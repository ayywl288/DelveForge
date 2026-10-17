"""Windows-only, standard-library process spike. Never accepts an existing workspace.

All child commands are this file or the fixed CIM launcher generated below. Results
are experimental evidence, never a business Recovery authorization. Run: python
ownership_spike.py run --output-parent <existing task-owned temporary parent>.
"""
import argparse
import ctypes as C
from ctypes import wintypes as W
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import traceback
import uuid

if os.name != "nt":
    raise SystemExit("Windows only; no simulated fallback")

K = C.WinDLL("kernel32", use_last_error=True)
HANDLE = W.HANDLE
SIZE = C.c_size_t
WAIT_OBJECT_0, WAIT_ABANDONED, WAIT_TIMEOUT = 0, 0x80, 0x102
QUERY, TERMINATE, SYNCHRONIZE = 0x0400, 0x0001, 0x00100000
JOB_QUERY, JOB_TERMINATE = 0x0004, 0x0008
KILL_ON_CLOSE, BREAKAWAY, SILENT_BREAKAWAY = 0x2000, 0x800, 0x1000
SCRIPT = str(Path(__file__).resolve())
PYTHON = str(Path(sys.executable).resolve())


class STARTUPINFO(C.Structure):
    _fields_ = [("cb", W.DWORD), ("lpReserved", W.LPWSTR), ("lpDesktop", W.LPWSTR),
                ("lpTitle", W.LPWSTR), ("dwX", W.DWORD), ("dwY", W.DWORD),
                ("dwXSize", W.DWORD), ("dwYSize", W.DWORD), ("dwXCountChars", W.DWORD),
                ("dwYCountChars", W.DWORD), ("dwFillAttribute", W.DWORD),
                ("dwFlags", W.DWORD), ("wShowWindow", W.WORD), ("cbReserved2", W.WORD),
                ("lpReserved2", C.POINTER(W.BYTE)), ("hStdInput", HANDLE),
                ("hStdOutput", HANDLE), ("hStdError", HANDLE)]


class STARTUPINFOEX(C.Structure):
    _fields_ = [("StartupInfo", STARTUPINFO), ("lpAttributeList", C.c_void_p)]


class PROCESS_INFORMATION(C.Structure):
    _fields_ = [("hProcess", HANDLE), ("hThread", HANDLE),
                ("dwProcessId", W.DWORD), ("dwThreadId", W.DWORD)]


class BASIC_LIMIT(C.Structure):
    _fields_ = [("PerProcessUserTimeLimit", C.c_longlong), ("PerJobUserTimeLimit", C.c_longlong),
                ("LimitFlags", W.DWORD), ("MinimumWorkingSetSize", SIZE),
                ("MaximumWorkingSetSize", SIZE), ("ActiveProcessLimit", W.DWORD),
                ("Affinity", SIZE), ("PriorityClass", W.DWORD), ("SchedulingClass", W.DWORD)]


class IO_COUNTERS(C.Structure):
    _fields_ = [(name, C.c_ulonglong) for name in ("ReadOperationCount", "WriteOperationCount",
                "OtherOperationCount", "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]


class EXTENDED_LIMIT(C.Structure):
    _fields_ = [("BasicLimitInformation", BASIC_LIMIT), ("IoInfo", IO_COUNTERS),
                ("ProcessMemoryLimit", SIZE), ("JobMemoryLimit", SIZE),
                ("PeakProcessMemoryUsed", SIZE), ("PeakJobMemoryUsed", SIZE)]


class ACCOUNTING(C.Structure):
    _fields_ = [(name, C.c_longlong) for name in ("TotalUserTime", "TotalKernelTime",
                "ThisPeriodTotalUserTime", "ThisPeriodTotalKernelTime")] + [
                (name, W.DWORD) for name in ("TotalPageFaultCount", "TotalProcesses",
                "ActiveProcesses", "TotalTerminatedProcesses")]


def api(name, restype, *args):
    fn = getattr(K, name)
    fn.restype, fn.argtypes = restype, args
    return fn


create_mutex = api("CreateMutexW", HANDLE, C.c_void_p, W.BOOL, W.LPCWSTR)
release_mutex = api("ReleaseMutex", W.BOOL, HANDLE)
wait_handle = api("WaitForSingleObject", W.DWORD, HANDLE, W.DWORD)
close_handle = api("CloseHandle", W.BOOL, HANDLE)
create_job = api("CreateJobObjectW", HANDLE, C.c_void_p, W.LPCWSTR)
open_job = api("OpenJobObjectW", HANDLE, W.DWORD, W.BOOL, W.LPCWSTR)
set_job = api("SetInformationJobObject", W.BOOL, HANDLE, C.c_int, C.c_void_p, W.DWORD)
query_job = api("QueryInformationJobObject", W.BOOL, HANDLE, C.c_int, C.c_void_p, W.DWORD, C.c_void_p)
terminate_job = api("TerminateJobObject", W.BOOL, HANDLE, W.UINT)
open_process = api("OpenProcess", HANDLE, W.DWORD, W.BOOL, W.DWORD)
terminate_process = api("TerminateProcess", W.BOOL, HANDLE, W.UINT)
is_in_job = api("IsProcessInJob", W.BOOL, HANDLE, HANDLE, C.POINTER(W.BOOL))
initialize_attrs = api("InitializeProcThreadAttributeList", W.BOOL, C.c_void_p, W.DWORD, W.DWORD, C.POINTER(SIZE))
update_attrs = api("UpdateProcThreadAttribute", W.BOOL, C.c_void_p, W.DWORD, SIZE, C.c_void_p, SIZE,
                   C.c_void_p, C.c_void_p)
delete_attrs = api("DeleteProcThreadAttributeList", None, C.c_void_p)
create_process = api("CreateProcessW", W.BOOL, W.LPCWSTR, W.LPWSTR, C.c_void_p, C.c_void_p,
                     W.BOOL, W.DWORD, C.c_void_p, W.LPCWSTR, C.c_void_p, C.POINTER(PROCESS_INFORMATION))


def checked(ok, what):
    if not ok:
        raise OSError(C.get_last_error(), what)
    return ok


def save(path, value):
    # 仅本次新建目录内的原型日志；不是生产 SQLite 协议或断电持久性证明。
    temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    with temp.open("w", encoding="utf-8") as out:
        json.dump(value, out, sort_keys=True)
        out.flush()
        os.fsync(out.fileno())
    os.replace(temp, path)


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def until(predicate, seconds=10):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = predicate()
        if value:
            return value
        time.sleep(0.02)
    raise TimeoutError("bounded spike wait expired")


def names(folder):
    key = hashlib.sha256(str(folder.resolve()).casefold().encode()).hexdigest()
    return "Local\\DelveForge.M5F1." + key


def accounting(job):
    value = ACCOUNTING()
    checked(query_job(job, 1, C.byref(value), C.sizeof(value), None), "query job accounting")
    return {"active": value.ActiveProcesses, "total": value.TotalProcesses,
            "terminated": value.TotalTerminatedProcesses}


def limits(job):
    value = EXTENDED_LIMIT()
    checked(query_job(job, 9, C.byref(value), C.sizeof(value), None), "query job limits")
    return value.BasicLimitInformation.LimitFlags


def new_job(name):
    job = checked(create_job(None, name), "create job")
    if C.get_last_error() == 183:
        close_handle(job)
        raise RuntimeError("fresh operation name unexpectedly exists")
    info = EXTENDED_LIMIT()
    info.BasicLimitInformation.LimitFlags = KILL_ON_CLOSE
    try:
        checked(set_job(job, 9, C.byref(info), C.sizeof(info)), "configure job before launch")
        return job
    except BaseException:
        close_handle(job)
        raise


def launch_native(argv, folder, job=None, suspended=False, breakaway=False):
    startup = STARTUPINFOEX()
    startup.StartupInfo.cb = C.sizeof(startup) if job is not None else C.sizeof(STARTUPINFO)
    attrs = None
    flags = 0x08000000 | (0x4 if suspended else 0) | (0x01000000 if breakaway else 0)
    try:
        if job is not None:
            size = SIZE()
            initialize_attrs(None, 1, 0, C.byref(size))
            attrs = C.create_string_buffer(size.value)
            checked(initialize_attrs(attrs, 1, 0, C.byref(size)), "initialize attribute list")
            startup.lpAttributeList = C.cast(attrs, C.c_void_p)
            handles = (HANDLE * 1)(job)
            # 在创建时原子加入 Job；不采用创建后 Assign 的崩溃间隙。
            checked(update_attrs(attrs, 0, 0x0002000D, handles, C.sizeof(handles), None, None), "set JOB_LIST")
            flags |= 0x00080000
        result = PROCESS_INFORMATION()
        command = C.create_unicode_buffer(subprocess.list2cmdline(argv))
        checked(create_process(argv[0], command, None, None, False, flags, None, str(folder),
                               C.byref(startup), C.byref(result)), "CreateProcessW")
        close_handle(result.hThread)
        return result.hProcess, result.dwProcessId
    finally:
        if attrs is not None and startup.lpAttributeList:
            delete_attrs(attrs)


def writer(folder, label, seconds=45):
    save(folder / (label + ".pid.json"), {"pid": os.getpid(), "created_ns": time.time_ns()})
    end = time.monotonic() + seconds
    with (folder / (label + ".writes")).open("ab", buffering=0) as out:
        while time.monotonic() < end and not (folder / "stop-writers").exists():
            out.write(b"x")
            time.sleep(0.01)


def worker(folder, mode):
    if mode == "finite":
        writer(folder, "finite", seconds=0.3)
        return
    if mode == "tree":
        subprocess.Popen([PYTHON, SCRIPT, "child", str(folder), "branch"], creationflags=0x08000000)
        until(lambda: (folder / "grandchild.pid.json").exists())
        save(folder / "root-ready.json", {"pid": os.getpid()})
        # 根进程先退出，后代仍持续写入。
        return
    if mode == "branch":
        subprocess.Popen([PYTHON, SCRIPT, "child", str(folder), "grandchild"], creationflags=0x08000000)
        writer(folder, "child")
        return
    if mode == "breakaway":
        process = None
        try:
            process, pid = launch_native([PYTHON, SCRIPT, "child", str(folder), "escaped"], folder,
                                         breakaway=True)
            save(folder / "breakaway.json", {"created": True, "pid": pid})
        except OSError as error:
            save(folder / "breakaway.json", {"created": False, "error": error.errno})
        finally:
            if process:
                close_handle(process)
        return
    if mode == "broker":
        # Windows 的 WMI 服务代创建；只创建本脚本，所有路径都在新建 Fixture 中。
        cmd = subprocess.list2cmdline([PYTHON, SCRIPT, "child", str(folder), "brokered"])
        quote = lambda value: "'" + value.replace("'", "''") + "'"
        ps = ("$ErrorActionPreference='Stop'; $r=Invoke-CimMethod -ClassName Win32_Process -MethodName Create "
              "-Arguments @{CommandLine=" + quote(cmd) + ";CurrentDirectory=" + quote(str(folder))
              + "}; $r | Select-Object ReturnValue,ProcessId | ConvertTo-Json -Compress | "
              "Set-Content -Encoding utf8 -LiteralPath " + quote(str(folder / "broker-result.json")))
        powershell = str(Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe")
        result = subprocess.run([powershell, "-NoProfile", "-NonInteractive", "-Command", ps],
                                creationflags=0x08000000, timeout=15, capture_output=True)
        if result.returncode != 0:
            raise RuntimeError("fixed CIM launcher failed, exit=" + str(result.returncode))
        return
    writer(folder, mode)


def backend(folder, mode):
    gate = checked(create_mutex(None, False, names(folder) + ".gate"), "create gate")
    acquired = wait_handle(gate, 3000)
    if acquired not in (WAIT_OBJECT_0, WAIT_ABANDONED):
        raise RuntimeError("backend cannot acquire gate")
    job, process = None, None
    try:
        op = uuid.uuid4().hex
        job_name = names(folder) + ".job." + op
        save(folder / "ownership.json", {"protocol": 1, "operation": op, "job": job_name,
                                       "owner_pid_diagnostic_only": os.getpid(), "mode": mode})
        if mode != "plain":
            job = new_job(job_name)
        if mode == "before-launch":
            save(folder / "ready.json", {"stage": "before-launch"})
        else:
            child_mode = "tree" if mode in ("plain", "tree") else mode
            process, pid = launch_native([PYTHON, SCRIPT, "child", str(folder), child_mode], folder,
                                         job=job, suspended=(mode == "suspended"))
            if mode in ("plain", "tree"):
                until(lambda: (folder / "root-ready.json").exists())
            elif mode == "broker":
                until(lambda: (folder / "brokered.pid.json").exists())
            elif mode == "breakaway":
                until(lambda: (folder / "breakaway.json").exists())
            save(folder / "ready.json", {"stage": "created", "root_pid": pid,
                                         "job": accounting(job) if job else None})
        end = time.monotonic() + 35
        while time.monotonic() < end and not (folder / "finish-owner").exists():
            time.sleep(0.02)
        if job:
            checked(terminate_job(job, 97), "owner terminates own job")
            until(lambda: accounting(job)["active"] == 0)
            save(folder / "owner-stopped.json", accounting(job))
        else:
            (folder / "stop-writers").touch()
    finally:
        if process:
            close_handle(process)
        if job:
            close_handle(job)
        checked(release_mutex(gate), "release owner gate")
        close_handle(gate)


def contender(folder, output, stop=False, hold=False, expected=None):
    gate = checked(create_mutex(None, False, names(folder) + ".gate"), "open contender gate")
    job = None
    acquired = wait_handle(gate, 0)
    owns = acquired in (WAIT_OBJECT_0, WAIT_ABANDONED)
    answer = {"gate_wait": acquired, "job_empty_under_gate": False}
    try:
        if not owns:
            answer["reason"] = "BLOCKED_GATE"
        elif not (folder / "ownership.json").exists():
            answer["reason"] = "BLOCKED_LEGACY"
        else:
            record = load(folder / "ownership.json")
            if record.get("protocol") != 1 or not record.get("operation") or not record.get("job"):
                answer["reason"] = "BLOCKED_UNPROVEN_PROTOCOL"
            elif expected is not None and expected != record["operation"]:
                answer["reason"] = "BLOCKED_STALE_OPERATION"
            else:
                job = open_job(JOB_QUERY | JOB_TERMINATE, False, record["job"])
                if not job:
                    answer.update(reason="BLOCKED_NO_KERNEL_PROOF", error=C.get_last_error())
                elif limits(job) & (KILL_ON_CLOSE | BREAKAWAY | SILENT_BREAKAWAY) != KILL_ON_CLOSE:
                    answer["reason"] = "BLOCKED_UNSAFE_JOB"
                else:
                    answer["before"] = accounting(job)
                    if answer["before"]["active"] and not stop:
                        answer["reason"] = "BLOCKED_ACTIVE_JOB"
                    else:
                        if stop:
                            checked(terminate_job(job, 98), "spike explicitly stops own old job")
                        until(lambda: accounting(job)["active"] == 0)
                        answer.update(reason="JOB_ZERO_UNDER_GATE", job_empty_under_gate=True,
                                      after=accounting(job), operation=record["operation"])
        save(folder / output, answer)
        if hold and answer["job_empty_under_gate"]:
            until(lambda: (folder / "release-contender").exists(), 10)
    finally:
        if job:
            close_handle(job)
        if owns:
            release_mutex(gate)
        close_handle(gate)


class Experiments:
    def __init__(self, root):
        self.root, self.results = root, []

    def scenario(self, name, function):
        folder = self.root / name
        folder.mkdir()
        self.processes, self.handles, self.jobs, self.gates = [], [], [], []
        started = time.monotonic()
        try:
            data = function(folder)
            self.results.append(dict(name=name, passed=True, seconds=round(time.monotonic() - started, 3), **data))
        except BaseException:
            self.results.append(dict(name=name, passed=False, error=traceback.format_exc()))
        finally:
            # 不枚举、终止系统其他进程；只使用本实验取得的句柄和本次 Popen 对象。
            (folder / "stop-writers").touch()
            (folder / "finish-owner").touch()
            (folder / "release-contender").touch()
            for job in self.jobs:
                terminate_job(job, 99)
            for process in self.processes:
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=3)
            for handle in self.handles:
                if wait_handle(handle, 3000) == WAIT_TIMEOUT:
                    checked(terminate_process(handle, 99), "cleanup owned fixture process")
                    checked(wait_handle(handle, 3000) == 0, "fixture process exited")
                close_handle(handle)
            for job in self.jobs:
                close_handle(job)
            for gate in self.gates:
                close_handle(gate)
        save(self.root / "results.partial.json", self.results)
        print(json.dumps(self.results[-1], ensure_ascii=False), flush=True)

    def start(self, folder, mode):
        with (folder / (mode + ".log")).open("wb") as out:
            process = subprocess.Popen([PYTHON, SCRIPT, "backend", str(folder), mode], stdout=out,
                                       stderr=subprocess.STDOUT, creationflags=0x08000000)
        self.processes.append(process)
        until(lambda: (folder / "ready.json").exists() or process.poll() is not None)
        if not (folder / "ready.json").exists():
            raise RuntimeError((folder / (mode + ".log")).read_text())
        return process

    def keep_job(self, folder):
        name = load(folder / "ownership.json")["job"]
        job = checked(open_job(JOB_QUERY | JOB_TERMINATE, False, name), "retain experimental job handle")
        self.jobs.append(job)
        return job

    def keep_gate_name(self, folder):
        # 保留名字以真实观察 WAIT_ABANDONED；句柄不取得所有权，不阻止 Owner 崩溃。
        gate = checked(create_mutex(None, False, names(folder) + ".gate"), "retain gate identity")
        self.gates.append(gate)

    def writer_handle(self, folder, label):
        until(lambda: (folder / (label + ".pid.json")).exists())
        pid = load(folder / (label + ".pid.json"))["pid"]
        handle = checked(open_process(SYNCHRONIZE | QUERY | TERMINATE, False, pid), "open freshly launched own writer")
        self.handles.append(handle)
        return handle

    def probe(self, folder, output="probe.json", stop=False, hold=False, expected=None):
        args = [PYTHON, SCRIPT, "contender", str(folder), output]
        if stop:
            args.append("--stop")
        if hold:
            args.append("--hold")
        if expected:
            args.extend(["--expected", expected])
        with (folder / (output + ".log")).open("wb") as out:
            process = subprocess.Popen(args, stdout=out, stderr=subprocess.STDOUT, creationflags=0x08000000)
        self.processes.append(process)
        until(lambda: (folder / output).exists() or process.poll() is not None)
        if not (folder / output).exists():
            raise RuntimeError((folder / (output + ".log")).read_text())
        if not hold:
            assert process.wait(timeout=5) == 0
        return load(folder / output)

    def crash(self, process):
        # Popen.kill 在 Windows 调用 TerminateProcess：没有 Python finally / 优雅关闭。
        process.kill()
        process.wait(timeout=5)

    def growing(self, folder, label):
        path = folder / (label + ".writes")
        until(lambda: path.exists() and path.stat().st_size > 0)
        before = path.stat().st_size
        time.sleep(0.2)
        return {"before": before, "after": path.stat().st_size}

    def normal(self, folder):
        owner = self.start(folder, "tree")
        child = self.writer_handle(folder, "child")
        grandchild = self.writer_handle(folder, "grandchild")
        job = self.keep_job(folder)
        pre = accounting(job)
        assert pre["active"] >= 2 and pre["total"] >= 3
        blocked = self.probe(folder)
        assert blocked["reason"] == "BLOCKED_GATE"
        (folder / "finish-owner").touch()
        assert owner.wait(timeout=5) == 0
        assert wait_handle(child, 3000) == 0 and wait_handle(grandchild, 3000) == 0
        zero = self.probe(folder, "after.json")
        assert zero["job_empty_under_gate"] and zero["after"]["active"] == 0
        return dict(pre=pre, zero=zero)

    def natural_exit(self, folder):
        owner = self.start(folder, "finite")
        child = self.writer_handle(folder, "finite")
        job = self.keep_job(folder)
        assert wait_handle(child, 5000) == 0
        until(lambda: accounting(job)["active"] == 0)
        before_stop = accounting(job)
        assert before_stop["total"] >= 1 and not (folder / "finish-owner").exists()
        (folder / "finish-owner").touch()
        assert owner.wait(timeout=5) == 0
        zero = self.probe(folder)
        assert zero["job_empty_under_gate"]
        return dict(natural_exit_before_stop=before_stop, child_handle_signaled=True, probe=zero)

    def mutex_negative(self, folder):
        owner = self.start(folder, "plain")
        child = self.writer_handle(folder, "child")
        self.writer_handle(folder, "grandchild")
        self.keep_gate_name(folder)
        self.crash(owner)
        observed = self.probe(folder)
        growth = self.growing(folder, "child")
        assert observed["gate_wait"] in (WAIT_OBJECT_0, WAIT_ABANDONED)
        assert not observed["job_empty_under_gate"] and wait_handle(child, 0) == WAIT_TIMEOUT
        assert growth["after"] > growth["before"]
        return dict(counterexample="gate acquired while writer survives", probe=observed, writes=growth)

    def kill_on_close(self, folder):
        owner = self.start(folder, "tree")
        child = self.writer_handle(folder, "child")
        grandchild = self.writer_handle(folder, "grandchild")
        self.crash(owner)
        assert wait_handle(child, 5000) == 0 and wait_handle(grandchild, 5000) == 0
        observed = self.probe(folder)
        assert not observed["job_empty_under_gate"]
        return dict(child_handles_signaled=True, probe=observed,
                    note="absent named job is conservatively blocked, not a stop certificate")

    def retained_and_race(self, folder):
        owner = self.start(folder, "tree")
        child = self.writer_handle(folder, "child")
        grandchild = self.writer_handle(folder, "grandchild")
        job = self.keep_job(folder)
        self.keep_gate_name(folder)
        self.crash(owner)
        growth = self.growing(folder, "child")
        blocked = self.probe(folder)
        assert blocked["reason"] == "BLOCKED_ACTIVE_JOB" and growth["after"] > growth["before"]
        stopped = self.probe(folder, "stopped.json", stop=True, hold=True)
        assert stopped["job_empty_under_gate"] and accounting(job)["active"] == 0
        immediate_waits = [wait_handle(child, 0), wait_handle(grandchild, 0)]
        # 首轮真实观察到 ActiveProcesses=0 早于已知进程句柄 signal；不能把计数当完整停止证明。
        # 此处增加真实句柄 join；这仍不能证明未被该 Job 包含的所有写者停止。
        assert wait_handle(child, 3000) == 0 and wait_handle(grandchild, 3000) == 0
        rivals = [self.probe(folder, "rival-" + str(i) + ".json") for i in range(4)]
        assert all(value["reason"] == "BLOCKED_GATE" for value in rivals)
        (folder / "release-contender").touch()
        return dict(writes_after_owner_crash=growth, blocked=blocked, stopped=stopped,
                    immediate_known_process_waits=immediate_waits, known_writer_handles_joined=True,
                    rivals_blocked=len(rivals), note="keeper is explicit harness fault injection, not a production service")

    def boundary_crash(self, folder, mode):
        owner = self.start(folder, mode)
        if mode == "suspended":
            pid = load(folder / "ready.json")["root_pid"]
            handle = checked(open_process(SYNCHRONIZE | QUERY | TERMINATE, False, pid), "open owned suspended process")
            self.handles.append(handle)
        self.crash(owner)
        if mode == "suspended":
            assert wait_handle(handle, 5000) == 0
        assert not (folder / "suspended.writes").exists()
        observed = self.probe(folder)
        assert not observed["job_empty_under_gate"]
        return dict(probe=observed, no_writer_side_effect=True)

    def breakaway(self, folder):
        owner = self.start(folder, "breakaway")
        observed = load(folder / "breakaway.json")
        assert not observed["created"] and observed["error"] == 5
        assert not (folder / "escaped.pid.json").exists()
        (folder / "finish-owner").touch()
        assert owner.wait(timeout=5) == 0
        return dict(native_breakaway=observed, no_writer_side_effect=True)

    def broker_negative(self, folder):
        owner = self.start(folder, "broker")
        escaped = self.writer_handle(folder, "brokered")
        job = self.keep_job(folder)
        member = W.BOOL()
        checked(is_in_job(escaped, job, C.byref(member)), "query actual brokered job membership")
        assert not member.value
        self.crash(owner)
        stopped = self.probe(folder, "job-zero.json", stop=True)
        assert stopped["job_empty_under_gate"] and accounting(job)["active"] == 0
        assert wait_handle(escaped, 0) == WAIT_TIMEOUT
        growth = self.growing(folder, "brokered")
        assert growth["after"] > growth["before"]
        return dict(counterexample="JOB_ZERO does not prove all possible writers stopped", in_managed_job=False,
                    broker=load_bom(folder / "broker-result.json"), job_probe=stopped, writes_after_job_zero=growth,
                    verdict="NO-GO")

    def invalid_job(self, folder):
        try:
            handle, pid = launch_native([PYTHON, SCRIPT, "child", str(folder), "invalid"], folder, job=-1)
        except OSError as error:
            assert not (folder / "invalid.pid.json").exists()
            return dict(launch_rejected=True, error=error.errno, no_writer_side_effect=True)
        self.handles.append(handle)
        raise AssertionError("invalid job unexpectedly accepted pid=" + str(pid))

    def legacy(self, folder):
        missing = self.probe(folder)
        assert missing["reason"] == "BLOCKED_LEGACY"
        save(folder / "ownership.json", {"protocol": 0, "owner_pid_diagnostic_only": 999999})
        old = self.probe(folder, "old.json")
        assert old["reason"] == "BLOCKED_UNPROVEN_PROTOCOL"
        return dict(missing=missing, old=old)

    def stale(self, folder):
        owner = self.start(folder, "tree")
        self.writer_handle(folder, "child")
        self.writer_handle(folder, "grandchild")
        self.keep_job(folder)
        old_op = load(folder / "ownership.json")["operation"]
        self.crash(owner)
        checked_stop = self.probe(folder, "old-zero.json", stop=True)
        assert checked_stop["job_empty_under_gate"]
        # 停止观测后释放锁，第二实例启动真正的新 Writer，模拟 check/use 间隙。
        (folder / "ready.json").unlink()
        replacement = self.start(folder, "writer")
        self.writer_handle(folder, "writer")
        self.keep_job(folder)
        new_op = load(folder / "ownership.json")["operation"]
        assert new_op != old_op
        busy = self.probe(folder, "busy.json", expected=old_op)
        assert busy["reason"] == "BLOCKED_GATE"
        self.crash(replacement)
        result = self.probe(folder, "stale.json", expected=old_op)
        assert result["reason"] == "BLOCKED_STALE_OPERATION" and not result["job_empty_under_gate"]
        growth = self.growing(folder, "writer")
        assert growth["after"] > growth["before"]
        return dict(old_zero=checked_stop, new_operation=new_op, old_operation=old_op,
                    busy=busy, probe=result, replacement_writes=growth)


def load_bom(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def run(parent):
    root = Path(tempfile.mkdtemp(prefix="delveforge-m5f1-", dir=parent)).resolve()
    save(root / "spike-root.json", {"purpose": "m5-f1-isolated-fixture", "id": uuid.uuid4().hex})
    print("RESULT_ROOT=" + str(root), flush=True)
    experiments = Experiments(root)
    cases = [("00-natural-worker-exit", experiments.natural_exit), ("01-normal", experiments.normal),
             ("02-mutex-only-negative", experiments.mutex_negative),
             ("03-kill-on-last-close", experiments.kill_on_close),
             ("04-retained-handle-takeover", experiments.retained_and_race),
             ("05-before-launch-crash", lambda f: experiments.boundary_crash(f, "before-launch")),
             ("06-atomic-suspended-crash", lambda f: experiments.boundary_crash(f, "suspended")),
             ("07-breakaway-denied", experiments.breakaway), ("08-invalid-job-fails-closed", experiments.invalid_job),
             ("09-legacy-blocked", experiments.legacy), ("10-stale-operation-blocked", experiments.stale)]
    for number in range(3):
        cases.append(("11-race-repeat-" + str(number), experiments.retained_and_race))
    cases.append(("12-broker-escape-negative", experiments.broker_negative))
    for name, function in cases:
        experiments.scenario(name, function)
    result = dict(platform=sys.getwindowsversion()._asdict() if hasattr(sys.getwindowsversion(), "_asdict")
                  else str(sys.getwindowsversion()), python=sys.version, executable=PYTHON,
                  source_sha256=hashlib.sha256(Path(SCRIPT).read_bytes()).hexdigest(),
                  run_root=str(root), scenario_count=len(experiments.results),
                  scenarios_passed=sum(value["passed"] for value in experiments.results),
                  scenarios=experiments.results,
                  gate="NO-GO", scope="Job Object alone is not a sandbox or Recovery write qualification")
    save(root / "results.json", result)
    print(json.dumps({k: v for k, v in result.items() if k != "scenarios"}, ensure_ascii=False), flush=True)
    # 2 表示实验成功复现 NO-GO 边界；1 表示实验本身失败，0 保留给将来另行验证的 GO。
    return 2 if all(value["passed"] for value in experiments.results) else 1


def require_fixture(folder):
    root = folder.parent
    if (not root.name.startswith("delveforge-m5f1-") or folder.is_symlink()
            or folder.resolve() != folder or not (root / "spike-root.json").is_file()
            or load(root / "spike-root.json").get("purpose") != "m5-f1-isolated-fixture"):
        raise RuntimeError("refusing a directory outside this spike's freshly allocated fixtures")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    command = sub.add_parser("run")
    command.add_argument("--output-parent", type=Path, default=Path(tempfile.gettempdir()))
    for action in ("backend", "child"):
        command = sub.add_parser(action)
        command.add_argument("folder", type=Path)
        command.add_argument("mode")
    command = sub.add_parser("contender")
    command.add_argument("folder", type=Path)
    command.add_argument("output")
    command.add_argument("--stop", action="store_true")
    command.add_argument("--hold", action="store_true")
    command.add_argument("--expected")
    args = parser.parse_args()
    if args.action == "run":
        raise SystemExit(run(args.output_parent))
    require_fixture(args.folder)
    try:
        if args.action == "backend":
            backend(args.folder, args.mode)
        elif args.action == "child":
            worker(args.folder, args.mode)
        else:
            contender(args.folder, args.output, args.stop, args.hold, args.expected)
    except BaseException:
        save(args.folder / (args.action + "-error-" + str(os.getpid()) + ".json"),
             {"error": traceback.format_exc()})
        raise
