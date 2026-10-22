package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** 防止第二个新应用实例把仍运行的进程误判为启动残留；数据库唯一约束另管请求并发。 */
public final class AgentInstanceLock implements AutoCloseable {
    private static final Set<Path> OWNED = new HashSet<>();

    private final Path location;
    private final FileChannel channel;
    private final FileLock lock;

    public AgentInstanceLock(Path database) {
        FileChannel opened = null;
        Path claimed = null;
        try {
            Path requested = Path.of(database.toAbsolutePath().normalize() + ".agent.lock");
            Files.createDirectories(requested.getParent());
            if (Files.isSymbolicLink(requested)) {
                throw new AgentException("INSTANCE_CONFLICT", "Agent ownership file must not be a symbolic link");
            }
            Path resolved = Files.exists(requested) ? requested.toRealPath()
                    : requested.getParent().toRealPath().resolve(requested.getFileName());
            claim(resolved);
            claimed = resolved;
            opened = FileChannel.open(resolved, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock acquired = opened.tryLock();
            if (acquired == null) {
                throw new AgentException("INSTANCE_CONFLICT", "Another Agent application owns this database");
            }
            location = resolved;
            channel = opened;
            lock = acquired;
        } catch (IOException | OverlappingFileLockException | AgentException failure) {
            boolean released = true;
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                    released = false;
                }
            }
            if (claimed != null && released) {
                releaseClaim(claimed);
            }
            if (failure instanceof AgentException classified) {
                throw classified;
            }
            throw new AgentException("INSTANCE_CONFLICT", "Cannot exclusively own Agent restart reconciliation", failure);
        }
    }

    private static synchronized void claim(Path location) throws IOException {
        // 部分平台关闭第二个 channel 会释放同 JVM 的已有锁；必须在打开前拒绝同文件与别名。
        for (Path owned : OWNED) {
            if (owned.equals(location) || Files.exists(location) && Files.isSameFile(owned, location)) {
                throw new AgentException("INSTANCE_CONFLICT", "Another Agent application owns this database");
            }
        }
        OWNED.add(location);
    }

    private static synchronized void releaseClaim(Path location) {
        OWNED.remove(location);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
        // 释放发生异常时保留 JVM 占用，不能推断另一个 owner 已安全接管。
        releaseClaim(location);
    }
}
