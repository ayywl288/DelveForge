package com.ayywl.delveforge.application.port.workspace;

import com.ayywl.delveforge.domain.evolution.WorkingCopy;

/** 只读目录元数据检查；不读源码、运行 Git、构建或创建目录，不证明执行安全。 */
public interface WorkingCopyStorageInspection {
    String inspect(WorkingCopy copy);
}
