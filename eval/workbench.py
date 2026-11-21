"""The runner only sends user actions to the loopback Workbench."""
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import UUID

from benchmark import DATA, HERE, docker, read, save


API = "http://127.0.0.1:18943/api/workbench"
SERVICE = DATA / "service"
CHOICE = {"provider": "openai", "model": "gpt-6.2-sol", "effort": "high"}
LIMIT = 96
SECONDS = 2700
TERMINAL = {"COMPLETED", "FAILED", "STOPPED", "PAUSED"}
FOREGROUND = {"RUNNING", "STOPPING", "BLOCKED", "RECONCILING"}


def api(route, body=None, *, timeout=180):
    data = None if body is None else json.dumps(body).encode()
    request = Request(API + route, data=data, headers={"Content-Type": "application/json"})
    with build_opener(ProxyHandler({})).open(request, timeout=timeout) as response:
        payload = response.read()
    return json.loads(payload) if payload else None


def identity(manifest, key, route, label, value, body, checkpoint):
    rows = [row for row in api(route) if row[label] == value]
    if key in manifest:
        if [r["id"] for r in rows] != [manifest[key]]:
            raise RuntimeError("saved identity no longer unique: " + key)
        return manifest[key]
    if len(rows) > 1:
        raise RuntimeError("ambiguous " + key)
    if rows:
        item = rows[0]
    else:
        if manifest.get("pending") == key:
            raise RuntimeError("unconfirmed creation; inspect server before retrying " + key)
        manifest["pending"] = key
        checkpoint()
        item = api(route, body)
    manifest[key] = str(UUID(item["id"]))
    manifest.pop("pending", None)
    checkpoint()
    return manifest[key]


def project(manifest, source, checkpoint):
    name = manifest["trialId"]
    pid = identity(manifest, "projectId", "/projects", "name", name,
                   {"name": name, "source": str(source)}, checkpoint)
    row = next(r for r in api("/projects") if r["id"] == pid)
    work = SERVICE / "projects" / pid
    if row["state"] != "READY" or work.resolve() != Path(row["workcopy"]).resolve():
        raise RuntimeError("project import or dedicated Workbench path mismatch")
    return work


def environment(pid, live=True):
    cfg = read(SERVICE / "environments" / pid / "environment.json")
    actual = json.loads(docker("inspect", cfg["containerId"]))[0]
    host = actual["HostConfig"]
    labels = actual["Config"]["Labels"]
    catalog = read(SERVICE / "runtime-catalog.json")
    mounts = [mount for mount in actual["Mounts"] if mount["Type"] == "bind"]
    if (cfg["project"] != pid or cfg["installation"] != labels.get("dev.delveforge.installation")
            or labels.get("dev.delveforge.project") != pid or actual["Id"] != cfg["containerId"]
            or cfg["owner"] != labels.get("dev.delveforge.workbench") or cfg["installation"] != catalog["owner"]
            or cfg["images"]["egress"] != catalog["images"]["egress"]
            or set(actual["NetworkSettings"]["Networks"]) != {cfg["owner"] + "-inside"}
            or [mount["Destination"] for mount in mounts] != ["/projects/" + pid]
            or host["Privileged"] or actual["Image"] != cfg["images"]["development"]
            or (host["NanoCpus"], host["Memory"], host["PidsLimit"]) != (2_000_000_000, 3072 * 2**20, 768)
            or (live and not actual["State"]["Running"])):
        raise RuntimeError("project runtime identity or resource budget mismatch")
    return cfg


def check_policy(pid):
    cfg = environment(pid)
    net = json.loads(docker("network", "inspect", cfg["owner"] + "-inside"))[0]
    if not net["Internal"]:
        raise RuntimeError("development network is not internal")
    policy = docker("exec", cfg["owner"] + "-egress", "cat", "/tmp/squid.conf").splitlines()
    required = (HERE / "downloads.acl").read_text().strip().splitlines()
    rules = [line for line in policy if line.startswith("http_access ")]
    if rules[-2:] != required[-2:] or any(line not in policy for line in required) or "http_access allow all" in rules:
        raise RuntimeError("evaluation egress ACL not active")
    for url, code in [("https://github.com/", "403"), ("https://registry.npmjs.org/", "200")]:
        # CONNECT 被代理拒绝时 curl 用退出码 56；判断代理返回的实际 HTTP 状态。
        result = subprocess.run(["docker", "exec", cfg["containerId"], "curl", "-s", "--proxy", "http://egress:3128",
                                 "--noproxy", "", "--max-time", "20", "-o", "/dev/null", "-w", "%{http_connect}", url],
                                capture_output=True, text=True)
        if result.stdout.strip() != code or (code == "200" and result.returncode != 0):
            raise RuntimeError("download policy probe failed: " + url)
    return {"containerId": cfg["containerId"], "images": cfg["images"], "generation": cfg["owner"],
            "githubConnect": 403, "npmConnect": 200}


def settle(pid, run_id, feed, generation):
    if (feed["activeTools"] or any(f["projectId"] == pid for f in feed["foregrounds"])
            or len(feed["runs"]) != 1 or feed["runs"][0]["id"] != run_id
            or feed["runs"][0]["state"] not in TERMINAL):
        raise RuntimeError("unsettled project; intervention required")
    if api(f"/projects/{pid}/maintenance")["operation"]:
        raise RuntimeError("project maintenance active")
    if len(api(f"/projects/{pid}/conversations")) != 1:
        raise RuntimeError("trial project has additional conversations")
    cfg = environment(pid, live=False)
    if cfg["containerId"] != generation:
        raise RuntimeError("environment generation changed")
    docker("stop", generation)
    verify_stopped(pid, generation)


def verify_stopped(pid, generation):
    if (environment(pid, live=False)["containerId"] != generation
            or docker("inspect", "-f", "{{.State.Running}}", generation) != "false"):
        raise RuntimeError("project processes have not stopped")


def decision(run, elapsed, timed_out):
    state = run["state"]
    if state not in TERMINAL | FOREGROUND | {"QUEUED"}:
        raise ValueError("unknown Run state")
    if timed_out or (elapsed is not None and elapsed >= SECONDS):
        return "TIMEOUT"
    if state == "PAUSED" and run["requests"] >= LIMIT and "模型请求总上限" in (run.get("detail") or ""):
        return "REQUEST_LIMIT"
    return state


def progress(m, checkpoint):
    """身份已持久化；运行中的进度写入失败只影响可观察性。"""
    try:
        checkpoint()
    except OSError as error:
        detail = str(error)
        if m.get("progressError") != detail:
            print(f"Trial progress write failed: {detail}", file=sys.stderr, flush=True)
        m["progressError"] = detail


def recover_identity(m, feed, owners):
    if [(r["project_id"], r["id"], r["title"]) for r in owners] != [(m["projectId"], m["conversationId"], m["trialId"])]:
        raise ValueError("saved project/conversation ownership is ambiguous")
    runs = feed["runs"]
    if len(runs) != 1 or ("runId" in m and runs[0]["id"] != m["runId"]):
        raise ValueError("original Run is missing or ambiguous; submission will not be repeated")
    if "runId" not in m:
        # ACK 丢失时，只接管原专属对话中与唯一提交完全吻合的 Run。
        inputs = [r for r in feed["messages"] if r["role"] in ("user", "pending")]
        digest = m.get("submittedTextSha256") or m["task"]["assets"]["problem_statement.md"]
        if (len(inputs) != 1 or hashlib.sha256(inputs[0]["text"].encode()).hexdigest() != digest
                or inputs[0]["run_id"] not in (None, runs[0]["id"])):
            raise ValueError("cannot identify original submission; intervention required")
        m["runId"] = str(UUID(runs[0]["id"]))


def server_time(value):
    stamp = datetime.fromisoformat(value)
    if stamp.tzinfo is None:
        raise ValueError("Workbench timestamp has no timezone")
    return stamp.timestamp()


def elapsed_time(m, run, now, wall, recovering):
    previous = m["lastClock"]
    # 双钟核对检测主机重启或时钟跳变；5 秒是钟差容忍量，与观测间隔无关。
    submit_wall = m.get("submitWall", m["lastWall"] - (previous - m["submitClock"]))
    if (now < previous or previous < m["submitClock"] or
            abs((wall - submit_wall) - (now - m["submitClock"])) > 5 or
            abs((wall - m["lastWall"]) - (now - previous)) > 5):
        raise ValueError("saved monotonic and UTC anchors disagree")
    state = run["state"]
    start = now if state == "QUEUED" else m.get("startClock", previous)
    if not m["submitClock"] <= start <= now:
        raise ValueError("invalid execution start anchor")
    elapsed = now - start
    if recovering or (state in TERMINAL and run.get("ended_at")):
        queued = server_time(run["started_at"])  # Workbench 记录的是入队时间。
        ended = state in TERMINAL and run.get("ended_at")
        end = server_time(ended) if ended else wall
        if not submit_wall - 5 <= queued <= end <= wall + 5:
            raise ValueError("inconsistent Workbench timestamps")
        if recovering and state != "QUEUED":
            start = max(start, min(m["submitClock"] + (queued - submit_wall), now + (queued - wall)))
        start_wall = submit_wall + (start - m["submitClock"])
        if end < start_wall - 5:
            raise ValueError("Workbench end precedes observed execution")
        bound = max(0, end - start_wall)
        elapsed = bound if ended else max(now - start, bound)
    m["queueSeconds"] = start - m["submitClock"]
    if state != "QUEUED":
        m["startClock"] = start
        return elapsed
    return None


def observe(m, checkpoint, *, recovering=False):
    """在场时执行外部截止时间。整个观察进程缺席期间，Workbench 仍执行自己的请求上限。"""
    route = "/conversations/" + m["conversationId"]

    def stop(wall, action="stop"):
        m["stopAction"] = action
        try:
            api(f"/runs/{m['runId']}/{action}", {}, timeout=5)
            m["stopRequested"] = wall
        except OSError as error:
            m["stopError"] = str(error)
            print(f"Stop of {m['runId']} unconfirmed: {error}", file=sys.stderr, flush=True)

    while True:
        try:
            if recovering:
                owners = api(f"/projects/{m['projectId']}/conversations", timeout=5)
            remaining = SECONDS - (m.get("lastClock", 0) - m["startClock"]) if "startClock" in m else SECONDS
            feed = api(route, timeout=max(1, min(5, remaining)))
        except OSError as error:
            now = time.monotonic()
            # 最近一次已知排队之后也可能已开始执行，失联不能重新起算。
            last = m.get("lastClock", now)
            expired = now - m.get("startClock", last) >= SECONDS
            rejected = isinstance(error, HTTPError) and 400 <= error.code < 500
            wall = time.time()
            drift = ("lastClock" not in m or "lastWall" not in m or now < last or
                     abs((now - last) - (wall - m["lastWall"])) > 5)
            if not (expired or rejected or drift):
                time.sleep(1)
                continue
            if drift or rejected:
                m["timingUncertain"] = True
            m["outcome"] = "TIMEOUT" if expired and not drift else "TIMING_UNCERTAIN"
            if recovering:
                m.update(timingUncertain=True, identityError="could not verify original Run ownership")
                raise RuntimeError(f"recovery cannot verify original Run; intervention required: {error}") from error
            if "runId" in m and not (m.get("stopRequested") or m.get("stopError")):
                stop(wall)
                if not rejected:
                    progress(m, checkpoint)
                    continue  # Stop 响应可能丢失，下一次读取以 Workbench 状态为准。
            progress(m, checkpoint)
            raise RuntimeError(f"Workbench unreachable: {error}; Stop status requires intervention") from error

        if recovering:
            try:
                recover_identity(m, feed, owners)
            except ValueError as error:
                m.update(outcome="TIMING_UNCERTAIN", timingUncertain=True, identityError=str(error))
                raise
        runs = feed["runs"]
        if len(runs) != 1 or runs[0]["id"] != m["runId"]:
            m["timingUncertain"] = True
            raise RuntimeError("original Run ownership ambiguous; intervention required")
        run = runs[0]
        state = run["state"]
        now, wall = time.monotonic(), time.time()
        try:
            elapsed = elapsed_time(m, run, now, wall, recovering)
        except (KeyError, TypeError, ValueError, OverflowError) as error:
            m["timingUncertain"] = True
            m["timingError"] = str(error)
            elapsed = None
        cfg = m.get("config")
        if (recovering and not cfg) or (cfg and (
                run.get("allowance") != LIMIT or cfg["requestLimit"] != LIMIT or feed.get("model") != CHOICE or
                cfg["choice"] != CHOICE or feed.get("modelInfo") != cfg["modelInfo"])):
            m["configurationError"] = "frozen Workbench configuration changed"
        if run["requests"] > LIMIT or m.get("configurationError"):
            m["timingUncertain"] = True

        outcome = decision(run, elapsed, m.get("outcome") == "TIMEOUT")
        if m.get("timingUncertain") and outcome != "TIMEOUT":
            outcome = "CONFIGURATION_CHANGED" if m.get("configurationError") else "TIMING_UNCERTAIN"
        m.update(outcome=outcome, agentSeconds=elapsed, lastWall=wall, lastClock=now)
        changed = not m["transitions"] or m["transitions"][-1]["state"] != state
        if changed:
            m["transitions"].append({"state": state, "time": wall, "requests": run["requests"]})
        if state not in TERMINAL | {"STOPPING"} and outcome in {"TIMEOUT", "TIMING_UNCERTAIN", "CONFIGURATION_CHANGED"}:
            action = "cancel-queue" if state == "QUEUED" else "stop"
            if m.get("stopAction") == action and m.get("stopError"):
                raise RuntimeError(f"Stop unconfirmed, Run still {state}: {m['stopError']}")
            if not m.get("stopRequested") or m.get("stopAction", "stop") != action:
                stop(wall, action)
                changed = True
            elif state in {"RUNNING", "QUEUED"}:
                raise RuntimeError(f"Stop acknowledged but original Run still {state}; intervention required")
        if changed or recovering:
            progress(m, checkpoint)
        recovering = False
        if state in TERMINAL:
            return feed
        if state == "BLOCKED" and not m.get("reconciled"):
            m["reconciled"] = wall
            checkpoint()  # 核对意图必须先落盘，避免中断后重复派发。
            api(f"/runs/{m['runId']}/reconcile", {})
        if state in {"BLOCKED", "STOPPING", "RECONCILING"}:
            m.setdefault("settlingSince", wall)
            if wall - m["settlingSince"] > 180:
                raise RuntimeError("foreground execution unresolved; intervention required")
        time.sleep(1)


def redact(text):
    for key, value in os.environ.items():
        if value and len(value) >= 8 and key.endswith(("_API_KEY", "_ACCESS_TOKEN")):
            text = text.replace(value, "[redacted]")
    return re.sub(r"(?i)(authorization\s*[:=]\s*(?:bearer|basic)?\s*)[^\s,\"']+"
                  r"|\b(?:bearer|basic)\s+[a-z0-9._~+/=-]+|\bsk-[\w-]{10,}|\b[0-9a-f]{32}\.[\w-]{12,}",
                  "[redacted]", text)


def public_messages(m, feed):
    keys = ("seq", "run_id", "role", "text", "calls", "provider_id", "tool_name", "recorded_at")
    return [{key: redact(row[key]) if isinstance(row.get(key), str) else row.get(key) for key in keys}
            for row in feed["messages"] if row["run_id"] == m["runId"]]


def tool_outcome(receipt):
    if receipt.get("notStarted") is True:
        return "NOT_STARTED"
    if receipt.get("timedOut") is True:
        return "TIMEOUT"
    if type(receipt.get("exit")) is int:
        return "SUCCESS" if receipt["exit"] == 0 else "NONZERO_EXIT"
    return "UNKNOWN"


def trace(folder, m, feed):
    messages = [row for row in feed["messages"] if row["run_id"] == m["runId"]]
    save(folder / "trace.json", public_messages(m, feed))
    calls = executed = gaps = stops = 0
    outcomes = dict.fromkeys(("SUCCESS", "NONZERO_EXIT", "TIMEOUT", "NOT_STARTED", "UNKNOWN"), 0)
    seen = set()
    for row in messages:
        calls += len(json.loads(row["calls"] or "[]"))
        if row["role"] != "tool":
            continue
        receipt = json.loads(row["text"])
        invocation = str(UUID(receipt["invocation"])) if receipt.get("invocation") else None
        if invocation and invocation in seen:
            continue
        outcome = tool_outcome(receipt)
        outcomes[outcome] += 1
        stops += receipt.get("stopRequested") is True
        gap = receipt.get("outputGap") is True
        confirmed = outcome in {"SUCCESS", "NONZERO_EXIT", "TIMEOUT"} or (
            outcome == "UNKNOWN" and (receipt.get("capturedBytes") or 0) > 0)
        if not invocation:
            gaps += gap
            executed += confirmed
            continue
        seen.add(invocation)
        offset = 0
        streams = {}
        frames = []
        while True:
            page = api(f"/conversations/{m['conversationId']}/outputs/{invocation}?offset={offset}&limit=8192")
            gap |= page["gap"]
            for chunk in page["chunks"]:
                data = base64.b64decode(chunk["base64"], validate=True)
                channel = chunk["channel"]
                streams.setdefault(channel, bytearray()).extend(data)
                frames.append({"channel": channel, "bytes": len(data)})
            if page["next"] == page["bytes"]:
                break
            if page["next"] <= offset:
                raise ValueError("stalled output cursor")
            offset = page["next"]
        channels = {}
        redacted = False
        for channel, data in streams.items():
            raw = data.decode("utf-8", errors="surrogateescape")
            safe = redact(raw)
            redacted |= safe != raw
            channels[channel] = base64.b64encode(safe.encode("utf-8", errors="surrogateescape")).decode("ascii")
        save(folder / "tool-outputs" / (invocation + ".json"),
             {"bytes": page["bytes"], "gap": gap, "outcome": outcome, "redacted": redacted,
              "frames": frames, "base64": channels})
        gaps += gap
        executed += confirmed or (outcome == "UNKNOWN" and page["bytes"] > 0)
    m.update(toolCalls=calls, toolExecuted=executed, toolOutcomes=outcomes, toolOutputGaps=gaps,
             toolStopRequests=stops, toolFailures=outcomes["NONZERO_EXIT"] + outcomes["TIMEOUT"])
