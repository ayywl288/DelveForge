"""登记 HTTP 预览时核对 socket 的项目归属及唯一持有进程。"""
import os
import sys
import uuid
from pathlib import Path

PROC = Path("/proc")
LOCAL = {"00000000", "0100007F", "0" * 32, "0" * 24 + "01000000"}


def listeners(port):
    result = set()
    for table in ("tcp", "tcp6"):
        try:
            lines = (PROC / "net" / table).read_text().splitlines()[1:]
        except FileNotFoundError:
            continue
        for line in lines:
            fields = line.split()
            address, number = fields[1].split(":")
            if fields[3] == "0A" and address in LOCAL and int(number, 16) == port:
                result.add(fields[9])
    return result


def belongs(pid, project):
    # 环境只在实际持有该 socket 的进程内核对。独立启动的服务也可用其项目目录证明归属。
    try:
        for entry in (pid / "environ").read_bytes().split(b"\0"):
            if entry.startswith(b"DF_PROJECT_ID="):
                return entry == b"DF_PROJECT_ID=" + project.encode("ascii")
        cwd = (pid / "cwd").resolve(strict=True)
        return cwd.is_relative_to(Path("/projects") / project)
    except (PermissionError, FileNotFoundError, ProcessLookupError):
        return False


def owned_inode(project, port):
    found = listeners(port)
    if len(found) != 1:
        return None
    inode = found.pop()
    socket = "socket:[" + inode + "]"
    owners = set()
    for pid in PROC.iterdir():
        if not pid.name.isdecimal():
            continue
        try:
            for fd in (pid / "fd").iterdir():
                try:
                    if os.readlink(fd) != socket:
                        continue
                except (FileNotFoundError, ProcessLookupError, PermissionError):
                    continue
                if not belongs(pid, project):
                    return None
                # /proc/stat 的 comm 允许包含空格与括号。启动时钟用于区分被复用的 PID。
                started = (pid / "stat").read_text().rsplit(")", 1)[1].split()[19]
                owners.add(pid.name + ":" + started)
                break
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            continue
    if owners and listeners(port) == {inode}:
        return inode, owners.pop() if len(owners) == 1 else ""
    return None


if __name__ == "__main__":
    project = str(uuid.UUID(sys.argv[1]))
    port = int(sys.argv[2])
    if 0 < port <= 65535 and port not in (8400, 8401, 8402):
        result = owned_inode(project, port)
        if result:
            print(" ".join(result).strip() if "--identity" in sys.argv[3:] else result[0])
