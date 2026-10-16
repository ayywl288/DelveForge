"""Real initialization contrast. New E-drive fixtures only; never updates existing ACLs."""
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

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('f1b', HERE / 'isolation_spike.py')
P = importlib.util.module_from_spec(spec)
saved_argv = sys.argv
sys.argv = [str(HERE / 'isolation_spike.py')]
try:
    spec.loader.exec_module(P)
finally:
    sys.argv = saved_argv
sys.path.insert(0, str(HERE))
from private_desktop import PrivateDesktop


def experiment(root, name, restricted, private, logon=False):
    folder = root / name
    folder.mkdir()
    P.configure(folder)
    marker = folder / 'staging' / 'python-entry.json'
    script = 'import json,os; from pathlib import Path; Path(' + repr(str(marker)) + ').write_text(json.dumps({"pid":os.getpid(),"phase":"python-user-code"}))'
    argv = [P.F.PYTHON, '-I', '-S', '-B', '-c', script]
    token = process = None
    boundary = None
    try:
        if private:
            boundary = PrivateDesktop(P, P.identity()['user_sid'])
        if restricted:
            token = P.make_restricted(include_logon=logon)
            startup = P.F.STARTUPINFO()
            startup.cb = C.sizeof(startup)
            startup.lpDesktop = boundary.path if boundary else None
            info = P.F.PROCESS_INFORMATION()
            command = C.create_unicode_buffer(subprocess.list2cmdline(argv))
            P.F.checked(P.create_as_user(token, argv[0], command, None, None, False, 0x08000000,
                                         None, str(folder), C.byref(startup), C.byref(info)), 'probe CreateProcessAsUser')
            P.F.close_handle(info.hThread)
            process, pid = info.hProcess, info.dwProcessId
        else:
            process, pid = P.F.launch_native(argv, folder)
        wait = P.F.wait_handle(process, 5000)
        if wait != 0:
            P.F.checked(P.F.terminate_process(process, 99), 'stop only owned initialization probe')
            P.F.checked(P.F.wait_handle(process, 3000) == 0, 'owned initialization probe exited')
        result = dict(name=name, created=True, pid=pid, wait=wait, exit_hex=hex(P.native_exit(process)),
                      user_code_reached=marker.exists(), desktop=boundary.path if boundary else 'inherited',
                      restricted=restricted, include_logon=logon)
        P.F.save(folder / 'result.json', result)
        return result
    finally:
        if process:
            P.F.close_handle(process)
        if token:
            P.F.close_handle(token)
        if boundary:
            boundary.close()


def run(parent):
    parent = parent.resolve(strict=True)
    if parent.drive.upper() != 'E:':
        raise RuntimeError('requires an existing task-owned E-drive output parent')
    root = Path(tempfile.mkdtemp(prefix='init-probe-', dir=parent))
    os.environ.update(TEMP=str(root), TMP=str(root), PYTHONDONTWRITEBYTECODE='1',
                      PSModuleAnalysisCachePath=str(root / 'powershell-analysis-cache'))
    P.F.save(root / 'spike-root.json', dict(purpose='m5-f1b-isolated-fixture'))
    results = []
    for name, restricted, private, logon in (('unrestricted-inherited', False, False, False),
             ('restricted-inherited', True, False, False), ('restricted-private', True, True, False),
             ('logon-inherited', True, False, True), ('logon-private', True, True, True)):
        try:
            result = experiment(root, name, restricted, private, logon)
        except OSError as error:
            result = dict(name=name, error=str(error), winerror=error.errno, user_code_reached=False)
        results.append(result)
        print(json.dumps(result), flush=True)
    P.F.save(root / 'results.json', dict(root=str(root), results=results, host_identity=P.identity(), source_files=P.source_facts(),
                                       source_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()))
    print('RESULT_ROOT=' + str(root), flush=True)
    return 0 if results[0].get('user_code_reached') and results[4].get('user_code_reached') else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-parent', type=Path, required=True)
    raise SystemExit(run(parser.parse_args().output_parent))
