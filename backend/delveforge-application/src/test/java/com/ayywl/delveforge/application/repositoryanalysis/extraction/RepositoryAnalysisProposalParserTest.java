package com.ayywl.delveforge.application.repositoryanalysis.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 输出的解析：只接受「恰好一个、字段完整、结构符合约定的 json 对象」。
 *
 * <p>结构不合法意味着模型没有按要求作答，属于外部 AI 能力失败，
 * 因此统一是 {@link AiGatewayException}。
 */
class RepositoryAnalysisProposalParserTest {

    private static final String FULL_PROPOSAL = """
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

    private final RepositoryAnalysisProposalParser parser =
            new RepositoryAnalysisProposalParser(new ObjectMapper());

    @Test
    void parsesFullProposal() {
        RepositoryAnalysisProposal proposal = parser.parse(FULL_PROPOSAL);

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
     * 某一区确实没有内容时用空数组表达；这不等于省略该字段。
     */
    @Test
    void acceptsEmptySections() {
        RepositoryAnalysisProposal proposal = parser.parse("""
                {
                  "purpose": "一个很小的脚本",
                  "techStack": [],
                  "modules": [],
                  "capabilities": [],
                  "reusableAssets": [],
                  "limitations": [],
                  "risks": [],
                  "evidence": []
                }
                """);

        assertEquals(List.of(), proposal.techStack());
        assertEquals(List.of(), proposal.evidence());
        assertEquals("一个很小的脚本", proposal.purpose());
    }

    /**
     * 与 User Profile 提议不同：这里没有「上一版 Profile」可以合并，
     * 字段缺失只说明模型没有回答，不能被当成「该区为空」。
     */
    @Test
    void rejectsProposalWithMissingFields() {
        assertThrows(AiGatewayException.class, () -> parser.parse("""
                {
                  "purpose": "个人记账工具",
                  "techStack": ["Java 21"],
                  "modules": [],
                  "capabilities": [],
                  "reusableAssets": [],
                  "limitations": []
                }
                """));
    }

    @Test
    void rejectsProposalWithNullField() {
        assertThrows(AiGatewayException.class, () -> parser.parse("""
                {
                  "purpose": null,
                  "techStack": [], "modules": [], "capabilities": [],
                  "reusableAssets": [], "limitations": [], "risks": [], "evidence": []
                }
                """));
    }

    @Test
    void rejectsBlankPurpose() {
        assertThrows(AiGatewayException.class, () -> parser.parse("""
                {
                  "purpose": "   ",
                  "techStack": [], "modules": [], "capabilities": [],
                  "reusableAssets": [], "limitations": [], "risks": [], "evidence": []
                }
                """));
    }

    @Test
    void rejectsFieldWithWrongType() {
        assertThrows(AiGatewayException.class, () -> parser.parse("""
                {
                  "purpose": ["个人记账工具"],
                  "techStack": [], "modules": [], "capabilities": [],
                  "reusableAssets": [], "limitations": [], "risks": [], "evidence": []
                }
                """));

        assertThrows(AiGatewayException.class, () -> parser.parse("""
                {
                  "purpose": "个人记账工具",
                  "techStack": "Java 21", "modules": [], "capabilities": [],
                  "reusableAssets": [], "limitations": [], "risks": [], "evidence": []
                }
                """));
    }

    @Test
    void rejectsSectionWithInvalidElement() {
        assertThrows(AiGatewayException.class, () -> parser.parse("""
                {
                  "purpose": "个人记账工具",
                  "techStack": ["Java 21", 21], "modules": [], "capabilities": [],
                  "reusableAssets": [], "limitations": [], "risks": [], "evidence": []
                }
                """));

        assertThrows(AiGatewayException.class, () -> parser.parse("""
                {
                  "purpose": "个人记账工具",
                  "techStack": ["Java 21", " "], "modules": [], "capabilities": [],
                  "reusableAssets": [], "limitations": [], "risks": [], "evidence": []
                }
                """));
    }

    @Test
    void rejectsInvalidEvidence() {
        // 不是对象
        assertThrows(AiGatewayException.class, () -> parser.parse(proposalWithEvidence("[\"pom.xml\"]")));
        // 缺少 sourceRef
        assertThrows(AiGatewayException.class,
                () -> parser.parse(proposalWithEvidence("[{\"claim\":\"使用 Spring Boot\"}]")));
        // 缺少 claim
        assertThrows(AiGatewayException.class,
                () -> parser.parse(proposalWithEvidence("[{\"sourceRef\":\"pom.xml\"}]")));
        // sourceRef 不是字符串
        assertThrows(AiGatewayException.class, () -> parser.parse(proposalWithEvidence(
                "[{\"claim\":\"使用 Spring Boot\",\"sourceRef\":3}]")));
        // sourceRef 为空
        assertThrows(AiGatewayException.class, () -> parser.parse(proposalWithEvidence(
                "[{\"claim\":\"使用 Spring Boot\",\"sourceRef\":\"  \"}]")));
    }

    /**
     * 回归：json 对象之后的解释文字必须被拒绝，不能只读第一个值就接受。
     */
    @Test
    void rejectsTrailingTextAfterJsonObject() {
        assertThrows(AiGatewayException.class, () -> parser.parse(FULL_PROPOSAL + " 以上是分析结果"));
    }

    @Test
    void rejectsSecondJsonValue() {
        assertThrows(AiGatewayException.class, () -> parser.parse(FULL_PROPOSAL + FULL_PROPOSAL));
    }

    @Test
    void rejectsContentThatIsNotAJsonObject() {
        assertThrows(AiGatewayException.class, () -> parser.parse("这不是 json"));
        assertThrows(AiGatewayException.class, () -> parser.parse("[]"));
        assertThrows(AiGatewayException.class, () -> parser.parse("   "));
        assertThrows(AiGatewayException.class, () -> parser.parse(null));
    }

    /**
     * 约定之外的字段不参与解析，也不会让整次分析失败：系统只读取契约内的字段。
     */
    @Test
    void ignoresFieldsThatAreNotPartOfTheContract() {
        RepositoryAnalysisProposal proposal = parser.parse("""
                {
                  "purpose": "个人记账工具",
                  "techStack": [], "modules": [], "capabilities": [],
                  "reusableAssets": [], "limitations": [], "risks": [],
                  "evidence": [],
                  "overallAssessment": "这个项目很有潜力"
                }
                """);

        assertEquals("个人记账工具", proposal.purpose());
    }

    private static String proposalWithEvidence(String evidenceJson) {
        return """
                {
                  "purpose": "个人记账工具",
                  "techStack": [], "modules": [], "capabilities": [],
                  "reusableAssets": [], "limitations": [], "risks": [],
                  "evidence": %s
                }
                """.formatted(evidenceJson);
    }
}
