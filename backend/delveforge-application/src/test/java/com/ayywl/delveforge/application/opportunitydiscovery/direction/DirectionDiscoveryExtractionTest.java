package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.ayywl.delveforge.domain.direction.DirectionProposal;
import com.ayywl.delveforge.domain.direction.EvidenceReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Direction Discovery 的提取编排：可信输入交给 AI，解析结果作为提议返回。
 *
 * <p>AI 能力在 Port 边界用替身替代，因此本类不依赖 Spring、不访问网络，
 * 也不产生真实 LLM 调用（AGENTS.md §10.2、§10.6）。
 *
 * <p>失败的验证是「以异常结束、不返回任何结果」：本类只有 {@code AiGateway} 与解析器
 * 两个协作者，不持有 Aggregate 或持久化能力，因此任何失败都不可能留下领域或持久化副作用。
 */
class DirectionDiscoveryExtractionTest {

    private static final DirectionDiscoveryInputs INPUTS = DirectionDiscoveryFixtures.inputs();

    private static final String VALID_RESPONSE = """
            {
              "directions": [
                {
                  "title": "个人记账 + 报表导出",
                  "problem": "现有记账工具缺少可导出的报表",
                  "targetProduct": "单用户桌面记账工具 + 报表导出",
                  "userFit": "用户已经在用记账工具，且技术栈匹配",
                  "candidateAssetIds": ["software-asset-1"],
                  "differentiation": "相比现有工具增加了自定义报表",
                  "technicalValue": "可复用现有报表模块的渲染能力",
                  "estimatedComplexity": "中等：主要在导出与模板部分",
                  "risks": ["模板格式复杂度可能超预期"],
                  "evidence": {
                    "userNeed": ["U-E2"],
                    "userFit": ["U-E1"],
                    "reusableCapability": ["R1-E2"]
                  }
                }
              ]
            }
            """;

    private final StubAiGateway aiGateway = new StubAiGateway();

    private final DirectionDiscoveryExtraction extraction =
            new DirectionDiscoveryExtraction(aiGateway, new ObjectMapper());

    @Test
    void extractsDirectionProposalsFromTheGivenInputs() {
        aiGateway.respond(VALID_RESPONSE);

        List<DirectionProposal> proposals = extraction.extract(INPUTS);

        assertEquals(1, proposals.size());
        DirectionProposal proposal = proposals.get(0);
        assertEquals("个人记账 + 报表导出", proposal.title());
        assertEquals(
                List.of(new EvidenceReference("U-E2")),
                proposal.evidenceLinkage().userNeed());
        assertEquals(
                List.of(new EvidenceReference("U-E1")),
                proposal.evidenceLinkage().userFit());
        assertEquals(
                List.of(new EvidenceReference("R1-E2")),
                proposal.evidenceLinkage().reusableCapability());
    }

    @Test
    void sendsSystemInstructionAndStructuredContext() {
        aiGateway.respond(VALID_RESPONSE);

        extraction.extract(INPUTS);

        AiRequest request = aiGateway.lastRequest();
        assertEquals(AiResponseFormat.JSON, request.responseFormat());
        assertEquals(AiRole.SYSTEM, request.messages().get(0).role());
        assertTrue(request.messages().get(0).content().contains("json"),
                "提示词必须包含 json 字样：Provider 的 JSON 模式依赖它");
        assertEquals(AiRole.USER, request.messages().get(1).role());
    }

    /** 用户侧已确认的事实必须进入请求，模型才有依据谈「适合这个用户」。 */
    @Test
    void sendsEveryConfirmedUserProfileFact() {
        aiGateway.respond(VALID_RESPONSE);

        extraction.extract(INPUTS);

        String userMessage = aiGateway.lastRequest().messages().get(1).content();
        assertTrue(userMessage.contains("个人记账"));
        assertTrue(userMessage.contains("长期自己维护小工具"));
        assertTrue(userMessage.contains("现有工具的报表导出很麻烦"));
        assertTrue(userMessage.contains("Spring Boot"));
        assertTrue(userMessage.contains("做一个自己每天都会用的工具"));
        assertTrue(userMessage.contains("只能在业余时间推进"));
    }

    /** 资产侧已确认的事实同样必须进入请求：方向要基于已有能力，而不是凭空设想。 */
    @Test
    void sendsEveryConfirmedRepositoryProfileFact() {
        aiGateway.respond(VALID_RESPONSE);

        extraction.extract(INPUTS);

        String userMessage = aiGateway.lastRequest().messages().get(1).content();
        assertTrue(userMessage.contains("repository-profile-1"), "Repository Profile 身份");
        assertTrue(userMessage.contains("abc123"), "analyzedRevision");
        assertTrue(userMessage.contains("个人记账工具"), "purpose");
        assertTrue(userMessage.contains("报表导出"), "reusableAssets");
        assertTrue(userMessage.contains("没有自动化测试"), "limitations");
        assertTrue(userMessage.contains("图表组件库"), "第二个 Repository Profile");
        assertTrue(userMessage.contains("software-asset-2"), "第二个资产的身份");
    }

    /**
     * 已有 Evidence 与它们的临时引用必须一起给出：模型只能引用这里出现过的条目。
     */
    @Test
    void sendsExistingEvidenceWithTheirTemporaryReferences() {
        aiGateway.respond(VALID_RESPONSE);

        extraction.extract(INPUTS);

        String userMessage = aiGateway.lastRequest().messages().get(1).content();

        assertTrue(userMessage.contains("U-E1"), "用户侧第一条依据的引用");
        assertTrue(userMessage.contains("U-E2"), "用户侧第二条依据的引用");
        assertTrue(userMessage.contains("R1-E1"), "第 1 个 Profile 第一条依据的引用");
        assertTrue(userMessage.contains("R1-E2"));
        assertTrue(userMessage.contains("R2-E1"), "第 2 个 Profile 第一条依据的引用");

        assertTrue(userMessage.contains("用户对报表导出的不满"), "引用必须与依据的内容一起给出");
        assertTrue(userMessage.contains("已有报表渲染模块"));
        assertTrue(userMessage.contains("已有图表组件"));
    }

    /**
     * 模型无权决定的两项不进请求：它们既不由模型给出，也不需要模型看到系统当前的取值。
     */
    @Test
    void doesNotSendConfidenceOrConfirmed() {
        aiGateway.respond(VALID_RESPONSE);

        extraction.extract(INPUTS);

        String userMessage = aiGateway.lastRequest().messages().get(1).content();
        assertFalse(userMessage.contains("\"confidence\""), "请求里不得给出可信程度");
        assertFalse(userMessage.contains("\"confirmed\""), "请求里不得给出确认状态");
    }

    /**
     * 引用指向的是本次提示里的条目，不是 Evidence 的身份：本次没有发过的引用一律拒绝，
     * 因此模型无法凭一个看起来合理的字符串指向一条它没见过的依据。
     */
    @Test
    void rejectsUnknownEvidenceReferenceReturnedByTheModel() {
        aiGateway.respond(VALID_RESPONSE.replace("\"U-E2\"", "\"U-E7\""));

        assertThrows(AiGatewayException.class, () -> extraction.extract(INPUTS));
    }

    /** 候选资产同样只能来自本次提供的资产。 */
    @Test
    void rejectsCandidateAssetThatWasNotProvided() {
        aiGateway.respond(VALID_RESPONSE.replace("software-asset-1", "software-asset-999"));

        assertThrows(AiGatewayException.class, () -> extraction.extract(INPUTS));
    }

    @Test
    void rejectsMissingInputs() {
        aiGateway.respond(VALID_RESPONSE);

        assertThrows(IllegalArgumentException.class, () -> extraction.extract(null));

        assertEquals(0, aiGateway.callCount(), "输入不合法时不得调用 AI");
    }

    @Test
    void propagatesAiFailureWithoutProducingProposals() {
        aiGateway.failWith(new AiGatewayException("模型调用失败"));

        assertThrows(AiGatewayException.class, () -> extraction.extract(INPUTS));
        assertEquals(1, aiGateway.callCount());
    }

    /** 模型输出不符合约定时同样不产生结果：没有「部分解析成功」的提议。 */
    @Test
    void propagatesParseFailureWithoutProducingProposals() {
        aiGateway.respond("{\"directions\": []}".replace("[]", "[{}]"));

        assertThrows(AiGatewayException.class, () -> extraction.extract(INPUTS));
        assertEquals(1, aiGateway.callCount());
    }

    @Test
    void rejectsMissingDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new DirectionDiscoveryExtraction(null, new ObjectMapper()));
        assertThrows(IllegalArgumentException.class,
                () -> new DirectionDiscoveryExtraction(aiGateway, null));
    }

    /** AI Gateway 替身：记录收到的请求，并返回预设内容或抛出预设失败。 */
    private static final class StubAiGateway implements AiGateway {

        private final List<AiRequest> requests = new ArrayList<>();

        private String response;

        private RuntimeException failure;

        void respond(String rawResponse) {
            this.response = rawResponse;
            this.failure = null;
        }

        void failWith(RuntimeException exception) {
            this.failure = exception;
            this.response = null;
        }

        AiRequest lastRequest() {
            return requests.get(requests.size() - 1);
        }

        int callCount() {
            return requests.size();
        }

        @Override
        public String generate(AiRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
