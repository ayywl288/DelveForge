import ctypes as C
from ctypes import wintypes as W
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location('f1', r'C:\Users\28226\.codex\worktrees\m5-lab\DelveForge\tools\spikes\m5-f1\ownership_spike.py')
f1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(f1)
A = C.WinDLL('advapi32', use_last_error=True)
def api(name, result, *args):
    f = getattr(A, name)
    f.restype, f.argtypes = result, args
    return f
class SID_ATTR(C.Structure):
    _fields_=[('Sid',C.c_void_p),('Attributes',W.DWORD)]
open_token=api('OpenProcessToken',W.BOOL,W.HANDLE,W.DWORD,C.POINTER(W.HANDLE))
restrict=api('CreateRestrictedToken',W.BOOL,W.HANDLE,W.DWORD,W.DWORD,C.c_void_p,W.DWORD,C.c_void_p,W.DWORD,C.POINTER(SID_ATTR),C.POINTER(W.HANDLE))
convert=api('ConvertStringSidToSidW',W.BOOL,W.LPCWSTR,C.POINTER(C.c_void_p))
create=api('CreateProcessAsUserW',W.BOOL,W.HANDLE,W.LPCWSTR,W.LPWSTR,C.c_void_p,C.c_void_p,W.BOOL,W.DWORD,C.c_void_p,W.LPCWSTR,C.c_void_p,C.POINTER(f1.PROCESS_INFORMATION))
get_current=f1.api('GetCurrentProcess',W.HANDLE)
token=W.HANDLE()
f1.checked(open_token(get_current(),0x8b,C.byref(token)),'OpenProcessToken')
sid=C.c_void_p()
f1.checked(convert('S-1-5-12',C.byref(sid)),'SID')
entry=SID_ATTR(sid,0)
restricted=W.HANDLE()
f1.checked(restrict(token,9,0,None,0,None,1,C.byref(entry),C.byref(restricted)),'CreateRestrictedToken')
folder=Path(tempfile.mkdtemp(prefix='delveforge-m5f1b-probe-'))
startup=f1.STARTUPINFO()
startup.cb=C.sizeof(startup)
info=f1.PROCESS_INFORMATION()
cmd=C.create_unicode_buffer(subprocess.list2cmdline([str(Path(sys.executable).resolve()),'-c','print("RESTRICTED_PROCESS_STARTED")']))
ok=create(restricted,str(Path(sys.executable).resolve()),cmd,None,None,False,0x08000000,None,str(folder),C.byref(startup),C.byref(info))
print('CREATE_RESTRICTED_TOKEN=True; CREATE_PROCESS_AS_USER='+str(bool(ok))+'; ERROR='+str(C.get_last_error()),flush=True)
if ok:
    print('CHILD_PID='+str(info.dwProcessId)+'; WAIT='+str(f1.wait_handle(info.hProcess,5000)))
    if f1.wait_handle(info.hProcess,0)!=0:
        f1.terminate_process(info.hProcess,99)
        f1.wait_handle(info.hProcess,3000)
    f1.close_handle(info.hThread)
    f1.close_handle(info.hProcess)
f1.close_handle(restricted)
f1.close_handle(token)
