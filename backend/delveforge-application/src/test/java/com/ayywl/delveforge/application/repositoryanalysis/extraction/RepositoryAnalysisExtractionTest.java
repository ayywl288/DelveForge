package com.ayywl.delveforge.application.repositoryanalysis.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Repository 分析提议的提取编排：材料交给 AI，解析结果作为提议返回。
 *
 * <p>AI 能力在 Port 边界用替身替代，因此本类不依赖 Spring、不访问网络，
 * 也不产生真实 LLM 调用（AGENTS.md §10.2、§10.6）。
 *
 * <p>失败的验证是「以异常结束、不返回任何结果」：本类只有 AiGateway 一个协作者，
 * 不持有 Repository、Aggregate 或持久化能力，因此 AI 失败与解析失败都不可能留下
 * 领域或持久化副作用。
 */
class RepositoryAnalysisExtractionTest {

    private static final List<RepositorySourceFile> FILES = List.of(
            new RepositorySourceFile("pom.xml", "<project>spring-boot</project>"),
            new RepositorySourceFile("src/main/App.java", "public class App {}"));

    private static final String VALID_RESPONSE = """
            {
              "purpose": "个人记账工具",
              "techStack": ["Java 21", "Spring Boot"],
              "modules": ["accounting"],
              "capabilities": ["记账"],
              "reusableAssets": ["报表导出"],
              "limitations": ["没有自动化测试"],
              "risks": ["模块耦合"],
              "evidence": [
                { "claim": "项目使用 Spring Boot", "sourceRef": "pom.xml" }
              ]
            }
            """;

    private final StubAiGateway aiGateway = new StubAiGateway();

    private final RepositoryAnalysisExtraction extraction =
            new RepositoryAnalysisExtraction(aiGateway, new ObjectMapper());

    @Test
    void extractsProposalFromRepositoryFiles() {
        aiGateway.respond(VALID_RESPONSE);

        RepositoryAnalysisProposal proposal = extraction.extract(FILES);

        assertEquals("个人记账工具", proposal.purpose());
        assertEquals(List.of("Java 21", "Spring Boot"), proposal.techStack());
        assertEquals(List.of("accounting"), proposal.modules());
        assertEquals(List.of("记账"), proposal.capabilities());
        assertEquals(List.of("报表导出"), proposal.reusableAssets());
        assertEquals(List.of("没有自动化测试"), proposal.limitations());
        assertEquals(List.of("模块耦合"), proposal.risks());
        assertEquals(
                List.of(new RepositoryEvidenceProposal("项目使用 Spring Boot", "pom.xml")),
                proposal.evidence());
    }

    /**
     * 请求里给出的是「相对路径 + 内容」：模型要依据文件内容作答，并按相对路径引用依据。
     * 宿主机绝对路径与 analyzedRevision 不属于请求内容。
     */
    @Test
    void sendsRepositoryFilesAsStructuredRequest() {
        aiGateway.respond(VALID_RESPONSE);

        extraction.extract(FILES);

        AiRequest request = aiGateway.lastRequest();
        assertEquals(AiResponseFormat.JSON, request.responseFormat());
        assertEquals(AiRole.SYSTEM, request.messages().get(0).role());
        assertTrue(request.messages().get(0).content().contains("json"),
                "提示词必须包含 json 字样：Provider 的 JSON 模式依赖它");
        assertEquals(AiRole.USER, request.messages().get(1).role());

        String userMessage = request.messages().get(1).content();
        assertTrue(userMessage.contains("pom.xml"));
        assertTrue(userMessage.contains("<project>spring-boot</project>"),
                "请求必须包含文件内容，而不只是路径");
        assertTrue(userMessage.contains("src/main/App.java"));

        assertFalse(userMessage.contains("\\"),
                "请求只携带相对路径，不含宿主机路径分隔形式");
    }

    /**
     * 依据必须指向本次真正交给模型的文件：指向材料之外的路径意味着这条依据无法定位到
     * 实际存在的代码或配置（DOMAIN_MODEL.md §3.6），提示词的要求必须由代码兜住。
     */
    @Test
    void rejectsEvidenceReferencingAFileThatWasNotSent() {
        aiGateway.respond(proposalWithEvidence("""
                [ { "claim": "项目使用 Spring Boot", "sourceRef": "some/nonexistent/file.java" } ]
                """));

        assertThrows(AiGatewayException.class, () -> extraction.extract(FILES));
    }

    /**
     * 只要有一条依据无法定位，整次分析就被拒绝，而不是丢掉那一条、保留其余部分：
     * 无法定位依据说明模型这次没有按材料作答，结论整体不可信。
     */
    @Test
    void rejectsWholeProposalWhenAnyEvidenceReferenceIsUnknown() {
        aiGateway.respond(proposalWithEvidence("""
                [
                  { "claim": "项目使用 Spring Boot", "sourceRef": "pom.xml" },
                  { "claim": "入口类很简单", "sourceRef": "src/main/App.java" },
                  { "claim": "存在构建脚本", "sourceRef": "build.gradle" }
                ]
                """));

        assertThrows(AiGatewayException.class, () -> extraction.extract(FILES));
    }

    /**
     * 匹配是精确的：{@code ./pom.xml} 与交给模型的 {@code pom.xml} 不是同一个字符串，
     * 因此同样被拒绝。本层没有关于「什么算同一个路径」的可靠依据，不做归一化。
     */
    @Test
    void requiresExactPathMatch() {
        aiGateway.respond(proposalWithEvidence("""
                [ { "claim": "项目使用 Spring Boot", "sourceRef": "./pom.xml" } ]
                """));

        assertThrows(AiGatewayException.class, () -> extraction.extract(FILES));
    }

    /**
     * 材料中嵌套路径的依据同样合法——校验的是「是否来自本次材料」，不是「是否在根目录」。
     */
    @Test
    void acceptsEvidenceForNestedFileThatWasSent() {
        aiGateway.respond(proposalWithEvidence("""
                [ { "claim": "入口类没有依赖", "sourceRef": "src/main/App.java" } ]
                """));

        RepositoryAnalysisProposal proposal = extraction.extract(FILES);

        assertEquals(
                List.of(new RepositoryEvidenceProposal("入口类没有依赖", "src/main/App.java")),
                proposal.evidence());
    }

    /**
     * 没有 Evidence 的提议是合法的：某个仓库可能确实得不出可定位的依据。
     */
    @Test
    void acceptsProposalWithoutEvidence() {
        aiGateway.respond(proposalWithEvidence("[]"));

        RepositoryAnalysisProposal proposal = extraction.extract(FILES);

        assertEquals(List.of(), proposal.evidence());
    }

    @Test
    void rejectsMissingOrEmptyMaterial() {
        aiGateway.respond(VALID_RESPONSE);

        assertThrows(IllegalArgumentException.class, () -> extraction.extract(null));
        assertThrows(IllegalArgumentException.class, () -> extraction.extract(List.of()));

        assertEquals(0, aiGateway.callCount(), "材料不合法时不得调用 AI");
    }

    @Test
    void rejectsMaterialContainingNullEntry() {
        List<RepositorySourceFile> withNull = new ArrayList<>();
        withNull.add(FILES.get(0));
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> extraction.extract(withNull));

        assertEquals(0, aiGateway.callCount(), "材料不合法时不得调用 AI");
    }

    /**
     * AI 调用失败不产生任何结果：异常向上传播，调用方拿不到提议。
     */
    @Test
    void propagatesAiFailureWithoutProducingAProposal() {
        aiGateway.failWith(new AiGatewayException("模型调用失败"));

        assertThrows(AiGatewayException.class, () -> extraction.extract(FILES));
        assertEquals(1, aiGateway.callCount());
    }

    /**
     * 模型输出不符合约定时同样不产生结果：没有「部分解析成功」的提议。
     */
    @Test
    void propagatesParseFailureWithoutProducingAProposal() {
        aiGateway.respond("{\"purpose\":\"只有一个字段\"}");

        assertThrows(AiGatewayException.class, () -> extraction.extract(FILES));
        assertEquals(1, aiGateway.callCount());
    }

    @Test
    void rejectsMissingDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryAnalysisExtraction(null, new ObjectMapper()));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryAnalysisExtraction(aiGateway, null));
    }

    /** 与 {@link #VALID_RESPONSE} 同构、只替换 evidence 部分的模型输出。 */
    private static String proposalWithEvidence(String evidenceJson) {
        return """
                {
                  "purpose": "个人记账工具",
                  "techStack": ["Java 21"],
                  "modules": [],
                  "capabilities": [],
                  "reusableAssets": [],
                  "limitations": [],
                  "risks": [],
                  "evidence": %s
                }
                """.formatted(evidenceJson);
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
