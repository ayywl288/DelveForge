package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 引用校验：只有命中本次调用的编号才会变成可信区域。 */
class RepositoryRegionProposalResolverTest {

    private final RepositoryRegionProposalResolver resolver =
            new RepositoryRegionProposalResolver();

    private final RepositoryRegionCatalog catalog = RegionFixtures.catalog();

    private RepositoryRegionReference referenceAt(RepositoryRegionCatalog source, int position) {
        return source.entries().get(position - 1).reference();
    }

    @Test
    void resolvesReferencesBackToRegionsInModelOrder() {
        RepositoryRegionSelection selection = resolver.resolve(
                new AiRegionSelectionProposal(List.of(
                        referenceAt(catalog, 3), referenceAt(catalog, 1))),
                catalog);

        assertEquals(List.of("web", "src"),
                selection.regions().stream().map(RepositoryRegion::pathPrefix).toList(),
                "顺序必须保持模型给出的分支优先级");
        assertEquals(RegionFixtures.REVISION, selection.analyzedRevision());
    }

    /**
     * 两份目录**都有各自的第一个区域**，因此都拥有一枚位置编号为 1 的引用。
     *
     * <p>这正是只比较裸编号时的漏洞：A 的第 1 个引用在这里会被静默接受，并把 A 的第 1 个区域
     * （src）换成 B 的第 1 个区域（svc）。
     */
    @Test
    void rejectsReferenceFromAnotherCatalogEvenWhenBothHaveAFirstRegion() {
        RepositoryRegionCatalog other = RepositoryRegionCatalog.of(
                RegionFixtures.REVISION,
                List.of(RegionFixtures.tree().region("svc").orElseThrow(),
                        RegionFixtures.tree().region("web").orElseThrow()));

        assertEquals("src", catalog.regions().get(0).pathPrefix());
        assertEquals("svc", other.regions().get(0).pathPrefix());

        assertThrows(AiGatewayException.class, () -> resolver.resolve(
                new AiRegionSelectionProposal(List.of(referenceAt(catalog, 1))),
                other),
                "另一个目录的第 1 个区域不得被当成本次调用的第 1 个区域");
    }

    /**
     * 内容完全相同的两次调用仍然是两次调用：调用身份不与内容挂钩。
     *
     * <p>用注入的作用域让这件事确定为真，不依赖随机性。
     */
    @Test
    void rejectsReferenceFromAnotherInvocationWithIdenticalContent() {
        RepositoryRegionCatalog first = RepositoryRegionCatalog.of(
                RegionFixtures.REVISION, catalog.regions(), () -> "aaaaaaaa");
        RepositoryRegionCatalog second = RepositoryRegionCatalog.of(
                RegionFixtures.REVISION, catalog.regions(), () -> "bbbbbbbb");

        assertThrows(AiGatewayException.class, () -> resolver.resolve(
                new AiRegionSelectionProposal(List.of(referenceAt(first, 1))),
                second),
                "上一次调用留下的响应不得被这一次接受");
        assertThrows(AiGatewayException.class, () -> resolver.resolve(
                new AiRegionSelectionProposal(List.of(referenceAt(second, 2))),
                first));
    }

    @Test
    void rejectsUnknownReference() {
        assertThrows(AiGatewayException.class, () -> resolver.resolve(
                new AiRegionSelectionProposal(
                        List.of(new RepositoryRegionReference("RR-00000000-99"))),
                catalog));
    }

    @Test
    void rejectsDuplicateReferences() {
        assertThrows(AiGatewayException.class, () -> resolver.resolve(
                new AiRegionSelectionProposal(List.of(
                        referenceAt(catalog, 1), referenceAt(catalog, 1))),
                catalog));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(null, catalog));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(new AiRegionSelectionProposal(
                        List.of(referenceAt(catalog, 1))), null));
    }

    @Test
    void failureProducesNoPartialSelection() {
        // 第一条合法、第二条越界：整次选择失败，不会只保留第一条。
        assertThrows(AiGatewayException.class, () -> resolver.resolve(
                new AiRegionSelectionProposal(List.of(
                        referenceAt(catalog, 1),
                        new RepositoryRegionReference("RR-00000000-42"))),
                catalog));
    }
}
