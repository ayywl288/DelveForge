package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.WorkspaceException;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 正式 Delivery 专用写入边界。新增只创建，旧文件先取得身份并核对；不执行 checkout/reset。
 * Windows 用共享模式固定目录/文件；Linux 用目录 fd 和 no-replace 捕获保留旧条目。
 */
final class DeliveryFiles {
    record Change(String path, byte[] before, byte[] after, String mode) {
        boolean indexGuard() {
            return path.equals(".git/index");
        }
    }

    static final class NotStarted extends IOException {
        NotStarted(Throwable failure) {
            super("No official file/index mutation started", failure);
        }
    }

    private static final class Mutation {
        boolean started;
    }

    private DeliveryFiles() { }

    static void apply(Path root, List<Change> changes, String captureName) throws IOException {
        // 新增目标先占位；首个新增冲突时，旧 tracked 文件和 index 尚未被修改。
        changes = changes.stream()
                .sorted(Comparator.comparingInt(change -> change.before() == null ? 0 : 1)).toList();
        Mutation mutation = new Mutation();
        try {
            if (Platform.isWindows()) {
                windows(root, changes, mutation);
            } else if (Platform.isLinux() && Platform.ARCH.equals("x86-64")) {
                linux(root, changes, captureName, mutation);
            } else {
                throw new IOException("Delivery requires Windows handles or Linux x86-64 no-replace support");
            }
        } catch (IOException | LinkageError failure) {
            if (!mutation.started) {
                throw new NotStarted(failure);
            }
            if (failure instanceof IOException io) {
                throw io;
            }
            throw new WorkspaceException("Native non-destructive Delivery capability is unavailable", failure);
        }
    }

    private static IOException conflict(String operation) {
        return new IOException("Non-destructive Delivery refused: " + operation + " (native " + Native.getLastError() + ")");
    }

    private interface Win extends StdCallLibrary {
        Win API = Native.load("kernel32", Win.class);
        int READ_WRITE_DELETE = 0xc0010000;
        int SHARE_READ_WRITE = 3;
        int CREATE_NEW = 1;
        int OPEN_EXISTING = 3;
        int OPEN_REPARSE_POINT = 0x00200000;
        int BACKUP_SEMANTICS = 0x02000000;
        int DIRECTORY = 0x10;
        int REPARSE_POINT = 0x400;
        int FILE_DISPOSITION_INFO = 4;

        Pointer CreateFileW(WString name, int access, int share, Pointer security, int creation, int flags,
                Pointer template);
        boolean CloseHandle(Pointer handle);
        boolean GetFileInformationByHandle(Pointer handle, Pointer information);
        int GetFinalPathNameByHandleW(Pointer handle, char[] name, int length, int flags);
        boolean ReadFile(Pointer handle, byte[] buffer, int length, IntByReference read, Pointer overlapped);
        boolean WriteFile(Pointer handle, byte[] buffer, int length, IntByReference written, Pointer overlapped);
        boolean SetFilePointerEx(Pointer handle, long distance, Pointer result, int method);
        boolean SetEndOfFile(Pointer handle);
        boolean FlushFileBuffers(Pointer handle);
        boolean SetFileInformationByHandle(Pointer handle, int kind, Pointer information, int length);
    }

    private static final class Handle implements AutoCloseable {
        final Pointer value;

        Handle(Path path, boolean directory, boolean create, Mutation mutation) throws IOException {
            // 目录禁止删除共享；文件禁止全部共享，避免核对后被写入、替换或删除。
            value = Win.API.CreateFileW(new WString(path.toString()), directory ? 0 : Win.READ_WRITE_DELETE,
                    directory ? Win.SHARE_READ_WRITE : 0, null, create ? Win.CREATE_NEW : Win.OPEN_EXISTING,
                    Win.OPEN_REPARSE_POINT | (directory ? Win.BACKUP_SEMANTICS : 0), null);
            if (value == null || Pointer.nativeValue(value) == -1) {
                throw conflict("open " + path.getFileName());
            }
            if (create) {
                mutation.started = true;
            }
            try {
                try (Memory info = new Memory(52)) {
                    if (!Win.API.GetFileInformationByHandle(value, info)) {
                        throw conflict("file identity");
                    }
                    // BY_HANDLE_FILE_INFORMATION 的 DWORD 属性在 0、硬链接计数在 40。
                    int attributes = info.getInt(0);
                    if ((attributes & Win.REPARSE_POINT) != 0 || ((attributes & Win.DIRECTORY) != 0) != directory
                            || !directory && info.getInt(40) != 1) {
                        throw conflict("reparse, hardlink or unexpected entry type");
                    }
                }
                char[] name = new char[32768];
                int length = Win.API.GetFinalPathNameByHandleW(value, name, name.length, 0);
                if (length <= 0 || length >= name.length) {
                    throw conflict("canonical handle path");
                }
                String actual = new String(name, 0, length);
                actual = actual.startsWith("\\\\?\\UNC\\") ? "\\\\" + actual.substring(8)
                        : actual.startsWith("\\\\?\\") ? actual.substring(4) : actual;
                if (!Path.of(actual).equals(path)) {
                    throw conflict("aliased handle path");
                }
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        }

        void requireBytes(byte[] expected) throws IOException {
            byte[] bytes = new byte[expected.length + 1];
            IntByReference count = new IntByReference();
            if (!Win.API.ReadFile(value, bytes, bytes.length, count, null)
                    || count.getValue() != expected.length
                    || !Arrays.equals(expected, Arrays.copyOf(bytes, count.getValue()))) {
                throw conflict("changed tracked bytes");
            }
        }

        void write(byte[] bytes) throws IOException {
            IntByReference count = new IntByReference();
            if (!Win.API.SetFilePointerEx(value, 0, null, 0)
                    || !Win.API.WriteFile(value, bytes, bytes.length, count, null) || count.getValue() != bytes.length
                    || !Win.API.SetEndOfFile(value) || !Win.API.FlushFileBuffers(value)) {
                throw conflict("write verified handle");
            }
        }

        void delete() throws IOException {
            try (Memory disposition = new Memory(1)) {
                disposition.clear();
                disposition.setByte(0, (byte) 1);
                if (!Win.API.SetFileInformationByHandle(value, Win.FILE_DISPOSITION_INFO, disposition, 1)) {
                    throw conflict("delete verified handle");
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (!Win.API.CloseHandle(value)) {
                throw conflict("close verified handle");
            }
        }
    }

    private static void windows(Path root, List<Change> changes, Mutation mutation) throws IOException {
        List<Handle> handles = new ArrayList<>();
        Map<Path, Handle> directories = new HashMap<>();
        Map<String, Handle> files = new HashMap<>();
        try {
            // 从卷根逐级固定，最终项使用 OPEN_REPARSE_POINT，不能跟随刚换入的 junction。
            pin(root, directories, handles, false, mutation);
            for (Change change : changes) {
                Path path = root.resolve(change.path());
                pin(path.getParent(), directories, handles, true, mutation);
                if (change.before() != null) {
                    Handle file = new Handle(path, false, false, mutation);
                    handles.add(file);
                    file.requireBytes(change.before());
                    files.put(change.path(), file);
                }
            }
            // 所有旧文件都已独占且核对；先用 CREATE_NEW 取得每个新增目标，绝不回退到替换。
            for (Change change : changes) {
                if (change.before() == null) {
                    Handle file = new Handle(root.resolve(change.path()), false, true, mutation);
                    handles.add(file);
                    files.put(change.path(), file);
                }
            }
            for (Change change : changes) {
                if (change.indexGuard()) {
                    continue;
                }
                mutation.started = true;
                if (change.after() == null) {
                    files.get(change.path()).delete();
                } else {
                    files.get(change.path()).write(change.after());
                }
            }
        } finally {
            closeReverse(handles);
        }
    }

    private static void pin(Path directory, Map<Path, Handle> pinned, List<Handle> handles, boolean create,
            Mutation mutation)
            throws IOException {
        if (pinned.containsKey(directory)) {
            return;
        }
        if (directory.getParent() != null) {
            pin(directory.getParent(), pinned, handles, create, mutation);
        }
        if (create && !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            // 父目录句柄已固定；若别的写入者抢先创建，保留现场并终止。
            Files.createDirectory(directory);
            mutation.started = true;
        }
        Handle handle = new Handle(directory, true, false, mutation);
        handles.add(handle);
        pinned.put(directory, handle);
    }

    private interface Linux extends com.sun.jna.Library {
        Linux API = Native.load("c", Linux.class);
        int AT_FDCWD = -100;
        int AT_EMPTY_PATH = 0x1000;
        int O_DIRECTORY = 0x10000;
        int O_NOFOLLOW = 0x20000;
        int O_NONBLOCK = 0x800;
        int O_CLOEXEC = 0x80000;
        int O_WRONLY_CREAT_EXCL = 0xc1;
        int RENAME_NOREPLACE = 1;
        int openat(int directory, String path, int flags, int mode);
        int mkdirat(int directory, String path, int mode);
        int renameat2(int fromDirectory, String from, int toDirectory, String to, int flags);
        int statx(int directory, String path, int flags, int mask, Pointer information);
        long read(int file, byte[] buffer, long length);
        long write(int file, byte[] buffer, long length);
        int fsync(int file);
        int fchmod(int file, int mode);
        int close(int file);
    }

    private record Descriptor(int value) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (Linux.API.close(value) != 0) {
                throw conflict("close directory/file fd");
            }
        }
    }

    private static int directory(int parent, String name, boolean create, List<Descriptor> descriptors)
            throws IOException {
        int flags = Linux.O_DIRECTORY | Linux.O_NOFOLLOW | Linux.O_CLOEXEC;
        int file = Linux.API.openat(parent, name, flags, 0);
        if (file < 0 && create && Native.getLastError() == 2) {
            if (Linux.API.mkdirat(parent, name, 0755) != 0) {
                throw conflict("create directory");
            }
            file = Linux.API.openat(parent, name, flags, 0);
        }
        if (file < 0) {
            throw conflict("open directory without following links");
        }
        descriptors.add(new Descriptor(file));
        return file;
    }

    private static void requireLinuxBytes(int parent, String name, byte[] expected) throws IOException {
        int file = Linux.API.openat(parent, name, Linux.O_NOFOLLOW | Linux.O_NONBLOCK | Linux.O_CLOEXEC, 0);
        if (file < 0) {
            throw conflict("open captured source");
        }
        try (Descriptor ignored = new Descriptor(file)) {
            try (Memory info = new Memory(256)) {
                // statx 的稳定 UAPI：stx_mode 在偏移 28，不能把 FIFO/设备当成可证明源码。
                if (Linux.API.statx(file, "", Linux.AT_EMPTY_PATH, 1, info) != 0
                        || (info.getShort(28) & 0170000) != 0100000) {
                    throw conflict("captured source is not a regular file");
                }
            }
            byte[] bytes = new byte[expected.length + 1];
            long length = Linux.API.read(file, bytes, bytes.length);
            if (length != expected.length || !Arrays.equals(expected, Arrays.copyOf(bytes, (int) Math.max(length, 0)))) {
                throw conflict("changed captured source bytes");
            }
        }
    }

    private static void linux(Path root, List<Change> changes, String captureName, Mutation mutation) throws IOException {
        List<Descriptor> descriptors = new ArrayList<>();
        try {
            int base = directory(Linux.AT_FDCWD, "/", false, descriptors);
            for (Path part : root) {
                base = directory(base, part.toString(), false, descriptors);
            }
            Map<String, Integer> parents = new HashMap<>();
            for (Change change : changes) {
                int parent = base;
                Path relative = Path.of(change.path());
                if (relative.getParent() != null) {
                    for (Path part : relative.getParent()) {
                        parent = directory(parent, part.toString(), true, descriptors);
                    }
                }
                parents.put(change.path(), parent);
                if (change.before() != null) {
                    requireLinuxBytes(parent, relative.getFileName().toString(), change.before());
                }
            }
            int git = directory(base, ".git", false, descriptors);
            if (Linux.API.mkdirat(git, captureName, 0700) != 0) {
                throw conflict("reserve captured-source directory");
            }
            int captured = directory(git, captureName, false, descriptors);
            byte[] manifest = changes.stream().filter(change -> change.before() != null && !change.indexGuard())
                    .map(Change::path).collect(Collectors.joining("\n", "", "\n")).getBytes(StandardCharsets.UTF_8);
            int createFlags = Linux.O_WRONLY_CREAT_EXCL | Linux.O_NOFOLLOW | Linux.O_CLOEXEC;
            int manifestFile = Linux.API.openat(captured, "paths", createFlags, 0600);
            if (manifestFile < 0) {
                throw conflict("create capture path manifest");
            }
            try (Descriptor ignored = new Descriptor(manifestFile)) {
                if (Linux.API.write(manifestFile, manifest, manifest.length) != manifest.length
                        || Linux.API.fsync(manifestFile) != 0) {
                    throw conflict("write capture path manifest");
                }
            }
            int sequence = 0;
            for (Change change : changes) {
                if (change.indexGuard()) {
                    continue;
                }
                int parent = parents.get(change.path());
                String name = Path.of(change.path()).getFileName().toString();
                if (change.before() != null) {
                    String saved = Integer.toString(sequence++);
                    mutation.started = true;
                    if (Linux.API.renameat2(parent, name, captured, saved, Linux.RENAME_NOREPLACE) != 0) {
                        throw conflict("capture existing source without replacement");
                    }
                    try {
                        requireLinuxBytes(captured, saved, change.before());
                    } catch (IOException failure) {
                        // 恢复只能 no-replace；新占用者与捕获内容都不能被自动清除。
                        if (Linux.API.renameat2(captured, saved, parent, name, Linux.RENAME_NOREPLACE) != 0) {
                            failure.addSuppressed(conflict("captured bytes retained; original path occupied"));
                        }
                        throw failure;
                    }
                }
                if (change.after() != null) {
                    int mode = change.mode().equals("100755") ? 0755 : 0644;
                    int file = Linux.API.openat(parent, name, createFlags, mode);
                    if (file < 0) {
                        throw conflict("create source without replacement");
                    }
                    mutation.started = true;
                    try (Descriptor ignored = new Descriptor(file)) {
                        if (Linux.API.write(file, change.after(), change.after().length) != change.after().length
                                || Linux.API.fchmod(file, mode) != 0 || Linux.API.fsync(file) != 0) {
                            throw conflict("write newly created source");
                        }
                    }
                }
            }
        } finally {
            closeReverse(descriptors);
        }
    }

    private static void closeReverse(List<? extends AutoCloseable> resources) throws IOException {
        IOException failure = null;
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = new IOException("Cannot close Delivery filesystem handle", closeFailure);
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
