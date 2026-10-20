package com.ayywl.delveforge.application.port.workspace;

import com.ayywl.delveforge.domain.evolution.WorkingCopy;

import java.util.List;

/** 只比较服务端绑定的固定 tree；不读取工作树，不授予任何 Git 写能力。 */
public interface WorkingCopyTreeDiffPort {
    Difference compare(WorkingCopy copy, String baselineRevision, String verifiedRevision);

    record FileChange(String path, String changeType) {}

    record Difference(String reason, String baselineTree, String verifiedTree, List<FileChange> files) {
        public static Difference unavailable(String reason) {
            return new Difference(reason, null, null, null);
        }
    }
}
