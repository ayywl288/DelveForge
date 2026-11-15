"""控制进程失联后，用同一个 ticket 封住尚未开始的执行并核对前台进程组。"""
import fcntl
import json
import os
import signal
import sys
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from tool import file_result

ROOT = Path("/run/delveforge-calls")


def stat(pid):
    try:
        tail = Path("/proc", str(pid), "stat").read_text().rsplit(") ", 1)[1].split()
        return tail[0], int(tail[2]), tail[19]
    except (FileNotFoundError, ProcessLookupError):
        return None


@contextmanager
def locked(ticket):
    uuid.UUID(ticket)
    ROOT.mkdir(mode=0o700, exist_ok=True)
    directory = ROOT / ticket
    directory.mkdir(mode=0o700, exist_ok=True)
    with (directory / "lock").open("a") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        yield directory


def save(path, data):
    staged = path.with_suffix(".new")
    staged.write_text(json.dumps(data))
    staged.replace(path)


def live(group, birth):
    leader = stat(group)
    if leader and leader[2] != birth:
        return False
    for p in Path("/proc").iterdir():
        if p.name.isdecimal():
            info = stat(p.name)
            if info and info[0] not in ("Z", "X") and info[1] == group:
                return True
    return False


def fence(ticket, terminate):
    with locked(ticket) as directory:
        (directory / "closed").touch()
        leader = directory / "leader"
        if not leader.exists():
            return {"ended": True, "started": False, "detail": "尚未进入命令，启动门已关闭"}
        group, birth = json.loads(leader.read_text())
        if terminate:
            for sig, grace in ((signal.SIGTERM, 0.6), (signal.SIGKILL, 0.6)):
                if not live(group, birth):
                    break
                try:
                    os.killpg(group, sig)
                except ProcessLookupError:
                    pass
                time.sleep(grace)
        ended = not live(group, birth)
        return {"ended": ended, "started": True, "detail": "前台进程组已结束" if ended else "原前台进程组仍存活"}


def run(ticket, tool, args):
    if os.getsid(0) != os.getpid():
        os.setsid()
    with locked(ticket) as directory:
        if (directory / "closed").exists() or (directory / "leader").exists():
            print("该调用已封存", file=sys.stderr)
            return 125
        pid = os.getpid()
        save(directory / "leader", [pid, stat(pid)[2]])
    home = Path(os.environ["HOME"])
    home.mkdir(parents=True, exist_ok=True)
    maven = home / ".m2"
    maven.mkdir(exist_ok=True)
    if not (maven / "settings.xml").exists():
        try:
            with (maven / "settings.xml").open("x") as settings:
                settings.write(Path("/opt/delveforge/maven-settings.xml").read_text())
        except FileExistsError:
            pass
    if tool != "Bash":
        return file_result(tool, args)
    # JVM 在 Linux 上默认从 passwd 读取 /root；与 shell 共用项目 HOME 才能读取持久化的工具配置。
    options = os.environ.get("JAVA_TOOL_OPTIONS", "")
    os.environ["JAVA_TOOL_OPTIONS"] = (options + " -Duser.home=" + str(home)).strip()
    os.environ.pop("MAVEN_CONFIG", None)
    command = ["/bin/bash", "-c", args["command"]]
    if len(args["command"].encode()) > 65536:
        script = os.memfd_create("delveforge-bash", flags=0)
        with os.fdopen(os.dup(script), "wb") as stream:
            stream.write(args["command"].encode())
        os.lseek(script, 0, os.SEEK_SET)
        command = ["/bin/bash", "/proc/self/fd/" + str(script)]
    # exec 保留已登记的 PID 与会话；显式 setsid 的后台服务脱离前台组。
    os.execvpe(command[0], command, os.environ)


if __name__ == "__main__":
    if sys.argv[1] == "fence":
        print(json.dumps(fence(sys.argv[2], sys.argv[3] == "stop"), ensure_ascii=False))
    else:
        sys.exit(run(sys.argv[2], sys.argv[3], json.loads("".join(sys.argv[4:]))))
