package com.ayywl.delveforge.application.repositoryanalysis.scout;

import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.CONTROLLER;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.SERVICE_IMPL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Scout 的提取编排：描述符交给 AI，返回可信的查看计划。
 *
 * <p>AI 能力在 Port 边界用替身替代，因此本类不依赖 Spring、不访问网络，
 * 也不产生真实 LLM 调用（AGENTS.md §10.2、§10.6）。
 *
 * <p>失败的验证是「以异常结束、不返回任何结果」：本类只有 AiGateway 一个协作者，
 * 不持有 Workspace、Aggregate 或持久化能力，因此 AI 失败、解析失败与引用校验失败
 * 都不可能留下副作用，也不可能返回半份计划。
 */
class RepositoryScoutExtractionTest {

    private final StubAiGateway aiGateway = new StubAiGateway();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RepositoryScoutExtraction extraction =
            new RepositoryScoutExtraction(aiGateway, objectMapper);

    private final RepositoryScoutInputs inputs = ScoutFixtures.inputs();

    @Test
    void returnsATrustedPlanFromTheModelOutput() {
        aiGateway.respond(ScoutFixtures.validResponse());

        RepositoryInspectionPlan plan = extraction.scout(inputs, () -> { });

        assertEquals(ScoutFixtures.REVISION, plan.analyzedRevision());
        assertEquals(3, plan.areaCount());
        assertEquals(CONTROLLER, plan.areas().get(0).entries().get(0).relativePath());
        assertEquals(SERVICE_IMPL, plan.areas().get(2).entries().get(0).relativePath());
    }

    /**
     * 计划里的描述符就是清单里发出去的那些对象。
     *
     * <p>「模型输出中的路径不进入将来的读取」这条约束，最终就落在这一点上：
     * 路径来自 Map，而不是来自模型。
     */
    @Test
    void planEntriesAreTheVeryDescriptorsThatWereSent() {
        aiGateway.respond(ScoutFixtures.validResponse());

        RepositoryInspectionPlan plan = extraction.scout(inputs, () -> { });

        RepositoryMapEntry controller = plan.areas().get(0).entries().get(0);
        assertSame(inputs.catalog().get(0), controller);
    }

    /**
     * 请求里给出的是「revision + 编号 + 描述符」。
     *
     * <p>其中**没有文件内容**——模型看得到路径，看不到代码。这是这个阶段成立的前提：
     * 一旦把内容发出去，「只看一眼整棵树」就退化成另一次有界采样。
     */
    @Test
    void sendsTheCatalogWithoutFileContents() throws Exception {
        aiGateway.respond(ScoutFixtures.validResponse());

        extraction.scout(inputs, () -> { });

        AiRequest request = aiGateway.lastRequest();
        assertEquals(AiResponseFormat.JSON, request.responseFormat());
        assertEquals(AiRole.SYSTEM, request.messages().get(0).role());
        assertTrue(request.messages().get(0).content().contains("json"),
                "提示词必须包含 json 字样：Provider 的 JSON 模式依赖它");
        assertEquals(AiRole.USER, request.messages().get(1).role());

        String userMessage = request.messages().get(1).content();
        JsonNode payload = objectMapper.readTree(userMessage);
        assertEquals(ScoutFixtures.REVISION, payload.get("analyzedRevision").asText());

        JsonNode catalog = payload.get("fileCatalog");
        assertEquals(inputs.size(), catalog.size());

        JsonNode controller = catalog.get(0);
        assertEquals("RF-4", controller.get("reference").asText());
        assertEquals(CONTROLLER, controller.get("path").asText());
        assertEquals(3_000, controller.get("sizeBytes").asLong());
        assertEquals("JAVA", controller.get("language").asText());
        assertEquals("SOURCE_CODE", controller.get("materialKind").asText());
        assertEquals("API_ENTRY", controller.get("roleHints").get(0).asText());

        // 描述符里没有内容字段：模型看到的是「这是什么文件」，不是「它写了什么」
        assertFalse(controller.has("content"), "请求不得携带文件内容");
        assertFalse(controller.has("text"), "请求不得携带文件内容");
    }

    /**
     * 提示词把边界写清楚：只回答去哪里看，不要下结论。
     */
    @Test
    void instructsTheModelToStayInsideTheInspectionBoundary() {
        aiGateway.respond(ScoutFixtures.validResponse());

        extraction.scout(inputs, () -> { });

        String instruction = aiGateway.lastRequest().messages().get(0).content();
        assertTrue(instruction.contains("focusAreas"), "系统指令必须给出输出契约");
        assertTrue(instruction.contains("RF-"), "系统指令必须说明引用形式");
        assertTrue(instruction.contains(String.valueOf(
                        AiRepositoryScoutProposal.MIN_FOCUS_AREAS))
                        && instruction.contains(String.valueOf(
                        AiRepositoryScoutProposal.MAX_FOCUS_AREAS)),
                "系统指令里的区域数量范围应与解析器使用同一组常量");
    }

    // ---------------------------------------------------------------------
    // 失败：以异常结束，不返回半份计划
    // ---------------------------------------------------------------------

    @Test
    void propagatesAiGatewayFailure() {
        aiGateway.failWith(new AiGatewayException("provider 不可用", new RuntimeException("boom")));

        assertThrows(AiGatewayException.class, () -> extraction.scout(inputs, () -> { }));
    }

    @Test
    void propagatesMalformedResponse() {
        aiGateway.respond("{}");

        assertThrows(AiGatewayException.class, () -> extraction.scout(inputs, () -> { }));
    }

    /**
     * 引用校验失败同样整次失败：不做「丢掉那个区域、保留其余部分」的降级。
     */
    @Test
    void propagatesReferenceValidationFailure() {
        aiGateway.respond("""
                {
                  "focusAreas": [
                    { "label": "基础材料", "fileRefs": ["RF-1"] },
                    { "label": "领域模型", "fileRefs": ["RF-5"] },
                    { "label": "服务实现", "fileRefs": ["RF-7"] }
                  ]
                }
                """);

        assertThrows(AiGatewayException.class, () -> extraction.scout(inputs, () -> { }));
    }

    // ---------------------------------------------------------------------
    // 有界重试：模型这一次没按约定作答时可以再问一次
    // ---------------------------------------------------------------------

    /**
     * 第一次违反输出契约、第二次守约 → 成功。
     *
     * <p>真实证据：多仓 Smoke 里 memos 的第一次分析正是因为 File Scout 返回了 7 个
     * {@code focusAreas}（契约上限 6）而整次作废，同一资产、同一 revision 再跑一次即成功。
     * 契约违反属于「模型这次没答好」，再问一次是值得的。
     */
    @Test
    void retriesOnceWhenTheResponseViolatesTheScoutContract() {
        aiGateway.respondSequence(
                sevenFocusAreas(),
                ScoutFixtures.validResponse());

        RepositoryInspectionPlan plan = extraction.scout(inputs, () -> { });

        assertEquals(2, aiGateway.calls(), "第一次不合法 → 重试一次");
        assertEquals(3, plan.areaCount(), "重试拿到的合法结果被正常使用");
    }

    /**
     * 两次都不合法 → 失败，且失败语义与以前一致（仍是 {@link AiGatewayException}）。
     *
     * <p>重试**不放松**校验：不会把 7 个区域截成 6 个，也不会丢掉那个不存在的引用。
     */
    @Test
    void failsWhenTheResponseViolatesTheContractTwice() {
        aiGateway.respondSequence(sevenFocusAreas(), sevenFocusAreas());

        assertThrows(AiGatewayException.class, () -> extraction.scout(inputs, () -> { }));
        assertEquals(2, aiGateway.calls(), "最多两次尝试，不会无限重试");
    }

    /**
     * 调用本身失败**不重试**：那是外部能力的问题，再问一次未必更好。
     */
    @Test
    void doesNotRetryAProviderFailure() {
        aiGateway.failWith(new AiGatewayException("provider 不可用", new RuntimeException("boom")));

        assertThrows(AiGatewayException.class, () -> extraction.scout(inputs, () -> { }));
        assertEquals(1, aiGateway.calls(), "调用失败只发生一次");
    }

    /**
     * 预算不允许第二次尝试时，**重试不会发出去**，抛出的是许可的异常。
     *
     * <p>「重试是一次真实的模型调用」这句话在这里变成可执行的事实：
     * 额度不够时它连请求都不会发出，抛出的也不是 Scout 的契约违反，而是预算耗尽。
     */
    @Test
    void doesNotSendTheRetryWhenThePermitRefusesIt() {
        aiGateway.respondSequence(sevenFocusAreas(), ScoutFixtures.validResponse());

        List<Integer> acquisitions = new ArrayList<>();
        ScoutAttemptPermit permit = () -> {
            acquisitions.add(acquisitions.size() + 1);
            if (acquisitions.size() > 1) {
                throw new IllegalStateException("预算用尽");
            }
        };

        assertThrows(IllegalStateException.class, () -> extraction.scout(inputs, permit));
        assertEquals(1, aiGateway.calls(), "第二次尝试被许可挡下，没有发出请求");
    }

    /**
     * 每个区域的引用都合法（都在本次清单里、区域内不重复），**唯独区域总数是 7**。
     *
     * <p>真实 Smoke 里 memos 遇到的正是这一种：唯一的违规是数量多了一个，
     * 而不是引用了不存在的东西。
     */
    private static String sevenFocusAreas() {
        return """
                {
                  "focusAreas": [
                    { "label": "a1", "fileRefs": ["RF-1"] },
                    { "label": "a2", "fileRefs": ["RF-2"] },
                    { "label": "a3", "fileRefs": ["RF-3"] },
                    { "label": "a4", "fileRefs": ["RF-4"] },
                    { "label": "a5", "fileRefs": ["RF-1"] },
                    { "label": "a6", "fileRefs": ["RF-2"] },
                    { "label": "a7", "fileRefs": ["RF-3"] }
                  ]
                }
                """;
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(IllegalArgumentException.class, () -> extraction.scout(null, () -> { }));
    }

    @Test
    void rejectsMissingDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryScoutExtraction(null, objectMapper));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryScoutExtraction(aiGateway, null));
    }

    // ---------------------------------------------------------------------
    // 替身
    // ---------------------------------------------------------------------

    /** AI Gateway 替身：记录每一次请求，按顺序返回预设内容，或按预设抛错。 */
    private static final class StubAiGateway implements AiGateway {

        private final List<String> sequence = new ArrayList<>();

        private String response = "{}";

        private RuntimeException failure;

        private AiRequest lastRequest;

        private int calls;

        void respond(String rawResponse) {
            this.sequence.clear();
            this.response = rawResponse;
            this.failure = null;
        }

        /** 按顺序返回多次调用的内容；用完之后继续返回最后一条。 */
        void respondSequence(String... rawResponses) {
            this.sequence.clear();
            this.sequence.addAll(List.of(rawResponses));
            this.failure = null;
        }

        void failWith(RuntimeException exception) {
            this.failure = exception;
        }

        AiRequest lastRequest() {
            return lastRequest;
        }

        int calls() {
            return calls;
        }

        @Override
        public String generate(AiRequest request) {
            this.lastRequest = request;
            this.calls++;
            if (failure != null) {
                throw failure;
            }
            if (sequence.isEmpty()) {
                return response;
            }
            this.response = sequence.remove(0);
            return response;
        }
    }
}
