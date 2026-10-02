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
    private static final String SCOPE = RegionFixtures.SCOPE_A;

    private final RepositoryRegionProposalParser parser =
            new RepositoryRegionProposalParser(new ObjectMapper(), MAX_SELECTED);

    private static String ref(int position) {
        return "RR-" + SCOPE + "-" + position;
    }

    @Test
    void parsesAndPreservesOrder() {
        AiRegionSelectionProposal proposal = parser.parse(
                "{\"regionRefs\":[\"" + ref(3) + "\",\"" + ref(1) + "\"]}");

        assertEquals(List.of(ref(3), ref(1)),
                proposal.regionRefs().stream()
                        .map(RepositoryRegionReference::value).toList(),
                "解析不得重排：顺序就是模型表达的分支优先级");
    }

    @Test
    void ignoresFieldsOutsideTheContract() {
        AiRegionSelectionProposal proposal = parser.parse(
                "{\"regionRefs\":[\"" + ref(1) + "\"],\"reason\":\"因为看起来重要\"}");

        assertEquals(1, proposal.regionRefs().size());
    }

    @Test
    void rejectsMissingField() {
        assertThrows(AiGatewayException.class, () -> parser.parse("{ }"));
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{\"regions\":[\"" + ref(1) + "\"]}"));
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{ \"regionRefs\": null }"));
    }

    @Test
    void rejectsNonArrayOrEmpty() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{\"regionRefs\":\"" + ref(1) + "\"}"));
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{\"regionRefs\":[]}"),
                "空选择没有意义");
    }

    @Test
    void rejectsMoreThanConfiguredMaximum() {
        RepositoryRegionProposalParser tight =
                new RepositoryRegionProposalParser(new ObjectMapper(), 2);

        assertThrows(AiGatewayException.class, () -> tight.parse(
                "{\"regionRefs\":[\"" + ref(1) + "\",\"" + ref(2) + "\",\"" + ref(3) + "\"]}"),
                "超过配置的选择上限必须失败，而不是截断到上限");
    }

    @Test
    void rejectsMalformedReferences() {
        for (String bad : List.of(
                "RR-1",              // 裸序号：没有作用域，正是必须拒绝的旧形式
                "RR-" + SCOPE + "-0",              // 位置从 1 开始
                "RR-" + SCOPE + "-01",             // 前置零
                "RR-" + SCOPE.toUpperCase() + "-1", // 作用域必须小写
                "RR-3f1a9c02-1",                   // 作用域被截短（碰撞空间只有 2^32）
                "RR-" + SCOPE + "0-1",             // 作用域过长
                "RR-", "RF-1", "src/main/java", "")) {
            assertThrows(AiGatewayException.class,
                    () -> parser.parse("{\"regionRefs\":[\"" + bad + "\"]}"),
                    "格式不合约定的引用必须拒绝: " + bad);
        }
    }

    @Test
    void rejectsNonTextualElements() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{\"regionRefs\":[1]}"));
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{\"regionRefs\":[null]}"));
    }

    @Test
    void rejectsDuplicateReferencesWithinTheProposal() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                "{\"regionRefs\":[\"" + ref(1) + "\",\"" + ref(1) + "\"]}"),
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
