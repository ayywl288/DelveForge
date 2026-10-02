package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 引用校验：只有命中本次目录的编号才会变成可信区域。 */
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
     * <p>这正是旧实现的漏洞：裸编号 {@code RR-1} 在这里会被静默接受，并把 A 的第 1 个区域
     * （src）换成了 B 的第 1 个区域（svc）。引用带上作用域之后，两者在字符串层面就不同。
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

    @Test
    void rejectsReferenceFromAnotherRevisionEvenWithSameRegions() {
        RepositoryRegionCatalog sameRegionsOtherRevision = RepositoryRegionCatalog.of(
                "another-revision", catalog.regions());

        assertThrows(AiGatewayException.class, () -> resolver.resolve(
                new AiRegionSelectionProposal(List.of(referenceAt(catalog, 1))),
                sameRegionsOtherRevision),
                "revision 不同即不是同一次导航，引用不可互换");
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
