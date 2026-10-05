package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;
import java.util.Optional;

/**
 * 只读取 WorkingCopy 元数据；初次写入属于原子激活提交。
 */
public interface WorkingCopyRepository {
    Optional<WorkingCopy> findById(WorkingCopyId id);
}
