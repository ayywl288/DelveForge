"""通过本机 Workbench API 提交一次有界任务；保存可核对的证据，不自动重发消息或继续 Run。"""
import argparse
import json
import time
import uuid
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener


class WorkbenchClient:
    def __init__(self, origin):
        url = urlsplit(origin)
        if url.scheme != "http" or url.hostname != "127.0.0.1" or url.username or url.path not in ("", "/"):
            raise ValueError("请指定本机隔离控制端的 HTTP 地址")
        self.base = origin.rstrip("/") + "/api/workbench"
        self.http = build_opener(ProxyHandler({}))

    def api(self, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        req = Request(self.base + path, data=data, headers={"Content-Type": "application/json"})
        with self.http.open(req, timeout=60) as response:
            payload = response.read()
        return json.loads(payload) if payload else None

    def project(self, name, source=None):
        return self.api("/projects", {"name": name, "source": str(source) if source else None})["id"]

    def conversation(self, project, title):
        return self.api("/projects/" + project + "/conversations", {"title": title})["id"]

    def send(self, conversation, text, limit=None):
        body = {"requestId": str(uuid.uuid4()), "text": text}
        if limit is not None:
            body["requestLimit"] = limit
        return self.api("/conversations/" + conversation + "/messages", body)

    def feed(self, conversation):
        return self.api("/conversations/" + conversation)

    def observe(self, conversation, run, timeout=1200):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            feed = self.feed(conversation)
            current = next(row for row in feed["runs"] if row["id"] == run)
            if current["state"] not in ("RUNNING", "STOPPING", "RECONCILING"):
                return feed
            time.sleep(1)
        raise TimeoutError("达到观察期限，可用原对话和 Run ID 查询持久记录")

    def evidence(self, conversation, run, version):
        feed = self.feed(conversation)
        row = next(item for item in feed["runs"] if item["id"] == run)
        tools = []
        start = datetime.fromisoformat(row["started_at"])
        end = datetime.fromisoformat(row["ended_at"]) if row.get("ended_at") else None
        for message in feed["messages"]:
            if message["role"] != "tool" or message["run_id"] != run:
                continue
            receipt = json.loads(message["text"])
            if receipt.get("capturedBytes") and receipt.get("invocation"):
                page = self.api("/conversations/" + conversation + "/outputs/" + receipt["invocation"] + "?limit=32")
                if page["bytes"] < (receipt.get("capturedBytes") or 0):
                    raise ValueError("输出引用与已捕获字节不一致")
            tools.append({"seq": message["seq"], "name": message["tool_name"],
                          **{k: receipt.get(k) for k in ("invocation", "exit", "notStarted",
                                                        "outputGap", "displayTruncated", "capturedBytes")}})
        return {"version": version, "conversation": conversation, "run": row,
                "durationSeconds": (end - start).total_seconds() if end else None,
                "model": self.api("/settings")["model"], "tools": tools}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--prompt", required=True, type=Path)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--limit", required=True, type=int)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--expect-file", action="append", default=[])
    args = parser.parse_args()
    if args.report.exists() or not 1 <= args.limit <= 10000:
        parser.error("请使用新的证据路径及有效的任务总上限")
    client = WorkbenchClient(args.origin)
    project = client.project(args.title, args.source)
    conversation = client.conversation(project, args.title)
    ack = client.send(conversation, args.prompt.read_text(encoding="utf-8"), args.limit)
    print(json.dumps({"project": project, "conversation": conversation, "receipt": ack}), flush=True)
    if not ack["newlyStarted"]:
        raise RuntimeError("消息已保存，当前未启动独立 Run")
    client.observe(conversation, ack["runId"])
    evidence = client.evidence(conversation, ack["runId"], args.version)
    workcopy = next(Path(p["workcopy"]) for p in client.api("/projects") if p["id"] == project).resolve()
    files = {}
    for name in args.expect_file:
        path = (workcopy / name).resolve()
        if not path.is_relative_to(workcopy):
            raise ValueError("验证路径必须在此工作副本内")
        files[name] = path.is_file()
    evidence.update(project=project, workcopy=str(workcopy), files=files)
    with args.report.open("x", encoding="utf-8") as report:
        json.dump(evidence, report, ensure_ascii=False, indent=2)
    return 0 if evidence["run"]["state"] == "COMPLETED" and all(files.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
