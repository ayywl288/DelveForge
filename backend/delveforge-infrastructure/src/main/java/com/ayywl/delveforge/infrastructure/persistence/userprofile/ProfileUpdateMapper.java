package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** 六区人工保护和整轮提案的最小存储操作。 */
public interface ProfileUpdateMapper {

    @Update("UPDATE user_profile SET status=status WHERE id=#{id}")
    int lockProfile(String id);

    @Select("SELECT section FROM user_profile_section_protection WHERE profile_id=#{id} ORDER BY section")
    List<String> protectedSections(String id);

    @Insert("INSERT OR IGNORE INTO user_profile_section_protection(profile_id,section) VALUES(#{id},#{section})")
    void protect(@Param("id") String id, @Param("section") String section);

    @Select("SELECT * FROM user_profile_update_proposal WHERE profile_id=#{id} AND state='PENDING'")
    Map<String, Object> pending(String id);

    @Select("SELECT * FROM user_profile_update_proposal WHERE profile_id=#{id} AND id=#{proposalId}")
    Map<String, Object> find(@Param("id") String id, @Param("proposalId") String proposalId);

    @Insert("""
            INSERT INTO user_profile_update_proposal(id,profile_id,source_request_id,project_id,base_revision,
                base_status,snapshot_json,state) VALUES(#{proposalId},#{id},#{requestId},#{projectId},#{revision},
                #{status},#{snapshot},'PENDING')
            """)
    void insert(@Param("proposalId") String proposalId, @Param("id") String id,
            @Param("requestId") String requestId, @Param("projectId") String projectId,
            @Param("revision") int revision, @Param("status") String status, @Param("snapshot") String snapshot);

    @Update("UPDATE user_profile_update_proposal SET state='SUPERSEDED' WHERE profile_id=#{id} AND state='PENDING'")
    void supersede(String id);

    @Update("""
            UPDATE user_profile_update_proposal SET state=#{state},resulting_revision=#{revision}
            WHERE profile_id=#{id} AND id=#{proposalId} AND state='PENDING'
            """)
    int resolve(@Param("id") String id, @Param("proposalId") String proposalId,
            @Param("state") String state, @Param("revision") Integer revision);

    @Select("""
            SELECT COUNT(*) FROM project_flow_context WHERE id=#{projectId}
            AND user_profile_id=#{id} AND deleted_at IS NULL
            """)
    int projectMatches(@Param("id") String id, @Param("projectId") String projectId);
}
