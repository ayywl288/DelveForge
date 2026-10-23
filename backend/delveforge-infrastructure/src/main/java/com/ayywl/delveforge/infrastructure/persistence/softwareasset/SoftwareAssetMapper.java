package com.ayywl.delveforge.infrastructure.persistence.softwareasset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 资产授权 CAS 与登记写锁；技术 SQL 不进入 Domain / Application。 */
public interface SoftwareAssetMapper extends BaseMapper<SoftwareAssetDO> {
    // 技术 guard 无业务状态；缺行时在同一事务内建立，已有行仍通过写入取得锁。
    @Insert("""
            INSERT INTO software_asset_registration_guard (id, guard_value) VALUES (1, 0)
            ON CONFLICT(id) DO UPDATE SET guard_value = guard_value
            """)
    int lockRegistration();

    @Update("""
            UPDATE software_asset SET read_permission = #{row.readPermission}, license_info = #{row.licenseInfo},
                usage_authorization = #{row.usageAuthorization}, authorization_revision = #{row.authorizationRevision}
            WHERE id = #{row.id} AND authorization_revision = #{expectedRevision}
                AND type = #{row.type} AND source = #{row.source} AND location = #{row.location}
            """)
    int updateAuthorization(@Param("row") SoftwareAssetDO row, @Param("expectedRevision") long expectedRevision);
}
