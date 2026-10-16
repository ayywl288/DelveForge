"""Bounded debugger of only its own suspended child; loader flags are child-memory-only."""
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

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from token_probe import P


class EXCEPTION_RECORD(C.Structure):
    _fields_ = [('code', W.DWORD), ('flags', W.DWORD), ('record', C.c_void_p),
                ('address', C.c_void_p), ('parameter_count', W.DWORD), ('parameters', C.c_size_t * 15)]


class EXCEPTION_INFO(C.Structure):
    _fields_ = [('record', EXCEPTION_RECORD), ('first_chance', W.DWORD)]


class CREATE_INFO(C.Structure):
    _fields_ = [('file', W.HANDLE), ('process', W.HANDLE), ('thread', W.HANDLE), ('base', C.c_void_p),
                ('offset', W.DWORD), ('size', W.DWORD), ('tls', C.c_void_p), ('start', C.c_void_p),
                ('name', C.c_void_p), ('unicode', W.WORD)]


class LOAD_INFO(C.Structure):
    _fields_ = [('file', W.HANDLE), ('base', C.c_void_p), ('offset', W.DWORD), ('size', W.DWORD),
                ('name', C.c_void_p), ('unicode', W.WORD)]


class STRING_INFO(C.Structure):
    _fields_ = [('address', C.c_void_p), ('unicode', W.WORD), ('length', W.WORD)]


class EVENT_UNION(C.Union):
    _fields_ = [('exception', EXCEPTION_INFO), ('create', CREATE_INFO), ('dll', LOAD_INFO),
                ('text', STRING_INFO), ('exit', W.DWORD)]


class DEBUG_EVENT(C.Structure):
    _fields_ = [('code', W.DWORD), ('pid', W.DWORD), ('tid', W.DWORD), ('data', EVENT_UNION)]


class BASIC_INFO(C.Structure):
    _fields_ = [('reserved', C.c_void_p), ('peb', C.c_void_p), ('more', C.c_void_p * 2),
                ('pid', C.c_size_t), ('last', C.c_void_p)]


wait_event = P.F.api('WaitForDebugEvent', W.BOOL, C.POINTER(DEBUG_EVENT), W.DWORD)
continue_event = P.F.api('ContinueDebugEvent', W.BOOL, W.DWORD, W.DWORD, W.DWORD)
read_memory = P.F.api('ReadProcessMemory', W.BOOL, W.HANDLE, C.c_void_p, C.c_void_p,
                     C.c_size_t, C.POINTER(C.c_size_t))
write_memory = P.F.api('WriteProcessMemory', W.BOOL, W.HANDLE, C.c_void_p, C.c_void_p,
                      C.c_size_t, C.POINTER(C.c_size_t))
resume_thread = P.F.api('ResumeThread', W.DWORD, W.HANDLE)
ntquery = C.WinDLL('ntdll').NtQueryInformationProcess
ntquery.restype, ntquery.argtypes = W.LONG, [W.HANDLE, W.ULONG, C.c_void_p, W.ULONG, C.c_void_p]


def trace(folder, restricted):
    P.configure(folder)
    marker = folder / 'staging' / 'entry.json'
    argv = [P.F.PYTHON, '-I', '-S', '-B', '-c', 'from pathlib import Path; Path(' + repr(str(marker)) + ').write_text("entered")']
    info, startup = P.F.PROCESS_INFORMATION(), P.F.STARTUPINFO()
    startup.cb = C.sizeof(startup)
    command = C.create_unicode_buffer(subprocess.list2cmdline(argv))
    token, events, process = None, [], None
    try:
        if restricted:
            token = P.make_restricted()
            create = P.create_as_user
            P.F.checked(create(token, argv[0], command, None, None, False, 0x08000006, None,
                               str(folder), C.byref(startup), C.byref(info)), 'create owned debug child')
        else:
            P.F.checked(P.F.create_process(argv[0], command, None, None, False, 0x08000006, None,
                                           str(folder), C.byref(startup), C.byref(info)), 'create debug control')
        process = info.hProcess
        basic, count, flags = BASIC_INFO(), C.c_size_t(), W.DWORD()
        P.F.checked(ntquery(process, 0, C.byref(basic), C.sizeof(basic), None) == 0, 'own child PEB')
        if C.sizeof(C.c_void_p) != 8:
            raise RuntimeError('diagnostic only supports the tested x64 PEB layout')
        # 只改本任务自建且 suspended 的 x64 child；不设置全局 GFlags 或注册表。
        address = basic.peb + 0xBC
        P.F.checked(read_memory(process, address, C.byref(flags), 4, C.byref(count)), 'own child NtGlobalFlag')
        flags.value |= 2
        P.F.checked(write_memory(process, address, C.byref(flags), 4, C.byref(count)), 'enable own child loader snaps')
        child_identity = P.identity(process)
        P.F.checked(resume_thread(info.hThread) != 0xFFFFFFFF, 'resume owned debug child')
        exited, limit = False, time.monotonic() + 15
        while time.monotonic() < limit and not exited:
            event = DEBUG_EVENT()
            if not wait_event(C.byref(event), 200):
                if C.get_last_error() == 121:
                    continue
                raise OSError(C.get_last_error(), 'wait own debug event')
            if event.pid != info.dwProcessId:
                raise RuntimeError('debug event outside owned child')
            record, handled = dict(code=event.code), 0x10002
            if event.code in (3, 6):
                handle = event.data.create.file if event.code == 3 else event.data.dll.file
                if handle:
                    try:
                        path = C.create_unicode_buffer(32768)
                        if P.final_path(handle, path, len(path), 0):
                            record['image'] = path.value
                    finally:
                        P.F.close_handle(handle)
            elif event.code == 8:
                value = event.data.text
                count_bytes = min(int(value.length) * (2 if value.unicode else 1), 65536)
                data, copied = C.create_string_buffer(count_bytes), C.c_size_t()
                if read_memory(process, value.address, data, count_bytes, C.byref(copied)):
                    record['text'] = data.raw[:copied.value].decode('utf-16-le' if value.unicode else 'utf-8', errors='replace').rstrip('\x00')
            elif event.code == 1:
                record.update(exception=hex(event.data.exception.record.code),
                              first_chance=event.data.exception.first_chance)
                if event.data.exception.record.code not in (0x80000003, 0x80000004):
                    handled = 0x80010001
            elif event.code == 5:
                record['exit'] = hex(event.data.exit)
                exited = True
            events.append(record)
            P.F.checked(continue_event(event.pid, event.tid, handled), 'continue owned debug event')
        if not exited:
            raise TimeoutError('bounded initialization trace expired')
        P.F.checked(P.F.wait_handle(process, 3000) == 0, 'debug child exit observed')
        result = dict(restricted=restricted, pid=info.dwProcessId, child_identity=child_identity,
                      user_code_reached=marker.exists(), exit_hex=hex(P.native_exit(process)), events=events)
        P.F.save(folder / 'trace.json', result)
        (folder / 'loader.log').write_text('\n'.join(e.get('text', e.get('image', str(e))) for e in events), encoding='utf-8')
        return {k:v for k,v in result.items() if k != 'events'}
    finally:
        if process:
            if P.F.wait_handle(process, 0) != 0:
                P.F.terminate_process(process, 99)
            P.F.close_handle(info.hThread)
            P.F.close_handle(process)
        if token:
            P.F.close_handle(token)


def main(parent):
    parent = parent.resolve(strict=True)
    if parent.drive.upper() != 'E:':
        raise RuntimeError('E-drive task output parent required')
    root = Path(tempfile.mkdtemp(prefix='loader-trace-', dir=parent))
    os.environ.update(TEMP=str(root), TMP=str(root), PYTHONDONTWRITEBYTECODE='1',
                      PSModuleAnalysisCachePath=str(root / 'powershell-analysis-cache'))
    P.F.save(root / 'spike-root.json', dict(purpose='m5-f1b-isolated-fixture'))
    results = []
    print('RESULT_ROOT=' + str(root), flush=True)
    for name, restricted in (('control', False), ('restricted', True)):
        folder = root / name
        folder.mkdir()
        result = trace(folder, restricted)
        results.append(result)
        print(json.dumps(result), flush=True)
    P.F.save(root / 'results.json', dict(results=results, root=str(root), source_files=P.source_facts(), source_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()))
    return 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-parent', type=Path, required=True)
    raise SystemExit(main(parser.parse_args().output_parent))
