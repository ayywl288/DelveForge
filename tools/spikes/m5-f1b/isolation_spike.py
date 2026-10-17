"""Windows isolation experiment; no accounts, existing ACLs or VM settings changed.

Run from the lab worktree: python tools/spikes/m5-f1b/isolation_spike.py run --output-parent <task-owned E directory>
Uses the checked-in F1 native-process fixture helper, not production adapters.
Exit 2 = completed NO-GO evidence, 1 = experiment failed. Never authorizes Recovery.
"""
import argparse
import ctypes as C
from ctypes import wintypes as W
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import traceback
import uuid

if os.name != "nt" or not __debug__:
    raise SystemExit("Requires real Windows and assertions enabled; no simulated fallback")

sys.dont_write_bytecode = True
SCRIPT = str(Path(__file__).resolve())
DEFAULT_HELPER = Path(SCRIPT).parents[1] / "m5-f1" / "ownership_spike.py"
helper_parser = argparse.ArgumentParser(add_help=False)
helper_parser.add_argument("--helper", type=Path, default=DEFAULT_HELPER)
helper_args, remaining = helper_parser.parse_known_args()
HELPER = str(helper_args.helper.resolve())
spec = importlib.util.spec_from_file_location("m5f1_native_fixture", HELPER)
F = importlib.util.module_from_spec(spec)
spec.loader.exec_module(F)
A = C.WinDLL("advapi32", use_last_error=True)


def adv(name, restype, *args):
    fn = getattr(A, name)
    fn.restype, fn.argtypes = restype, args
    return fn


class SID_ATTR(C.Structure):
    _fields_ = [("Sid", C.c_void_p), ("Attributes", W.DWORD)]


class FILE_INFO(C.Structure):
    _fields_ = [("attributes", W.DWORD), ("creation", W.FILETIME), ("access", W.FILETIME),
                ("write", W.FILETIME), ("volume", W.DWORD), ("size_high", W.DWORD),
                ("size_low", W.DWORD), ("links", W.DWORD), ("index_high", W.DWORD), ("index_low", W.DWORD)]


open_token = adv("OpenProcessToken", W.BOOL, W.HANDLE, W.DWORD, C.POINTER(W.HANDLE))
get_token = adv("GetTokenInformation", W.BOOL, W.HANDLE, C.c_int, C.c_void_p, W.DWORD, C.POINTER(W.DWORD))
is_restricted = adv("IsTokenRestricted", W.BOOL, W.HANDLE)
restrict_token = adv("CreateRestrictedToken", W.BOOL, W.HANDLE, W.DWORD, W.DWORD, C.c_void_p,
                     W.DWORD, C.c_void_p, W.DWORD, C.POINTER(SID_ATTR), C.POINTER(W.HANDLE))
string_sid = adv("ConvertStringSidToSidW", W.BOOL, W.LPCWSTR, C.POINTER(C.c_void_p))
sid_string = adv("ConvertSidToStringSidW", W.BOOL, C.c_void_p, C.POINTER(C.c_void_p))
convert_sd = adv("ConvertStringSecurityDescriptorToSecurityDescriptorW", W.BOOL,
                 W.LPCWSTR, W.DWORD, C.POINTER(C.c_void_p), C.POINTER(W.DWORD))
set_security = adv("SetFileSecurityW", W.BOOL, W.LPCWSTR, W.DWORD, C.c_void_p)
get_security = adv("GetFileSecurityW", W.BOOL, W.LPCWSTR, W.DWORD, C.c_void_p,
                   W.DWORD, C.POINTER(W.DWORD))
create_as_user = adv("CreateProcessAsUserW", W.BOOL, W.HANDLE, W.LPCWSTR, W.LPWSTR, C.c_void_p,
                     C.c_void_p, W.BOOL, W.DWORD, C.c_void_p, W.LPCWSTR, C.c_void_p,
                     C.POINTER(F.PROCESS_INFORMATION))
current_process = F.api("GetCurrentProcess", W.HANDLE)
get_exit_code = F.api("GetExitCodeProcess", W.BOOL, W.HANDLE, C.POINTER(W.DWORD))
local_free = F.api("LocalFree", W.HANDLE, W.HANDLE)
create_file = F.api("CreateFileW", W.HANDLE, W.LPCWSTR, W.DWORD, W.DWORD, C.c_void_p,
                    W.DWORD, W.DWORD, W.HANDLE)
write_file = F.api("WriteFile", W.BOOL, W.HANDLE, C.c_void_p, W.DWORD, C.POINTER(W.DWORD), C.c_void_p)
read_file = F.api("ReadFile", W.BOOL, W.HANDLE, C.c_void_p, W.DWORD, C.POINTER(W.DWORD), C.c_void_p)
file_info = F.api("GetFileInformationByHandle", W.BOOL, W.HANDLE, C.POINTER(FILE_INFO))
final_path = F.api("GetFinalPathNameByHandleW", W.DWORD, W.HANDLE, W.LPWSTR, W.DWORD, W.DWORD)
symbolic_link = F.api("CreateSymbolicLinkW", W.BOOLEAN, W.LPCWSTR, W.LPCWSTR, W.DWORD)
INVALID = C.c_void_p(-1).value


def args_for(action, folder, *rest):
    return [F.PYTHON, "-B", SCRIPT, "--helper", HELPER, action, str(folder), *rest]


def source_facts():
    files = [Path(SCRIPT).parent / name for name in ("isolation_spike.py", "token_probe.py",
                                                    "private_desktop.py", "loader_probe.py")]
    files.append(Path(HELPER))
    return {path.name: dict(raw_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                           lf_sha256=hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest())
            for path in files}


def native_exit(handle):
    code = W.DWORD()
    F.checked(get_exit_code(handle, C.byref(code)), "actual native process exit")
    return code.value


def sid_text(pointer):
    value = C.c_void_p()
    F.checked(sid_string(pointer, C.byref(value)), "ConvertSidToStringSid")
    try:
        return C.wstring_at(value)
    finally:
        local_free(value)


def token_buffer(token, kind):
    needed = W.DWORD()
    get_token(token, kind, None, 0, C.byref(needed))
    buffer = C.create_string_buffer(needed.value)
    F.checked(get_token(token, kind, buffer, needed, C.byref(needed)), "GetTokenInformation")
    return buffer


def identity(process=None):
    token = W.HANDLE()
    F.checked(open_token(process or current_process(), 0x8, C.byref(token)), "query actual process token")
    try:
        user = token_buffer(token, 1)
        integrity = token_buffer(token, 25)
        restricted = token_buffer(token, 11)
        count = W.DWORD.from_buffer(restricted).value
        offset = (C.sizeof(W.DWORD) + C.alignment(SID_ATTR) - 1) & ~(C.alignment(SID_ATTR) - 1)
        entries = [SID_ATTR.from_buffer(restricted, offset + i * C.sizeof(SID_ATTR)) for i in range(count)]
        return dict(pid=os.getpid() if process is None else None,
                    user_sid=sid_text(SID_ATTR.from_buffer(user).Sid), restricted=bool(is_restricted(token)),
                    restricting_sids=[sid_text(entry.Sid) for entry in entries],
                    integrity_sid=sid_text(SID_ATTR.from_buffer(integrity).Sid),
                    privilege_count=W.DWORD.from_buffer(token_buffer(token, 3)).value)
    finally:
        F.close_handle(token)


def make_restricted(include_logon=False):
    original, result, sid = W.HANDLE(), W.HANDLE(), C.c_void_p()
    F.checked(open_token(current_process(), 0x8b, C.byref(original)), "open own primary token")
    try:
        F.checked(string_sid("S-1-5-12", C.byref(sid)), "Restricted Code SID")
        entries = [SID_ATTR(sid, 0)]
        if include_logon:
            groups = token_buffer(original, 2)
            count = W.DWORD.from_buffer(groups).value
            offset = (C.sizeof(W.DWORD) + C.alignment(SID_ATTR) - 1) & ~(C.alignment(SID_ATTR) - 1)
            for index in range(count):
                group = SID_ATTR.from_buffer(groups, offset + index * C.sizeof(SID_ATTR))
                if group.Attributes & 0xC0000000 == 0xC0000000:
                    entries.append(SID_ATTR(group.Sid, 0))
            if len(entries) != 2:
                raise RuntimeError('expected one real logon SID')
        restricted_sids = (SID_ATTR * len(entries))(*entries)
        # 仅减少当前 Token 权限；不是创建独立账户，也不启用任何权限。
        F.checked(restrict_token(original, 0x9, 0, None, 0, None, len(entries), restricted_sids, C.byref(result)),
                  "CreateRestrictedToken(DISABLE_MAX_PRIVILEGE | WRITE_RESTRICTED)")
        return result
    finally:
        if sid:
            local_free(sid)
        F.close_handle(original)


def launch_restricted(folder, mode, job):
    token = make_restricted()
    size, startup, info = F.SIZE(), F.STARTUPINFOEX(), F.PROCESS_INFORMATION()
    F.initialize_attrs(None, 1, 0, C.byref(size))
    attrs = C.create_string_buffer(size.value)
    initialized = False
    try:
        F.checked(F.initialize_attrs(attrs, 1, 0, C.byref(size)), "initialize attributes")
        initialized = True
        startup.StartupInfo.cb = C.sizeof(startup)
        startup.lpAttributeList = C.cast(attrs, C.c_void_p)
        handles = (W.HANDLE * 1)(job)
        F.checked(F.update_attrs(attrs, 0, 0x2000D, handles, C.sizeof(handles), None, None), "atomic JOB_LIST")
        command = C.create_unicode_buffer(subprocess.list2cmdline(args_for("actor", folder, mode)))
        F.checked(create_as_user(token, F.PYTHON, command, None, None, False, 0x08080000, None,
                                 str(folder), C.byref(startup), C.byref(info)), "restricted process creation")
        F.close_handle(info.hThread)
        return info.hProcess, info.dwProcessId
    finally:
        if initialized:
            F.delete_attrs(attrs)
        F.close_handle(token)


def dacl(path, sddl):
    descriptor = C.c_void_p()
    F.checked(convert_sd(sddl, 1, C.byref(descriptor), None), "parse task-only security descriptor")
    try:
        F.checked(set_security(str(path), 0x80000004, descriptor), "set task-only protected DACL")
    finally:
        local_free(descriptor)


def descriptor(path):
    needed = W.DWORD()
    get_security(str(path), 4, None, 0, C.byref(needed))
    buffer = C.create_string_buffer(needed.value)
    F.checked(get_security(str(path), 4, buffer, needed, C.byref(needed)), "save task-only DACL")
    return buffer


def open_append(path, desired=4):
    handle = create_file(str(path), desired, 7, None, 3, 0, None)
    if handle == INVALID:
        raise OSError(C.get_last_error(), "CreateFile on isolated fixture")
    return handle


def append_handle(handle):
    count = W.DWORD()
    F.checked(write_file(handle, C.c_char_p(b"x"), 1, C.byref(count), None), "write via existing fixture handle")
    assert count.value == 1


def try_append(path):
    handle = None
    try:
        handle = open_append(path)
        append_handle(handle)
        return dict(written=True)
    except OSError as error:
        return dict(written=False, error=error.errno)
    finally:
        if handle:
            F.close_handle(handle)


def require_fixture(folder):
    assert folder.drive.upper() == "E:" and folder.resolve() == folder and not folder.is_symlink()
    marker = folder.parent / "spike-root.json"
    if not marker.is_file() or F.load(marker).get("purpose") != "m5-f1b-isolated-fixture":
        raise RuntimeError("refusing any non-fixture path")


def configure(folder):
    user = identity()["user_sid"]
    private = "D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;" + user + ")"
    stage = private + "(A;OICI;0x1301bf;;;RC)"
    dacl(folder, private)
    for name in ("protected", "staging", "control"):
        (folder / name).mkdir()
        dacl(folder / name, stage if name == "staging" else private)
    for name in ("protected.txt", "dac-probe.txt"):
        (folder / "protected" / name).write_bytes(b"trusted baseline\n")
    paths = [str(folder / "protected" / "protected.txt"),
             str(folder / "staging" / ".." / "protected" / "protected.txt"),
             "\\\\?\\" + str(folder / "protected" / "protected.txt"),
             str(folder / "protected" / "protected.txt").swapcase()]
    os.link(folder / "protected" / "protected.txt", folder / "staging" / "hard-alias.txt")
    paths.append(str(folder / "staging" / "hard-alias.txt"))
    ps = str(Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe")
    quote = lambda value: "'" + str(value).replace("'", "''") + "'"
    junction = folder / "staging" / "junction"
    command = ("$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path " + quote(junction)
               + " -Target " + quote(folder / "protected") + " | Out-Null")
    result = subprocess.run([ps, "-NoProfile", "-NonInteractive", "-Command", command],
                            capture_output=True, timeout=10, creationflags=0x08000000)
    if result.returncode != 0:
        raise RuntimeError("task-owned junction creation failed")
    paths.append(str(junction / "protected.txt"))
    link = folder / "staging" / "symbolic.txt"
    ok = bool(symbolic_link(str(link), str(folder / "protected" / "protected.txt"), 2))
    symlink = dict(created=ok, error=0 if ok else C.get_last_error())
    if ok:
        paths.append(str(link))
    F.save(folder / "config.json", dict(user=user, private=private, stage=stage,
                                        paths=paths, symlink=symlink))
    return F.load(folder / "config.json")


def token_actor(folder, mode):
    stage = folder / "staging"
    facts = identity()
    F.save(stage / (mode + ".pid.json"), dict(pid=os.getpid(), identity=facts))
    if mode in ("managed", "broker-launcher"):
        config = F.load(folder / "config.json")
        outcomes = [dict(path=path, **try_append(path)) for path in config["paths"]]
        dac_result = {}
        try:
            grant = "D:P(A;;FA;;;SY)(A;;FA;;;" + config["user"] + ")(A;;FW;;;RC)"
            dacl(folder / "protected" / "dac-probe.txt", grant)
            dac_result = dict(changed=True, after=try_append(folder / "protected" / "dac-probe.txt"))
        except OSError as error:
            dac_result = dict(changed=False, error=error.errno)
        F.save(stage / "managed-results.json", dict(identity=facts, paths=outcomes, dac=dac_result,
                                                    stage_write=try_append(stage / "allowed.txt")))
        # 与 F1 同一真实 WMI 代创建机制；固定命令只启动本原型的有界 writer。
        argv = args_for("actor", folder, config.get("broker_mode", "brokered"))
        quote = lambda value: "'" + str(value).replace("'", "''") + "'"
        cmd = ("$ErrorActionPreference='Stop'; try { $r=Invoke-CimMethod -ClassName Win32_Process "
               "-MethodName Create -Arguments @{CommandLine=" + quote(subprocess.list2cmdline(argv))
               + ";CurrentDirectory=" + quote(folder) + "}; @{ok=$true;returnValue=$r.ReturnValue;pid=$r.ProcessId} "
               "| ConvertTo-Json -Compress | Set-Content -Encoding utf8 -LiteralPath "
               + quote(stage / "broker-result.json")
               + " } catch { @{ok=$false;error=$_.Exception.GetType().FullName;hresult=$_.Exception.HResult} "
               "| ConvertTo-Json -Compress | Set-Content -Encoding utf8 -LiteralPath "
               + quote(stage / "broker-result.json") + " }")
        ps = str(Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe")
        result = subprocess.run([ps, "-NoProfile", "-NonInteractive", "-Command", cmd],
                                capture_output=True, timeout=12, creationflags=0x08000000)
        F.save(stage / "broker-client-exit.json", dict(exit_code=result.returncode))
        return
    target = stage / "payload.bin" if mode == "artifact-writer" else folder / "protected" / "protected.txt"
    hold = mode in ("held", "artifact-writer", "broker-held")
    handle = open_append(target) if hold else None
    F.save(stage / (mode + "-ready.json"), dict(identity=facts, held=hold))
    end, successes, failures = time.monotonic() + 30, 0, 0
    try:
        while time.monotonic() < end and not (folder / "stop-writers").exists():
            if hold:
                append_handle(handle)
                successes += 1
            else:
                outcome = try_append(target)
                successes += int(outcome["written"])
                failures += int(not outcome["written"])
            (stage / (mode + ".heartbeats")).open("ab").write(b"x")
            time.sleep(0.01)
    finally:
        if handle:
            F.close_handle(handle)
        F.save(stage / (mode + "-done.json"), dict(successes=successes, failures=failures))


def owner(folder, restricted, broker_control=False):
    job_name = F.names(folder) + ".job." + uuid.uuid4().hex
    job = F.new_job(job_name)
    process = None
    gate = F.checked(F.create_mutex(None, False, F.names(folder) + ".gate"), "create gate")
    acquired = F.wait_handle(gate, 3000)
    F.checked(acquired in (0, 128), "acquire owner gate")
    try:
        operation = uuid.uuid4().hex
        F.save(folder / "ownership.json", dict(protocol=1, operation=operation, job=job_name, owner=identity()))
        if restricted:
            process, pid = launch_restricted(folder, "managed", job)
        else:
            process, pid = F.launch_native(args_for("actor", folder, "broker-launcher" if broker_control else "held"),
                                             folder, job=job)
        F.save(folder / "root-process.json", dict(pid=pid))
        phase_file = folder / "staging" / ("managed-results.json" if restricted or broker_control else "held-ready.json")
        F.until(lambda: phase_file.exists() or F.wait_handle(process, 0) == 0)
        if not phase_file.exists():
            raise RuntimeError("native process exited before target stage: " + hex(native_exit(process)))
        if restricted or broker_control:
            F.until(lambda: (folder / "staging" / "broker-result.json").exists(), 15)
        F.save(folder / "ready.json", dict(owner=identity(), job=F.accounting(job)))
        F.until(lambda: (folder / "finish-owner").exists(), 30)
    finally:
        F.terminate_job(job, 97)
        F.until(lambda: F.accounting(job)["active"] == 0)
        F.save(folder / "owner-job-zero.json", F.accounting(job))
        if process:
            F.close_handle(process)
        F.close_handle(job)
        F.release_mutex(gate)
        F.close_handle(gate)


def contention(folder, filename):
    gate = F.checked(F.create_mutex(None, False, F.names(folder) + ".gate"), "contender gate")
    value = F.wait_handle(gate, 0)
    try:
        F.save(folder / filename, dict(wait=value, acquired=value in (0, 128)))
    finally:
        if value in (0, 128):
            F.release_mutex(gate)
        F.close_handle(gate)


def snapshot(stage, relative):
    # 仅演示拒绝几类输入和读取一份字节快照；不是生产导入协议或可信验证结果。
    if (relative != "payload.bin" or Path(relative).name != relative or ":" in relative):
        return dict(accepted=False, reason="PATH_REJECTED")
    handle = create_file(str(stage / relative), 0x80000000, 1, None, 3, 0x00200000, None)
    if handle == INVALID:
        return dict(accepted=False, reason="OPEN_REJECTED", error=C.get_last_error())
    try:
        info = FILE_INFO()
        F.checked(file_info(handle, C.byref(info)), "artifact file information")
        path = C.create_unicode_buffer(32768)
        F.checked(final_path(handle, path, len(path), 0), "artifact final path")
        actual = path.value.removeprefix("\\\\?\\")
        if (info.attributes & (0x400 | 0x10) or info.links != 1
                or Path(actual).parent != stage.resolve() or info.size_high or info.size_low > 65536):
            return dict(accepted=False, reason="FILE_IDENTITY_REJECTED", links=info.links,
                        attributes=info.attributes, resolved_path=actual)
        buffer, count = C.create_string_buffer(65537), W.DWORD()
        F.checked(read_file(handle, buffer, 65537, C.byref(count), None), "bounded artifact read")
        if count.value > 65536:
            return dict(accepted=False, reason="SIZE_REJECTED")
        content = buffer.raw[:count.value]
        return dict(accepted=True, bytes=len(content), sha256=hashlib.sha256(content).hexdigest(),
                    verification_trusted=False)
    finally:
        F.close_handle(handle)


class Experiments(F.Experiments):
    def start_owner(self, folder, restricted=True, broker_control=False):
        mode = "broker-control" if broker_control else ("restricted" if restricted else "held")
        with (folder / "owner.log").open("wb") as out:
            process = subprocess.Popen(args_for("owner", folder, mode), stdout=out, stderr=subprocess.STDOUT,
                                       creationflags=0x08000000)
        self.processes.append(process)
        F.until(lambda: (folder / "ready.json").exists() or process.poll() is not None, 20)
        if not (folder / "ready.json").exists():
            raise RuntimeError((folder / "owner.log").read_text())
        return process

    def rival(self, folder, filename):
        result = subprocess.run(args_for("contender", folder, filename), timeout=5, capture_output=True,
                                creationflags=0x08000000)
        assert result.returncode == 0
        return F.load(folder / filename)

    def restricted_and_broker(self, folder):
        config = configure(folder)
        (folder / "staging" / "allowed.txt").write_bytes(b"allowed\n")
        owner_process = self.start_owner(folder)
        managed = F.load(folder / "staging" / "managed-results.json")
        assert managed["identity"]["restricted"] and "S-1-5-12" in managed["identity"]["restricting_sids"]
        assert managed["stage_write"]["written"]
        assert all(not value["written"] and value["error"] == 5 for value in managed["paths"])
        broker = json.loads((folder / "staging" / "broker-result.json").read_text(encoding="utf-8-sig"))
        busy = self.rival(folder, "busy.json")
        assert not busy["acquired"]
        output = dict(managed=managed, broker=broker, aliases=config["symlink"], rival=busy)
        if broker.get("ok") and broker.get("returnValue") == 0:
            writer = self.writer_handle(folder / "staging", "brokered")
            job = self.keep_job(folder)
            token = identity(writer)
            member = W.BOOL()
            F.checked(F.is_in_job(writer, job, C.byref(member)), "query specific managed-job membership")
            output.update(broker_identity=token, broker_in_managed_job=bool(member.value))
            assert token["user_sid"] == managed["identity"]["user_sid"]
            self.crash(owner_process)
            F.checked(F.terminate_job(job, 98), "terminate only task-owned job")
            F.until(lambda: F.accounting(job)["active"] == 0)
            target = folder / "protected" / "protected.txt"
            before = target.stat().st_size
            time.sleep(0.2)
            after = target.stat().st_size
            output.update(owner_crashed=True, protected_bytes_after_crash=dict(before=before, after=after),
                          job_after=F.accounting(job), broker_wait=F.wait_handle(writer, 0),
                          takeover_observation=self.rival(folder, "after-crash.json"))
            assert after > before and F.wait_handle(writer, 0) == 258
            output["counterexample"] = "WMI broker lost restricting SIDs and continued protected-file writes"
        else:
            (folder / "finish-owner").touch()
            assert owner_process.wait(timeout=5) == 0
            output["limitation"] = "broker creation denied; no broker writer token measured"
        return output

    def held_handle(self, folder):
        config = configure(folder)
        process = self.start_owner(folder, restricted=False)
        writer = self.writer_handle(folder / "staging", "held")
        target = folder / "protected" / "protected.txt"
        old = descriptor(target)
        try:
            # 只撤销这个新建 Fixture 文件的数据写权限；保留 host 的 WRITE_DAC 以还原。
            deny = "D:P(D;;0x116;;;" + config["user"] + ")(A;;FA;;;SY)(A;;FA;;;" + config["user"] + ")"
            dacl(target, deny)
            denied = try_append(target)
            before = target.stat().st_size
            time.sleep(0.2)
            after = target.stat().st_size
            assert not denied["written"] and denied["error"] == 5 and after > before
            rival = self.rival(folder, "before-crash.json")
            assert not rival["acquired"]
            self.crash(process)
            assert F.wait_handle(writer, 5000) == 0
            after_crash = self.rival(folder, "after-crash.json")
            assert after_crash["acquired"]
            return dict(new_open=denied, existing_handle_writes=dict(before=before, after=after),
                        writer_identity=F.load(folder / "staging" / "held.pid.json")["identity"],
                        known_writer_exited_after_owner_crash=True, rival_before=rival, rival_after=after_crash,
                        counterexample="ACL revocation does not revoke existing granted write handles")
        finally:
            F.checked(set_security(str(target), 0x80000004, old), "restore only fixture DACL")

    def staging_receive(self, folder, restricted=True):
        configure(folder)
        stage = folder / "staging"
        payload = stage / "payload.bin"
        payload.write_bytes(b'{"verificationPassed":true,"candidate":"untrusted"}')
        normal = snapshot(stage, "payload.bin")
        assert normal["accepted"] and not normal["verification_trusted"]
        rejected_paths = [snapshot(stage, name) for name in ("../protected/protected.txt", "C:\\outside", "payload.bin:ads")]
        assert all(not value["accepted"] for value in rejected_paths)
        payload.unlink()
        os.link(folder / "protected" / "protected.txt", payload)
        linked = snapshot(stage, "payload.bin")
        assert not linked["accepted"] and linked["reason"] == "FILE_IDENTITY_REJECTED"
        payload.unlink()
        link_ok = bool(symbolic_link(str(payload), str(folder / "protected" / "protected.txt"), 2))
        symlink = snapshot(stage, "payload.bin") if link_ok else dict(created=False, error=C.get_last_error())
        if link_ok:
            assert not symlink["accepted"]
            payload.unlink()
        payload.write_bytes(b"fresh\n")
        job = F.new_job(F.names(folder) + ".artifact." + uuid.uuid4().hex)
        self.jobs.append(job)
        if restricted:
            handle, pid = launch_restricted(folder, "artifact-writer", job)
        else:
            handle, pid = F.launch_native(args_for("actor", folder, "artifact-writer"), folder, job=job)
        self.handles.append(handle)
        F.until(lambda: (stage / "artifact-writer-ready.json").exists() or F.wait_handle(handle, 0) == 0)
        if not (stage / "artifact-writer-ready.json").exists():
            raise RuntimeError("native artifact writer exited before target stage: " + hex(native_exit(handle)))
        busy = snapshot(stage, "payload.bin")
        assert not busy["accepted"] and busy.get("error") == 32
        (folder / "stop-writers").touch()
        assert F.wait_handle(handle, 5000) == 0
        final = snapshot(stage, "payload.bin")
        assert final["accepted"] and not final["verification_trusted"]
        return dict(writer_restricted=restricted, normal_bytes_only=normal, path_rejections=rejected_paths, hardlink=linked,
                    symbolic=symlink, live_writer=busy, post_exit_bytes_only=final,
                    note="not a complete sandbox export or production acceptance protocol")


    def host_staging_receive(self, folder):
        # 此对照仅验证宿主接收原语，不冒充受限执行身份已运行。
        return self.staging_receive(folder, restricted=False)

    def broker_acl_counterexample(self, folder):
        config = configure(folder)
        (folder / "staging" / "allowed.txt").write_bytes(b"allowed\n")
        process = self.start_owner(folder, restricted=False, broker_control=True)
        managed = F.load(folder / "staging" / "managed-results.json")
        assert not managed["identity"]["restricted"]
        broker = json.loads((folder / "staging" / "broker-result.json").read_text(encoding="utf-8-sig"))
        assert broker.get("ok") and broker.get("returnValue") == 0, broker
        writer = self.writer_handle(folder / "staging", "brokered")
        assert F.load(folder / "staging" / "brokered.pid.json")["pid"] == broker["pid"]
        job = self.keep_job(folder)
        member = W.BOOL()
        F.checked(F.is_in_job(writer, job, C.byref(member)), "actual broker job membership")
        token = identity(writer)
        assert not member.value and not token["restricted"]
        assert token["user_sid"] == identity()["user_sid"]
        busy = self.rival(folder, "before-crash.json")
        assert not busy["acquired"]
        self.crash(process)
        F.checked(F.terminate_job(job, 98), "terminate only retained fixture job")
        F.until(lambda: F.accounting(job)["active"] == 0)
        target = folder / "protected" / "protected.txt"
        before = len(target.read_bytes())
        time.sleep(0.25)
        after = len(target.read_bytes())
        assert after > before and F.wait_handle(writer, 0) == 258
        takeover = self.rival(folder, "after-crash.json")
        assert takeover["acquired"]
        old = descriptor(target)
        try:
            deny = "D:P(D;;0x116;;;" + config["user"] + ")(A;;FA;;;SY)(A;;FA;;;" + config["user"] + ")"
            dacl(target, deny)
            denied = [dict(path=path, **try_append(path)) for path in config["paths"]]
            assert all(not value["written"] and value["error"] == 5 for value in denied)
            frozen = len(target.read_bytes())
            time.sleep(0.25)
            assert len(target.read_bytes()) == frozen and F.wait_handle(writer, 0) == 258
            F.checked(set_security(str(target), 0x80000004, old), "restore only fixture DACL")
            trusted_open = try_append(target)
            assert trusted_open["written"]
            restored = len(target.read_bytes())
            time.sleep(0.25)
            resumed = len(target.read_bytes())
            assert resumed > restored
            return dict(managed_identity=managed["identity"], broker_identity=token,
                        managed_paths=managed["paths"], broker=broker, broker_in_job=bool(member.value),
                        job_after=F.accounting(job), broker_wait=F.wait_handle(writer, 0),
                        after_owner_crash=dict(before=before, after=after),
                        competing_before=busy, competing_after=takeover,
                        revoked_aliases=denied, host_reopen=trusted_open,
                        after_restore=dict(before=restored, after=resumed), symbolic=config["symlink"],
                        recovery_eligible=False,
                        counterexample="Same-SID ACL cannot separate host from escaped broker writer; gate acquisition is not safety")
        finally:
            F.checked(set_security(str(target), 0x80000004, old), "restore task fixture DACL")


    def broker_held_counterexample(self, folder):
        config = configure(folder)
        config["broker_mode"] = "broker-held"
        F.save(folder / "config.json", config)
        (folder / "staging" / "allowed.txt").write_bytes(b"allowed\n")
        process = self.start_owner(folder, restricted=False, broker_control=True)
        broker = json.loads((folder / "staging" / "broker-result.json").read_text(encoding="utf-8-sig"))
        assert broker.get("ok") and broker.get("returnValue") == 0, broker
        writer = self.writer_handle(folder / "staging", "broker-held")
        assert F.load(folder / "staging" / "broker-held.pid.json")["pid"] == broker["pid"]
        job, member = self.keep_job(folder), W.BOOL()
        F.checked(F.is_in_job(writer, job, C.byref(member)), "held broker actual job membership")
        assert not member.value
        target = folder / "protected" / "protected.txt"
        old = descriptor(target)
        try:
            deny = "D:P(D;;0x116;;;" + config["user"] + ")(A;;FA;;;SY)(A;;FA;;;" + config["user"] + ")"
            dacl(target, deny)
            denied = try_append(target)
            assert not denied["written"] and denied["error"] == 5
            rival_before = self.rival(folder, "before-crash.json")
            assert not rival_before["acquired"]
            self.crash(process)
            F.checked(F.terminate_job(job, 98), "terminate only own retained job")
            F.until(lambda: F.accounting(job)["active"] == 0)
            before = len(target.read_bytes())
            time.sleep(0.25)
            after = len(target.read_bytes())
            assert after > before and F.wait_handle(writer, 0) == 258
            rival_after = self.rival(folder, "after-crash.json")
            assert rival_after["acquired"]
            return dict(broker=broker, broker_identity=identity(writer), broker_in_job=False,
                        denied_new_open=denied, after_crash_and_revocation=dict(before=before, after=after),
                        job_after=F.accounting(job), broker_wait=F.wait_handle(writer, 0),
                        rival_before=rival_before, rival_after=rival_after, recovery_eligible=False,
                        counterexample="Escaped pre-opened writer survives owner death, empty Job and ACL revocation")
        finally:
            F.checked(set_security(str(target), 0x80000004, old), "restore only held-writer fixture DACL")

    def staging_parent_alias(self, folder):
        configure(folder)
        target = folder / "protected"
        (target / "payload.bin").write_bytes(b"host-owned test bytes; not real user data")
        alias = folder / "receive-alias"
        quote = lambda value: "'" + str(value).replace("'", "''") + "'"
        command = "$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path " + quote(alias) + " -Target " + quote(target) + " | Out-Null"
        ps = str(Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe")
        outcome = subprocess.run([ps, "-NoProfile", "-NonInteractive", "-Command", command],
                                 timeout=10, capture_output=True, creationflags=0x08000000)
        assert outcome.returncode == 0
        received = snapshot(alias, "payload.bin")
        assert received["accepted"] and not received["verification_trusted"]
        return dict(raw_receiver=received, alias=str(alias), actual_parent=str(target),
                    receiver_safe_for_production=False,
                    counterexample="Leaf-file checks do not reject a junction in the staging parent")


def run(output_parent):
    output_parent = output_parent.resolve(strict=True)
    if output_parent.drive.upper() != "E:":
        raise RuntimeError("existing E-drive task output parent required")
    root = Path(tempfile.mkdtemp(prefix="delveforge-m5f1b-", dir=output_parent)).resolve()
    os.environ.update(TEMP=str(root), TMP=str(root), PYTHONDONTWRITEBYTECODE="1",
                      PSModuleAnalysisCachePath=str(root / "powershell-analysis-cache"))
    F.save(root / "spike-root.json", dict(purpose="m5-f1b-isolated-fixture", id=uuid.uuid4().hex))
    print("RESULT_ROOT=" + str(root), flush=True)
    tests = Experiments(root)
    for name, method in (("01-restricted-and-broker", tests.restricted_and_broker),
                         ("02-open-handle-revocation", tests.held_handle),
                         ("03-staging-byte-receive", tests.staging_receive),
                         ("04-host-staging-control", tests.host_staging_receive),
                         ("05-same-sid-broker-acl", tests.broker_acl_counterexample),
                         ("06-broker-preopened-handle", tests.broker_held_counterexample),
                         ("07-staging-parent-alias", tests.staging_parent_alias)):
        tests.scenario(name, method)
    result = dict(gate="NO-GO", host_identity=identity(), platform=str(sys.getwindowsversion()),
                  scenarios=tests.results, scenario_count=len(tests.results), source_files=source_facts(),
                  scenarios_passed=sum(value["passed"] for value in tests.results),
                  source_sha256=hashlib.sha256(Path(SCRIPT).read_bytes()).hexdigest(),
                  source_lf_sha256=hashlib.sha256(Path(SCRIPT).read_bytes().replace(b"\r\n", b"\n")).hexdigest(),
                  helper_sha256=hashlib.sha256(Path(HELPER).read_bytes().replace(b"\r\n", b"\n")).hexdigest(),
                  root=str(root), independent_account_tested=False,
                  sandbox_launcher_exists=(Path(os.environ["SystemRoot"]) / "System32/WindowsSandbox.exe").exists(),
                  note="restricted token shares host user SID; no Recovery capability is produced")
    F.save(root / "results.json", result)
    print(json.dumps({k: v for k, v in result.items() if k != "scenarios"}), flush=True)
    return 2 if all(value["passed"] for value in tests.results) else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    run_command = sub.add_parser("run")
    run_command.add_argument("--output-parent", type=Path, required=True)
    for name in ("actor", "owner"):
        command = sub.add_parser(name)
        command.add_argument("folder", type=Path)
        command.add_argument("mode")
    command = sub.add_parser("contender")
    command.add_argument("folder", type=Path)
    command.add_argument("filename")
    args = parser.parse_args(remaining)
    if args.action == "run":
        raise SystemExit(run(args.output_parent))
    require_fixture(args.folder)
    try:
        if args.action == "actor":
            token_actor(args.folder, args.mode)
        elif args.action == "owner":
            owner(args.folder, args.mode == "restricted", args.mode == "broker-control")
        else:
            contention(args.folder, args.filename)
    except BaseException:
        F.save(args.folder / "staging" / ("error-" + str(os.getpid()) + ".json"),
               dict(error=traceback.format_exc()))
        raise
