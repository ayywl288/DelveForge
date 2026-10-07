package com.ayywl.delveforge.infrastructure.persistence.workingcopy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface WorkingCopyMapper extends BaseMapper<WorkingCopyDO> {
    @org.apache.ibatis.annotations.Update("""
        UPDATE working_copy SET status = #{next.status}, current_revision = #{next.currentRevision},
            last_verified_revision = #{next.lastVerifiedRevision}
        WHERE id = #{old.id.value} AND source_asset_id = #{old.sourceAssetId.value}
            AND location = #{old.location} AND source_revision = #{old.sourceRevision}
            AND status = #{old.status} AND current_revision = #{old.currentRevision}
            AND last_verified_revision = #{old.lastVerifiedRevision}
        """)
    int advanceCheckpoint(@org.apache.ibatis.annotations.Param("old") com.ayywl.delveforge.domain.evolution.WorkingCopy old,
            @org.apache.ibatis.annotations.Param("next") com.ayywl.delveforge.domain.evolution.WorkingCopy next);
}
