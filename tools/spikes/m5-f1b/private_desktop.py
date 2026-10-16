"""Creates only a fresh private desktop; borrows current station without changing its ACL."""
import ctypes as C
from ctypes import wintypes as W
import uuid


class SECURITY_ATTRIBUTES(C.Structure):
    _fields_ = [('length', W.DWORD), ('descriptor', C.c_void_p), ('inherit', W.BOOL)]


class PrivateDesktop:
    def __init__(self, native, user_sid):
        self.native, self.desktop = native, None
        self.name = 'DelveForgeF1b_' + uuid.uuid4().hex
        self.user32 = C.WinDLL('user32', use_last_error=True)
        current_station = self.api('GetProcessWindowStation', W.HANDLE)
        get_name = self.api('GetUserObjectInformationW', W.BOOL, W.HANDLE, W.INT,
                            C.c_void_p, W.DWORD, C.POINTER(W.DWORD))
        create_desktop = self.api('CreateDesktopW', W.HANDLE, W.LPCWSTR, W.LPCWSTR,
                                  C.c_void_p, W.DWORD, W.DWORD, C.POINTER(SECURITY_ATTRIBUTES))
        self.close_desktop = self.api('CloseDesktop', W.BOOL, W.HANDLE)
        original_station = native.F.checked(current_station(), 'borrow original station')
        name, needed = C.create_unicode_buffer(512), W.DWORD()
        native.F.checked(get_name(original_station, 2, name, C.sizeof(name), C.byref(needed)), 'station name')
        self.path = name.value + chr(92) + self.name
        descriptor = C.c_void_p()
        sddl = 'D:P(A;;GA;;;SY)(A;;GA;;;' + user_sid + ')(A;;GA;;;RC)'
        native.F.checked(native.convert_sd(sddl, 1, C.byref(descriptor), None), 'private desktop descriptor')
        attrs = SECURITY_ATTRIBUTES(C.sizeof(SECURITY_ATTRIBUTES), descriptor, False)
        try:
            C.set_last_error(0)
            self.desktop = native.F.checked(create_desktop(self.name, None, None, 0, 0xF01FF,
                                                            C.byref(attrs)), 'create task-only desktop')
            if C.get_last_error() == 183:
                raise RuntimeError('refusing preexisting desktop')
        finally:
            native.local_free(descriptor)

    def api(self, name, result, *args):
        fn = getattr(self.user32, name)
        fn.restype, fn.argtypes = result, args
        return fn

    def close(self):
        if self.desktop:
            self.native.F.checked(self.close_desktop(self.desktop), 'close task-only desktop')
            self.desktop = None

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()
