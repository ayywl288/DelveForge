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

        RepositoryInspectionPlan plan = extraction.scout(inputs);

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

        RepositoryInspectionPlan plan = extraction.scout(inputs);

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

        extraction.scout(inputs);

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

        extraction.scout(inputs);

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

        assertThrows(AiGatewayException.class, () -> extraction.scout(inputs));
    }

    @Test
    void propagatesMalformedResponse() {
        aiGateway.respond("{}");

        assertThrows(AiGatewayException.class, () -> extraction.scout(inputs));
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

        assertThrows(AiGatewayException.class, () -> extraction.scout(inputs));
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(IllegalArgumentException.class, () -> extraction.scout(null));
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

    /** AI Gateway 替身：记录最后一次请求，返回预设内容，或按预设抛错。 */
    private static final class StubAiGateway implements AiGateway {

        private String response = "{}";

        private RuntimeException failure;

        private AiRequest lastRequest;

        void respond(String rawResponse) {
            this.response = rawResponse;
            this.failure = null;
        }

        void failWith(RuntimeException exception) {
            this.failure = exception;
        }

        AiRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public String generate(AiRequest request) {
            this.lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
