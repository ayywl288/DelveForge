"""DelveForge SWE-bench Multilingual calibration CLI (Python 3.11+)."""
import argparse
import csv
import hashlib
import json
import os
import subprocess
import sys
import time
import uuid
from contextlib import contextmanager

from artifacts import export, git, verify_base
from benchmark import DATA, GRADER, HERE, ROOT, SUITE, TASKS, baseline, docker, grade, image_ref, read, save, task
from benchmark import reference_roundtrip
from workbench import CHOICE, LIMIT, SECONDS, SERVICE, api, check_policy, identity, observe, project, settle, trace
from workbench import progress, verify_stopped


@contextmanager
def exclusive():
    DATA.mkdir(parents=True, exist_ok=True)
    with (DATA / "suite.lock").open("a+b") as handle:
        handle.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def setup():
    for name, remote, revision in [
        ("tasks", "swe-bench-multilingual-tasks", TASKS), ("grader", "SWE-bench", GRADER)
    ]:
        path = DATA / name
        if not path.exists():
            path.mkdir()
            git(path, "init", "-q")
            git(path, "fetch", "--depth=1", "--no-tags", "https://github.com/SWE-bench/" + remote, revision)
            git(path, "checkout", "--detach", revision)
        if git(path, "rev-parse", "HEAD").decode().strip() != revision:
            raise ValueError("unexpected official checkout: " + name)
        if name == "grader" and git(path, "-c", "core.autocrlf=input", "status", "--porcelain"):
            raise ValueError("official grader checkout contains changes")
    recipe = hashlib.sha256((HERE / "Grader.Dockerfile").read_bytes()).hexdigest()
    tag = "df-eval/grader:" + GRADER[:7]
    receipt = DATA / "grader-build.json"
    if receipt.exists():
        saved = read(receipt)
        local = subprocess.run(["docker", "image", "inspect", tag, "--format", "{{.Id}}"], capture_output=True, text=True)
        if saved == {"revision": GRADER, "recipe": recipe, "image": local.stdout.strip()} and local.returncode == 0:
            return
    subprocess.run(["docker", "build", "-t", tag, "-f", HERE / "Grader.Dockerfile",
                    DATA / "grader"], check=True)
    save(receipt, {"revision": GRADER, "recipe": recipe, "image": docker("image", "inspect", tag, "--format", "{{.Id}}")})


PRODUCT = ("backend", "runtime", "pom.xml", ".mvn", "mvnw", "mvnw.cmd")


def source_version(repo):
    if git(repo, "ls-files", "--others", "--exclude-standard", "--", *PRODUCT):
        raise ValueError("untracked product sources require a reviewed build")
    changes = git(repo, "diff", "--binary", "HEAD", "--", *PRODUCT)
    return {"commit": git(repo, "rev-parse", "HEAD").decode().strip(),
            "productDiffSha256": hashlib.sha256(changes).hexdigest()}, changes


def build_agent():
    version, changes = source_version(ROOT)
    build = DATA / ("build-" + version["commit"][:10] + "-" + version["productDiffSha256"][:12])
    if not build.exists():
        git(ROOT, "clone", "-q", "--no-local", "--", str(ROOT), str(build))
        if changes:
            git(build, "apply", "--binary", "-", data=changes)
    if source_version(build)[0] != version:
        raise RuntimeError("evaluation build copy differs from requested source")
    test_env = {k: v for k, v in os.environ.items() if k not in {
        "DF_WORKBENCH_LIVE_HISTORY_DB", "DF_WORKBENCH_CACHE_FIXTURE",
        "DF_WORKBENCH_TEST_FIXTURE", "DF_WORKBENCH_TOOL_FIXTURE"}}
    with (DATA / "verify.log").open("wb") as log:
        subprocess.run([build / "mvnw.cmd", "clean", "verify"], cwd=build, stdout=log, stderr=subprocess.STDOUT,
                       env=test_env, check=True)
    jar, = (build / "backend/delveforge-app/target").glob("*.jar")
    with jar.open("rb") as stream:
        version["jarSha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
    save(DATA / "agent-build.json", version)


def revision():
    built = read(DATA / "agent-build.json")
    if built != {**source_version(ROOT)[0], "jarSha256": (SERVICE / "eval-jar.sha256").read_text().strip()}:
        raise RuntimeError("running evaluation build does not match the recorded product sources")
    return built


def preflight(i):
    folder = DATA / "checks" / i
    path = folder / "admission.json"
    m = read(path) if path.exists() else {"instanceId": i, "trialId": "admission-" + i}
    def checkpoint():
        save(path, m)
    current = task(i)
    if m.get("task") != current:
        for mode in ("noop", "oracle"):
            m.pop(mode, None)
    m.update(admitted=False, errors={}, task=current)
    checkpoint()
    try:
        source = baseline(i)
        work = project(m, source, checkpoint)
        verify_base(work, SUITE[i]["base"])
        m["roundTrip"] = export(source, SUITE[i]["base"], work, folder / "baseline.patch")
        if m["roundTrip"]["bytes"]:
            raise ValueError("imported files differ from baseline")
        m["importVerified"] = True
    except Exception as exc:
        m["errors"]["import"] = str(exc)
    checkpoint()
    try:
        m["referenceRoundTrip"] = reference_roundtrip(i, DATA / "base" / i, folder)
    except Exception as exc:
        m["errors"]["roundTrip"] = str(exc)
    checkpoint()
    try:
        image = image_ref(i)
        try:
            docker("image", "inspect", image, "--format", "{{.Id}}")
        except subprocess.CalledProcessError:
            subprocess.run(["docker", "pull", image], check=True)
        m["imageId"] = docker("image", "inspect", image, "--format", "{{.Id}}")
    except Exception as exc:
        m["errors"]["image"] = str(exc)
    checkpoint()
    if "image" not in m["errors"]:
        for mode in ("noop", "oracle"):
            try:
                helper = docker("image", "inspect", "df-eval/grader:" + GRADER[:7], "--format", "{{.Id}}")
                report = folder / mode / "official.json"
                saved = m.get(mode, {})
                if (saved.get("graderImage") != helper or not report.is_file()
                        or read(report)[i]["resolved"] != saved.get("resolved")):
                    m[mode] = grade(i, mode, folder / mode)
                result = m[mode]
                if result["resolved"] != (mode == "oracle") or (
                        mode == "noop" and not result["failedAcceptanceTests"] and not result.get("baselineCompileFailure")):
                    raise ValueError("unexpected official control result")
            except Exception as exc:
                m["errors"][mode] = str(exc)
            checkpoint()
    m["admitted"] = not m["errors"]
    checkpoint()
    print(i, "ADMITTED" if m["admitted"] else m["errors"], flush=True)
    return m["admitted"]


def freeze_config(m, path):
    harness = hashlib.sha256()
    for file in sorted(HERE.iterdir()):
        if file.suffix in {".py", ".json", ".acl", ".ps1", ".Dockerfile"}:
            harness.update(file.name.encode() + b"\0" + hashlib.sha256(file.read_bytes()).digest())
    fixed = {"agent": revision(), "choice": CHOICE, "protocol": "responses", "modelInfo": m["modelInfo"],
             "harnessSha256": harness.hexdigest(), "graderImage": m["graderImage"],
             "contextThreshold": 0.90, "requestLimit": LIMIT, "agentSeconds": SECONDS, "cpu": 2,
             "memoryMiB": 3072, "pids": 768, "concurrency": 1, "images": m["environment"]["images"]}
    if ("config" in m and m["config"] != fixed) or (path.exists() and read(path) != fixed):
        raise RuntimeError("suite configuration changed")
    if not path.exists():
        save(path, fixed)
    return fixed


def execute(i):
    folder = DATA / "trials" / i
    manifest = folder / "manifest.json"
    if folder.exists() and not manifest.is_file():
        raise RuntimeError("trial directory has no durable identity; inspect before starting")
    m = read(manifest) if manifest.is_file() else {
        "trialId": "pass1-" + str(uuid.uuid4()), "requestId": str(uuid.uuid4()), "instanceId": i,
        "created": time.time(), "transitions": []}
    if m.get("instanceId") != i or not m.get("trialId") or not m.get("requestId"):
        raise RuntimeError("invalid durable Trial identity")
    if m.get("graded"):
        return
    recovering = "submitClock" in m or "runId" in m or m.get("phase") in {"EXECUTE", "FREEZE", "GRADE", "DONE"}

    def checkpoint():
        save(manifest, m)
    try:
        if not recovering:
            admission = read(DATA / "checks" / i / "admission.json")
            if not admission["admitted"] or admission["task"] != task(i):
                raise RuntimeError("task admission missing or stale")
            for other in (DATA / "trials").glob("*/manifest.json"):
                previous = read(other)
                if other != manifest and "submitClock" in previous and not previous.get("snapshot"):
                    raise RuntimeError("previous submitted trial still needs settlement: " + previous["trialId"])
            m["task"] = admission["task"]
            m["phase"] = "PREPARE"
            checkpoint()
            source = baseline(i)
            settings = api("/settings")
            catalog = api("/models")
            if settings["maxConcurrentRuns"] != 1 or not any(
                    entry["provider"] == CHOICE["provider"] and entry["id"] == CHOICE["model"]
                    and CHOICE["effort"] in entry["verifiedEfforts"] for entry in catalog["models"]):
                raise RuntimeError("requires single-slot Workbench and verified tool-calling model/effort")
            work = project(m, source, checkpoint)
            verify_base(work, m["task"]["base"])
            pid = m["projectId"]
            route = f"/projects/{pid}/conversations"
            cid = identity(m, "conversationId", route, "title", m["trialId"], {"title": m["trialId"]}, checkpoint)
            api(f"/conversations/{cid}/model", CHOICE)
            feed = api(f"/conversations/{cid}")
            if feed["model"] != CHOICE or not feed["modelInfo"] or feed["runs"] or feed["messages"]:
                raise RuntimeError("conversation is not a clean configured trial")
            m["modelInfo"] = feed["modelInfo"]
            view = feed["modelInfo"]["model"]
            if (view["requestedModel"] != CHOICE["model"] or view["thinking"] != CHOICE["effort"]
                    or view["adapter"] != "OpenAI" or view["endpoint"] != "https://api.openai.com"):
                raise RuntimeError("selected provider binding differs from suite")
            helper = admission["oracle"]["graderImage"]
            if (admission["noop"]["graderImage"] != helper
                    or docker("image", "inspect", helper, "--format", "{{.Id}}") != helper):
                raise RuntimeError("admitted grader image unavailable")
            m["graderImage"] = helper
            if not (SERVICE / "environments" / pid / "environment.json").exists():
                api(f"/projects/{pid}/environment", {"action": "Restart"})
            m["environment"] = check_policy(pid)
            m["config"] = freeze_config(m, DATA / "suite-config.json")
            prompt = (DATA / "official/tasks" / i / "problem_statement.md").read_text(encoding="utf-8")
            now, wall = time.monotonic(), time.time()
            intent = dict(phase="EXECUTE", preparationSeconds=wall - m["created"],
                          submitClock=now, lastClock=now, submitWall=wall, lastWall=wall,
                          submittedTextSha256=hashlib.sha256(prompt.encode()).hexdigest())
            save(manifest, {**m, **intent})
            m.update(intent)
            try:
                ack = api(f"/conversations/{cid}/messages", {"text": prompt, "requestId": m["requestId"],
                                                              "requestLimit": LIMIT})
            except OSError as error:
                m["submissionError"] = str(error)
                recovering = True  # ACK 不确定时只查询原对话，不再投递消息。
            else:
                m["runId"] = str(uuid.UUID(ack["runId"]))
                m["started"] = ack.get("newlyStarted") is True
                if m["started"]:
                    m["startClock"] = m["submitClock"]
                progress(m, checkpoint)
        pid = m["projectId"]
        if "snapshot" not in m:
            if recovering:
                try:
                    if read(DATA / "suite-config.json") != m["config"]:
                        m["configurationError"] = "Trial and suite configuration differ"
                except (OSError, ValueError, KeyError) as error:
                    m["configurationError"] = f"cannot verify frozen configuration: {error}"
            feed = observe(m, checkpoint, recovering=recovering)
            m["phase"] = "FREEZE"
            settle(pid, m["runId"], feed, m["environment"]["containerId"])
            m["settled"] = True
            save(folder / "run.json", feed["runs"][0])
            checkpoint()
            trace(folder, m, feed)
            work = SERVICE / "projects" / pid
            source = baseline(i) if recovering else source
            snapshot = export(source, m["task"]["base"], work, folder / "model.patch")
            verify_stopped(pid, m["environment"]["containerId"])
            m["snapshot"] = snapshot
            checkpoint()
        m["phase"] = "GRADE"
        checkpoint()
        # 中断期间评测脚本或构建可能已更换。先结清前台，再核对原配置并交给评分器。
        freeze_config(m, DATA / "suite-config.json")
        if hashlib.sha256((folder / "model.patch").read_bytes()).hexdigest() != m["snapshot"]["sha256"]:
            raise RuntimeError("frozen patch hash changed")
        m["graded"] = grade(i, "trial", folder / "grader", folder / "model.patch", m["graderImage"])
        m["phase"] = "DONE"
        m.pop("error", None)
        checkpoint()
    except Exception as exc:
        m["error"] = str(exc)
        progress(m, checkpoint)
        raise


def summarize():
    rows = []
    for i in SUITE:
        folder = DATA / "trials" / i
        m = read(folder / "manifest.json") if (folder / "manifest.json").exists() else {}
        admission = DATA / "checks" / i / "admission.json"
        checks = read(admission) if admission.is_file() else {}
        run = read(folder / "run.json") if (folder / "run.json").exists() else {}
        graded = m.get("graded", {})
        detail = run.get("detail") or ""
        provider_failure = run.get("state") in {"PAUSED", "FAILED"} and detail.startswith((
            "模型请求失败（PROVIDER_CALL_FAILED", "提供方未接受当前凭据", "当前账户没有访问模型的权限",
            "当前端点或账户无法使用此模型", "本次任务绑定的凭据已更换或删除", "模型账户已限流或额度不足",
            "提供方账户余额或付款权限不足", "提供方不支持所选工具或思考设置", "提供方拒绝当前模型或参数"))
        scored = type(graded.get("resolved")) is bool
        states = {step["state"] for step in m.get("transitions", [])} | {run.get("state")}
        started = bool(m.get("started") or run.get("requests") or states & {
            "RUNNING", "PAUSED", "COMPLETED", "FAILED", "BLOCKED", "RECONCILING"})
        error = m.get("error")
        cause = {"PREPARE": "ENVIRONMENT_SETUP", "GRADE": "GRADER_FAILURE", "FREEZE": "ARTIFACT_FAILURE"}.get(
            m.get("phase"), "UNKNOWN") if error else "UNKNOWN"
        unreconciled = run.get("state") in ("RECONCILING", "BLOCKED", "STOPPING") or bool(
            error and m.get("phase") == "FREEZE" and not m.get("settled"))
        if unreconciled:
            cause = "UNRECONCILED"
        elif m.get("timingUncertain"):
            cause = "CONFIGURATION_CHANGED" if m.get("configurationError") else "TIMING_UNCERTAIN"
        elif not error and m.get("outcome") in ("REQUEST_LIMIT", "TIMEOUT"):
            cause = m["outcome"]
        if provider_failure:
            cause = "PROVIDER_FAILURE"
        invalid = bool(provider_failure or m.get("timingUncertain") or (
            error and m.get("phase") in {"PREPARE", "GRADE", "FREEZE"} and not unreconciled))
        valid = scored and not invalid
        autonomous = valid and started and m.get("outcome") == "COMPLETED" and run.get("state") == "COMPLETED"
        incomplete = bool(m and not scored and not invalid)
        rows.append(dict(instance=i, admitted=checks.get("admitted"), admissionErrors=checks.get("errors"),
                         noopResolved=checks.get("noop", {}).get("resolved"),
                         oracleResolved=checks.get("oracle", {}).get("resolved"),
                         trial=m.get("trialId"), phase=m.get("phase", "DEFERRED" if checks.get("admitted") is False else "UNATTEMPTED"),
                         state=run.get("state"), outcome=m.get("outcome"), resolved=graded.get("resolved"),
                         started=started, officiallyGraded=scored, infrastructureInvalid=invalid,
                         incomplete=incomplete, unreconciled=unreconciled, valid=valid,
                         autonomous=bool(autonomous and graded["resolved"]) if started else None,
                         requests=run.get("requests"), tokens=run.get("tokens"), toolsProposed=m.get("toolCalls"),
                         toolsExecuted=m.get("toolExecuted"), toolOutcomes=m.get("toolOutcomes"),
                         toolOutputGaps=m.get("toolOutputGaps"), toolStopRequests=m.get("toolStopRequests"),
                         toolFailures=m.get("toolFailures"), agentSeconds=m.get("agentSeconds"),
                         graderSeconds=graded.get("seconds"), error=error, cause=cause))
    scored = [r for r in rows if r["officiallyGraded"]]
    started = [r for r in rows if r["started"]]
    def rate(items, key):
        n = sum(bool(r[key]) for r in items)
        return {"numerator": n, "denominator": len(items), "rate": n / len(items) if items else None}
    summary = {"candidates": len(rows), "admitted": sum(r["admitted"] is True for r in rows),
               "deferred": sum(r["admitted"] is False for r in rows), "started": len(started),
               "officiallyGraded": len(scored), "officialResolved": rate(scored, "resolved"),
               "autonomousSuccess": rate(started, "autonomous"),
               "infrastructureInvalid": sum(r["infrastructureInvalid"] for r in rows),
               "incomplete": sum(r["incomplete"] for r in rows), "unreconciled": sum(r["unreconciled"] for r in rows),
               "unattempted": sum(r["admitted"] is True and not r["trial"] for r in rows), "rows": rows}
    reports = DATA / "reports"
    save(reports / "summary.json", summary)
    with (reports / "summary.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0])
        writer.writeheader()
        writer.writerows(rows)
    lines = ["# DelveForge observed pass@1", ""]
    for key, label in [("officialResolved", "Officially resolved patches / valid official grading results"),
                       ("autonomousSuccess", "Autonomous successes / all confirmed started Agent trials")]:
        metric = summary[key]
        lines.append(f"- {label} = {metric['numerator']} / {metric['denominator']}")
    lines += [f"- Planned candidates {len(rows)}, admitted {summary['admitted']}, deferred {summary['deferred']}",
              f"- Confirmed started {len(started)}, officially graded {len(scored)}, "
              f"infrastructure-invalid {summary['infrastructureInvalid']}, incomplete {summary['incomplete']}, "
              f"unreconciled foregrounds {summary['unreconciled']}, admitted and unattempted {summary['unattempted']}"]
    lines += ["", "| Instance | Admitted | Started | Phase | Outcome | Official resolved | Autonomous success |",
              "|---|---|---|---|---|---|---|"]
    for r in rows:
        values = (r[k] for k in ("instance", "admitted", "started", "phase", "outcome", "resolved", "autonomous"))
        lines.append("| " + " | ".join("—" if value is None else str(value) for value in values) + " |")
    (reports / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in summary.items() if k != "rows"}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("setup", "build-agent", "dry-run", "preflight", "validate-model", "trial", "report"))
    parser.add_argument("instance", nargs="?", choices=SUITE)
    parser.add_argument("--authorize-paid", action="store_true")
    args = parser.parse_args()
    if args.command in ("trial", "validate-model") and not args.authorize_paid:
        parser.error("explicit paid authorization required")
    if args.command == "trial" and not args.instance:
        parser.error("select one admitted task")
    if args.command == "report":
        summarize()
        return 0
    with exclusive():
        if args.command == "setup":
            setup()
        elif args.command == "build-agent":
            build_agent()
        elif args.command == "validate-model":
            print(api("/models/validate", CHOICE))
        elif args.command == "trial":
            execute(args.instance)
        else:
            ok = True
            for i in [args.instance] if args.instance else SUITE:
                try:
                    if args.command == "dry-run":
                        print(i, task(i)["base"], baseline(i))
                    else:
                        ok = preflight(i) and ok
                except Exception as exc:
                    ok = False
                    print(i, str(exc), file=sys.stderr)
            return 0 if ok else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
