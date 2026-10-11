package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkingCopyStorageInspectorTest {
    @TempDir Path temp;

    private WorkingCopy copy(Path path) {
        return WorkingCopy.create(new WorkingCopyId("copy"), new SoftwareAssetId("asset"), "A", path.toString());
    }

    @Test
    void oldRootRequiresExplicitAllowanceWithoutMovingOrRewritingCopy() throws Exception {
        Path old = Files.createDirectories(temp.resolve("old"));
        Path target = Files.createDirectory(old.resolve("copy"));
        Path next = temp.resolve("next");
        WorkingCopy copy = copy(target);
        assertEquals("WORKSPACE_ROOT_NOT_ALLOWED", new WorkingCopyStorageInspector(new WorkspaceProperties(next)).inspect(copy));
        assertNull(new WorkingCopyStorageInspector(new WorkspaceProperties(next, List.of(old))).inspect(copy));
        assertEquals(target.toString(), copy.location());
        assertFalse(Files.exists(next));
    }

    @Test
    void missingDirectoryAndEscapingPathAreReportedWithoutCreatingFiles() {
        Path root = temp.resolve("root");
        var inspector = new WorkingCopyStorageInspector(new WorkspaceProperties(root));
        assertEquals("WORKSPACE_DIRECTORY_MISSING", inspector.inspect(copy(root.resolve("missing"))));
        assertEquals("WORKSPACE_ROOT_NOT_ALLOWED", inspector.inspect(copy(temp.resolve("elsewhere/copy"))));
        assertEquals("WORKSPACE_PATH_INVALID", inspector.inspect(copy(root.resolve("../copy"))));
        assertFalse(Files.exists(root));
    }

    @Test
    void fileCannotMasqueradeAsWorkingCopyDirectory() throws Exception {
        Path root = Files.createDirectories(temp.resolve("root"));
        Path target = Files.writeString(root.resolve("copy"), "preserved");
        assertEquals("WORKSPACE_PATH_UNAVAILABLE", new WorkingCopyStorageInspector(new WorkspaceProperties(root)).inspect(copy(target)));
        assertEquals("preserved", Files.readString(target));
    }
}
