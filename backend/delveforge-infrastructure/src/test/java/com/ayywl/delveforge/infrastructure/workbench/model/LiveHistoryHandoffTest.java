package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentProtocol.Message;
import com.ayywl.delveforge.application.workbench.ConversationContext;
import com.ayywl.delveforge.application.workbench.Workbench.Entry;
import com.ayywl.delveforge.application.workbench.Workbench.Receipt;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Choice;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Frozen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 显式启用的两次付费请求。仅用宿主凭据访问官方端点，测试文件和回执属于临时目录。 */
@EnabledIfEnvironmentVariable(named = "DF_WORKBENCH_LIVE_HISTORY_DB", matches = ".+")
class LiveHistoryHandoffTest {
    @Test
    void deepSeekThinkingReadsTheResultOfAnOpenAiToolCall(@TempDir Path directory) throws Exception {
        var db = new SQLiteDataSource();
        var uri = Path.of(System.getenv("DF_WORKBENCH_LIVE_HISTORY_DB")).toUri();
        db.setUrl("jdbc:sqlite:" + uri + "?mode=ro");
        var credentials = new ProviderCredentials(new JdbcTemplate(db), System::getenv);
        var source = model(credentials, ProviderFamily.OPENAI, new Choice("openai", "gpt-5.4-nano", "low"));
        var target = model(credentials, ProviderFamily.DEEPSEEK, new Choice("deepseek", "deepseek-flash", "low"));
        var first = List.of(Message.text("user", "请用 Read 读取 receipt.txt。只调用这一次工具。"));
        var firstAdmission = new AtomicBoolean();
        var turn = source.call(first, ConversationContext.TOOLS, () -> !firstAdmission.getAndSet(true));
        assertEquals(1, turn.calls().size());
        var call = turn.calls().getFirst();
        assertEquals("Read", call.name());
        assertEquals("receipt.txt", JsonMapper.builder().build().readTree(call.arguments()).path("path").asString());
        String marker = UUID.randomUUID().toString().substring(0, 8);
        Path file = Files.writeString(directory.resolve("receipt.txt"), marker);
        String invocation = UUID.randomUUID().toString();
        String content = "{\"exit\":0,\"stdout\":\"" + Files.readString(file) + "\",\"outputGap\":false}";
        var result = new Message("tool", content, List.of(), call.id(), call.name());
        var history = target.projectHistory(first, List.of(new Entry(2, turn.assistantMessage(turn.text())),
                new Entry(3, result, false, new Receipt(invocation, "FINISHED", 0, false)),
                new Entry(4, Message.text("user", "不要再调用工具。直接抄录历史 [#3] 已完成回执的 stdout 值，只输出该值。"))));
        var secondAdmission = new AtomicBoolean();
        var answer = target.call(history, ConversationContext.TOOLS, () -> !secondAdmission.getAndSet(true));
        System.out.printf("Paid handoff tokens: OpenAI=%s DeepSeek=%s%n", turn.tokens(), answer.tokens());
        assertTrue(answer.calls().isEmpty());
        assertEquals(marker, answer.text().strip());
    }

    private static WorkbenchChatModel model(ProviderCredentials credentials, ProviderFamily family, Choice choice) {
        var ref = credentials.reference(family);
        var config = new Frozen(choice, family.protocol, ref.preset(), ref.workspace(), ref.id(),
                new AgentContextBudget(16000, 1024, .9), "live-test");
        var timeout = Duration.ofSeconds(60);
        return new WorkbenchChatModel(config, family.endpoint(ref.preset(), ref.workspace()),
                () -> credentials.resolve(config), new ProviderChatModels(timeout), ModelTransport.client(timeout),
                100000, 100000);
    }
}
