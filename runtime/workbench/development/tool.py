"""同一 Linux 环境里的文件工具。文件内容版本是原始字节的 SHA-256。"""
import hashlib
import json
import os
import stat
import sys
import tempfile
from pathlib import Path

MAX_PAGE = 16000
MAX_WRITE = 1048576


def version(path):
    with path.open("rb") as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(131072), b""):
            digest.update(block)
        return digest.hexdigest()


def read(path, args):
    start = args.get("start", 1)
    count = args.get("count", 120)
    if type(start) is not int or type(count) is not int or start < 1 or not 1 <= count <= 500:
        raise ValueError("start 从 1 开始，count 范围为 1 到 500")
    key = version(path)
    lines = []
    used = 0
    eof = True
    with path.open("rb") as stream:
        line = 1
        while line < start:
            part = stream.readline(65536)
            if not part:
                break
            if part.endswith(b"\n"):
                line += 1
        while True:
            part = stream.readline(MAX_PAGE + 1)
            if not part:
                break
            if len(lines) == count:
                eof = False
                break
            if len(part) > MAX_PAGE:
                if not lines:
                    raise ValueError("该行超过单页字节上限，可用 Bash 处理")
                eof = False
                break
            if used + len(part) > MAX_PAGE:
                eof = False
                break
            if b"\x00" in part:
                raise ValueError("二进制内容可用 Bash 处理")
            lines.append(part.decode("utf-8"))
            used += len(part)
    if version(path) != key:
        raise ValueError("文件在读取期间已改变，请重新读取")
    return {"version": key, "text": "".join(lines), "start": start,
            "next": None if eof else start + len(lines), "eof": eof}


def existing(path):
    if path.stat().st_size > MAX_WRITE:
        raise ValueError("该文件超过文本替换上限，可用 Bash 处理")
    return path.read_bytes()


def replace(path, content, expected):
    if len(content) > MAX_WRITE:
        raise ValueError("写入内容超出上限")
    # 在同目录准备，再核对版本并原子更名。独立编辑器不参与此处的锁。
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temp:
        staging = Path(temp.name)
        try:
            temp.write(content)
            temp.flush()
            os.fsync(temp.fileno())
        except BaseException:
            temp.close()
            staging.unlink(missing_ok=True)
            raise
    try:
        os.chmod(staging, stat.S_IMODE(path.stat().st_mode))
        if version(path) != expected:
            raise ValueError("VERSION_CONFLICT，请重新读取")
        os.replace(staging, path)
    finally:
        staging.unlink(missing_ok=True)
    return {"version": hashlib.sha256(content).hexdigest(), "bytes": len(content)}


def invoke(tool, args):
    if tool not in {"Read", "Edit", "Write"} or not isinstance(args.get("path"), str) or not args["path"]:
        raise ValueError("工具名或路径无效")
    path = Path(args["path"]).resolve()
    if tool == "Read":
        return read(path, args)
    if tool == "Edit":
        old, new = args["old"], args["new"]
        if not isinstance(old, str) or not old or not isinstance(new, str):
            raise ValueError("old 必须是非空文本，new 必须是文本")
        raw = existing(path)
        data = raw.decode("utf-8")
        index = data.find(old)
        if index < 0 or data.find(old, index + 1) != -1:
            raise ValueError("EDIT_CONFLICT，原文必须恰好匹配一处")
        return replace(path, (data[:index] + new + data[index + len(old):]).encode(), hashlib.sha256(raw).hexdigest())
    content = args["content"].encode("utf-8")
    if len(content) > MAX_WRITE:
        raise ValueError("写入内容超出上限")
    expected = args.get("expectedVersion")
    if expected is not None:
        return replace(path, content, expected)
    # 用同目录临时文件加原子 hard link 实现完整内容的排他发布。
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as tmp:
        name = Path(tmp.name)
        try:
            tmp.write(content)
            tmp.flush()
            os.fsync(tmp.fileno())
        except BaseException:
            tmp.close()
            name.unlink(missing_ok=True)
            raise
    try:
        os.link(name, path)
    finally:
        name.unlink(missing_ok=True)
    return {"version": hashlib.sha256(content).hexdigest(), "bytes": len(content)}


def file_result(tool, args):
    try:
        result = invoke(tool, args)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as failure:
        print(json.dumps({"error": str(failure)[:1024]}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    sys.exit(file_result(sys.argv[1], json.loads(sys.argv[2])))
