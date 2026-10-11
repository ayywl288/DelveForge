package com.ayywl.delveforge.infrastructure.persistence.softwareasset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 资产授权 CAS 与登记写锁；技术 SQL 不进入 Domain / Application。 */
public interface SoftwareAssetMapper extends BaseMapper<SoftwareAssetDO> {
    @Update("UPDATE software_asset_registration_guard SET guard_value = guard_value WHERE id = 1")
    int lockRegistration();

    @Update("""
            UPDATE software_asset SET read_permission = #{row.readPermission}, license_info = #{row.licenseInfo},
                usage_authorization = #{row.usageAuthorization}, authorization_revision = #{row.authorizationRevision}
            WHERE id = #{row.id} AND authorization_revision = #{expectedRevision}
                AND type = #{row.type} AND source = #{row.source} AND location = #{row.location}
            """)
    int updateAuthorization(@Param("row") SoftwareAssetDO row, @Param("expectedRevision") long expectedRevision);
}
