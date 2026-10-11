package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.WorkingCopyStorageInspection;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 历史页的只读预检；执行仍须通过原 Workspace 的完整守卫，不能以此授予权限。 */
@Component
public final class WorkingCopyStorageInspector implements WorkingCopyStorageInspection {
    private final WorkspaceProperties properties;

    public WorkingCopyStorageInspector(WorkspaceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String inspect(WorkingCopy copy) {
        try {
            Path target = Path.of(copy.location());
            if (!target.isAbsolute() || !target.equals(target.normalize()) || target.getParent() == null) {
                return "WORKSPACE_PATH_INVALID";
            }
            List<Path> roots = new ArrayList<>(properties.existingRoots());
            if (properties.root() != null) {
                roots.add(properties.root());
            }
            if (roots.stream().noneMatch(root -> root.isAbsolute()
                    && root.normalize().equals(target.getParent()))) {
                return "WORKSPACE_ROOT_NOT_ALLOWED";
            }
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                return "WORKSPACE_DIRECTORY_MISSING";
            }
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    || !target.toRealPath().equals(target) || !Files.isReadable(target)) {
                return "WORKSPACE_PATH_UNAVAILABLE";
            }
            return null;
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            return "WORKSPACE_PATH_UNAVAILABLE";
        }
    }
}
