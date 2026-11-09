package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import com.ayywl.delveforge.application.port.persistence.DiscoveryConversationRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.conversation.DiscoveryReadiness;
import com.ayywl.delveforge.application.userdiscovery.shared.DiscoveryContext;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.DialogueAction;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.SufficiencyAssessment;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** 短事务请求登记与原子对话提交；不跨模型调用持有 SQLite 锁，也不记录原始内容到日志。 */
@Repository
public class SqliteDiscoveryConversationRepository implements DiscoveryConversationRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final UserProfileRepository profiles;
    private final ObjectMapper json;
    private final String owner = UUID.randomUUID().toString();
    private final long pid = ProcessHandle.current().pid();
    private final String processStart = ProcessHandle.current().info().startInstant().orElseThrow().toString();

    public SqliteDiscoveryConversationRepository(DataSource source, PlatformTransactionManager manager,
            UserProfileRepository profiles, ObjectMapper json) {
        this.jdbc = new JdbcTemplate(source);
        this.transaction = new TransactionTemplate(manager);
        this.profiles = profiles;
        this.json = json;
    }

    @Override
    public Optional<Operation> operation(UserProfileId id, String requestId) {
        recoverExitedOwner(id);
        return findOperation(id, requestId);
    }

    private Optional<Operation> findOperation(UserProfileId id, String requestId) {
        return jdbc.query("SELECT * FROM discovery_request WHERE profile_id=? AND request_id=?",
                operationMapper(), id.value(), requestId).stream().findFirst();
    }

    @Override
    public Operation begin(UserProfile expected, Command command, String fingerprint) {
        recoverExitedOwner(expected.id());
        return transaction.execute(status -> {
            if (jdbc.update("UPDATE user_profile SET status=status WHERE id=? AND revision=? AND status=?",
                    expected.id().value(), expected.revision(), expected.status().name()) != 1) {
                throw new UserProfileStateException("画像已变化，请重新读取");
            }
            Operation existing = findOperation(expected.id(), command.requestId()).orElse(null);
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) {
                    throw new UserProfileStateException("请求编号已用于其他内容");
                }
                return existing;
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM discovery_request WHERE profile_id=? AND state='PENDING'",
                    Integer.class, expected.id().value()) != 0) {
                throw new UserProfileStateException("上一轮仍在处理，请查询其结果");
            }
            jdbc.update("""
                    INSERT INTO discovery_request(profile_id,request_id,kind,project_id,fingerprint,state,
                        owner_pid,owner_start,owner_token,expected_revision,created_at)
                    VALUES(?,?,?,?,?,'PENDING',?,?,?,?,?)
                    """, expected.id().value(), command.requestId(), command.kind(), command.projectId(),
                    fingerprint, pid, processStart, owner, expected.revision(), Instant.now().toString());
            return new Operation(command.requestId(), command.kind(), command.projectId(), fingerprint,
                    "STARTED", null, null);
        });
    }

    @Override
    public Operation complete(UserProfile expected, UserProfile candidate, Command command,
            SufficiencyAssessment result) {
        return transaction.execute(status -> {
            // 完成状态先 CAS 取得写锁；后续任一步失败，状态、画像、元数据及消息一起回滚。
            if (jdbc.update("""
                    UPDATE discovery_request SET state='COMPLETED',result_revision=?,result_json=?,completed_at=?
                    WHERE profile_id=? AND request_id=? AND state='PENDING' AND owner_token=?
                    """, candidate.revision(), encode(result), Instant.now().toString(), expected.id().value(),
                    command.requestId(), owner) != 1) {
                throw new UserProfileStateException("请求执行资格已经失效，请查询记录结果");
            }
            if (command.projectId() != null && jdbc.queryForObject(
                    "SELECT COUNT(*) FROM project_flow_context WHERE id=? AND user_profile_id=? AND deleted_at IS NULL",
                    Integer.class, command.projectId(), expected.id().value()) != 1) {
                throw new UserProfileStateException("请求期间项目关联已经变化");
            }
            profiles.saveWithReadiness(candidate, expected,
                    new DiscoveryReadiness(result.sufficient(), result.missingAreas()));
            if ("TURN".equals(command.kind())) {
                if ("DEFERRED".equals(result.previousQuestionState())) {
                    jdbc.update("""
                            INSERT OR IGNORE INTO discovery_deferred_topic(profile_id,area)
                            SELECT profile_id,area FROM discovery_message
                            WHERE profile_id=? AND question_state='ASKED' AND area IS NOT NULL
                            """, expected.id().value());
                }
                if (List.of("ANSWERED", "DEFERRED", "REPLACED").contains(result.previousQuestionState())) {
                    jdbc.update("""
                        UPDATE discovery_message SET question_state=?
                        WHERE profile_id=? AND question_state='ASKED'
                        """, result.previousQuestionState(), expected.id().value());
                }
                insertMessage(expected.id(), command, "user", command.input(), null, null);
            }
            for (String area : result.resumedAreas()) {
                jdbc.update("DELETE FROM discovery_deferred_topic WHERE profile_id=? AND area=?",
                        expected.id().value(), area);
            }
            for (String area : result.deferredAreas()) {
                jdbc.update("INSERT OR IGNORE INTO discovery_deferred_topic(profile_id,area) VALUES(?,?)",
                        expected.id().value(), area);
            }
            insertMessage(expected.id(), command, "agent", result.assistantReply(), result.nextQuestion(),
                    result.questionArea());
            if (result.reviewInvitation()) {
                jdbc.update("INSERT INTO discovery_invitation(profile_id, request_id) VALUES(?,?)",
                        expected.id().value(), command.requestId());
            } else {
                jdbc.update("UPDATE discovery_invitation SET choice='CONTINUE' WHERE profile_id=? AND choice IS NULL",
                        expected.id().value());
            }
            return findOperation(expected.id(), command.requestId()).orElseThrow();
        });
    }

    private void insertMessage(UserProfileId id, Command command, String role, String text,
            String question, String area) {
        jdbc.update("""
                INSERT INTO discovery_message(profile_id,request_id,project_id,role,text,question,area,
                    question_state,created_at) VALUES(?,?,?,?,?,?,?,?,?)
                """, id.value(), command.requestId(), command.projectId(), role, text == null ? "" : text,
                question, area, question == null ? null : "ASKED", Instant.now().toString());
    }

    @Override
    public void fail(UserProfileId id, String requestId) {
        jdbc.update("""
                UPDATE discovery_request SET state='FAILED',completed_at=?
                WHERE profile_id=? AND request_id=? AND state='PENDING' AND owner_token=?
                """, Instant.now().toString(), id.value(), requestId, owner);
    }

    @Override
    public Operation cancel(UserProfileId id, String requestId) {
        return transaction.execute(status -> {
            jdbc.update("UPDATE user_profile SET status=status WHERE id=?", id.value());
            Operation previous = findOperation(id, requestId).orElse(null);
            if (previous == null) {
                int revision = jdbc.queryForObject("SELECT revision FROM user_profile WHERE id=?", Integer.class, id.value());
                jdbc.update("""
                        INSERT INTO discovery_request(profile_id,request_id,kind,fingerprint,state,
                            owner_pid,owner_start,owner_token,expected_revision,created_at,completed_at)
                        VALUES(?,?,'CANCEL','','CANCELLED',?,?,?,?,?,?)
                        """, id.value(), requestId, pid, processStart, owner, revision,
                        Instant.now().toString(), Instant.now().toString());
            } else if ("PENDING".equals(previous.state())) {
                jdbc.update("""
                        UPDATE discovery_request SET state='CANCELLED',completed_at=?
                        WHERE profile_id=? AND request_id=? AND state='PENDING'
                        """, Instant.now().toString(), id.value(), requestId);
            }
            return findOperation(id, requestId).orElseThrow();
        });
    }

    @Override
    public History history(UserProfileId id, Long before) {
        recoverExitedOwner(id);
        List<Message> messages = new ArrayList<>(jdbc.query("""
                SELECT m.*, CASE WHEN m.role='agent' THEN json_extract(r.result_json, '$.nextAction') END AS next_action
                FROM discovery_message m JOIN discovery_request r USING (profile_id, request_id)
                WHERE m.profile_id=? AND sequence<? ORDER BY sequence DESC LIMIT 101
                """, messageMapper(), id.value(), before == null ? Long.MAX_VALUE : before));
        boolean more = messages.size() > 100;
        if (more) {
            messages.removeLast();
        }
        Collections.reverse(messages);
        List<String> pending = jdbc.queryForList(
                "SELECT request_id FROM discovery_request WHERE profile_id=? AND state='PENDING'",
                String.class, id.value());
        return new History(List.copyOf(messages), more ? messages.getFirst().sequence() : null,
                pending.isEmpty() ? null : pending.getFirst(), pendingQuestion(id),
                jdbc.queryForList("""
                        SELECT i.request_id FROM discovery_invitation i JOIN user_profile p ON p.id=i.profile_id
                        JOIN discovery_readiness r ON r.profile_id=p.id AND r.revision=p.revision
                        WHERE i.profile_id=? AND i.choice IS NULL AND p.status='EXPLORING' AND r.sufficient=1
                        """, String.class, id.value()).stream().findFirst().orElse(null));
    }

    @Override
    public Memory memory(UserProfileId id) {
        List<DiscoveryContext.Message> context = jdbc.query("""
                SELECT request_id, role, text, question FROM discovery_message WHERE profile_id=? ORDER BY sequence
                """, (row, index) -> {
                    String text = row.getString("text");
                    String question = row.getString("question");
                    if (question != null && !text.contains(question)) {
                        text = text.isEmpty() ? question : text + "\n\n" + question;
                    }
                    return new DiscoveryContext.Message(row.getString("role"), text, row.getString("request_id"));
                }, id.value());
        List<String> questions = jdbc.queryForList("""
                SELECT question FROM discovery_message WHERE profile_id=? AND question IS NOT NULL ORDER BY sequence
                """, String.class, id.value());
        List<String> deferred = jdbc.queryForList(
                "SELECT area FROM discovery_deferred_topic WHERE profile_id=? ORDER BY area", String.class, id.value());
        List<Topic> topics = jdbc.query("""
                SELECT area,question,question_state FROM discovery_message WHERE profile_id=? AND sequence IN
                    (SELECT MAX(sequence) FROM discovery_message WHERE profile_id=? AND area IS NOT NULL GROUP BY area)
                ORDER BY sequence DESC
                """, (row, index) -> new Topic(row.getString(1), row.getString(2), row.getString(3)),
                id.value(), id.value());
        boolean presented = jdbc.queryForObject("SELECT COUNT(*) FROM discovery_invitation WHERE profile_id=?",
                Integer.class, id.value()) > 0;
        return new Memory(List.copyOf(context), questions, deferred, topics, pendingQuestion(id), presented);
    }

    private String pendingQuestion(UserProfileId id) {
        return jdbc.queryForList("SELECT question FROM discovery_message WHERE profile_id=? AND question_state='ASKED'",
                String.class, id.value()).stream().findFirst().orElse(null);
    }

    @Override
    public Optional<DiscoveryReadiness> readiness(UserProfileId id, int revision) {
        return jdbc.query("SELECT sufficient,missing_json FROM discovery_readiness WHERE profile_id=? AND revision=?",
                (row, index) -> new DiscoveryReadiness(row.getBoolean(1), decodeList(row.getString(2))),
                id.value(), revision).stream().findFirst();
    }

    private void recoverExitedOwner(UserProfileId id) {
        jdbc.query("SELECT request_id,owner_pid,owner_start FROM discovery_request WHERE profile_id=? AND state='PENDING'",
                (row, index) -> {
                    long ownerPid = row.getLong(2);
                    ProcessHandle process = ownerPid <= 0 ? null : ProcessHandle.of(ownerPid).orElse(null);
                    String expectedStart = row.getString(3);
                    boolean exited = process == null || !process.isAlive() || process.info().startInstant()
                            .map(start -> !start.toString().equals(expectedStart)).orElse(false);
                    return exited ? row.getString(1) : null;
                }, id.value()).stream().filter(Objects::nonNull).forEach(requestId -> jdbc.update("""
                        UPDATE discovery_request SET state='INTERRUPTED',completed_at=?
                        WHERE profile_id=? AND request_id=? AND state='PENDING'
                        """, Instant.now().toString(), id.value(), requestId));
    }

    private RowMapper<Operation> operationMapper() {
        return (row, index) -> new Operation(row.getString("request_id"), row.getString("kind"),
                row.getString("project_id"), row.getString("fingerprint"), row.getString("state"),
                row.getObject("result_revision") == null ? null : row.getInt("result_revision"),
                row.getString("result_json") == null ? null : decode(row.getString("result_json")));
    }

    private RowMapper<Message> messageMapper() {
        return (row, index) -> new Message(row.getLong("sequence"), row.getString("request_id"),
                row.getString("role"), row.getString("text"), row.getString("question"), row.getString("area"),
                row.getString("question_state"), Instant.parse(row.getString("created_at")), row.getString("project_id"),
                row.getString("next_action") == null ? null : DialogueAction.valueOf(row.getString("next_action")));
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法编码探索记录");
        }
    }

    private SufficiencyAssessment decode(String value) {
        try {
            return json.readValue(value, SufficiencyAssessment.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("探索结果记录不可读");
        }
    }

    private List<String> decodeList(String value) {
        try {
            return json.readValue(value, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("充分性记录不可读");
        }
    }
}
