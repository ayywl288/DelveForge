package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 区域选择提议的严格解析：形状不合约定即拒绝，不做补全。 */
class RepositoryRegionProposalParserTest {

    private static final int MAX_SELECTED = 6;

    private final RepositoryRegionProposalParser parser =
            new RepositoryRegionProposalParser(new ObjectMapper(), MAX_SELECTED);

    @Test
    void parsesAndPreservesOrder() {
        AiRegionSelectionProposal proposal = parser.parse("""
                { "regionRefs": ["RR-3", "RR-1"] }
                """);

        assertEquals(List.of("RR-3", "RR-1"),
                proposal.regionRefs().stream()
                        .map(RepositoryRegionReference::value).toList(),
                "解析不得重排：顺序就是模型表达的分支优先级");
    }

    @Test
    void ignoresFieldsOutsideTheContract() {
        AiRegionSelectionProposal proposal = parser.parse("""
                { "regionRefs": ["RR-1"], "reason": "因为看起来重要" }
                """);

        assertEquals(1, proposal.regionRefs().size());
    }

    @Test
    void rejectsMissingField() {
        assertThrows(AiGatewayException.class, () -> parser.parse("{ }"));
        assertThrows(AiGatewayException.class, () -> parser.parse("""
                { "regions": ["RR-1"] }
                """));
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{ \"regionRefs\": null }"));
    }

    @Test
    void rejectsNonArrayOrEmpty() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{ \"regionRefs\": \"RR-1\" }"));
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{ \"regionRefs\": [] }"),
                "空选择没有意义");
    }

    @Test
    void rejectsMoreThanConfiguredMaximum() {
        RepositoryRegionProposalParser tight =
                new RepositoryRegionProposalParser(new ObjectMapper(), 2);

        assertThrows(AiGatewayException.class,
                () -> tight.parse("{ \"regionRefs\": [\"RR-1\", \"RR-2\", \"RR-3\"] }"),
                "超过配置的选择上限必须失败，而不是截断到上限");
    }

    @Test
    void rejectsMalformedReferences() {
        for (String bad : List.of("RR-0", "RR-01", "RR-", "RF-1", "src/main/java", "")) {
            assertThrows(AiGatewayException.class,
                    () -> parser.parse("{ \"regionRefs\": [\"" + bad + "\"] }"),
                    "格式不合约定的引用必须拒绝: " + bad);
        }
    }

    @Test
    void rejectsNonTextualElements() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{ \"regionRefs\": [1] }"));
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{ \"regionRefs\": [null] }"));
    }

    @Test
    void rejectsDuplicateReferencesWithinTheProposal() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{ \"regionRefs\": [\"RR-1\", \"RR-1\"] }"),
                "重复引用既不表达额外优先级也不表达额外范围，静默去重会把它藏起来");
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryRegionProposalParser(new ObjectMapper(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryRegionProposalParser(null, MAX_SELECTED));
    }
}
