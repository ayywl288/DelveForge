package com.ayywl.delveforge.application.workbench;

import java.util.Optional;

/** 在宿主选择源目录、建立独立的项目副本，或打开已有副本。 */
public interface ProjectFiles {
    /** 用户取消返回空，只选择目录不会导入文件。 */
    Optional<String> chooseSourceFolder();

    final class FolderPickerUnavailable extends RuntimeException {
        public FolderPickerUnavailable(Exception cause) {
            super("无法打开本机文件夹选择器。", cause);
        }
    }

    String prepare(String id, String source);

    void open(String id);
}
