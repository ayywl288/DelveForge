package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 引用校验：只有命中本次 Catalog 的编号才会变成可信区域。 */
class RepositoryRegionProposalResolverTest {

    private final RepositoryRegionProposalResolver resolver =
            new RepositoryRegionProposalResolver();

    private final RepositoryRegionCatalog catalog = RegionFixtures.catalog();

    @Test
    void resolvesReferencesBackToRegionsInModelOrder() {
        RepositoryRegionSelection selection = resolver.resolve(
                new AiRegionSelectionProposal(List.of(
                        RepositoryRegionReference.of(3), RepositoryRegionReference.of(1))),
                catalog);

        assertEquals(List.of("web", "src"),
                selection.regions().stream().map(RepositoryRegion::pathPrefix).toList(),
                "顺序必须保持模型给出的分支优先级");
        assertEquals(RegionFixtures.REVISION, selection.analyzedRevision());
    }

    @Test
    void rejectsUnknownReference() {
        assertThrows(AiGatewayException.class, () -> resolver.resolve(
                new AiRegionSelectionProposal(
                        List.of(new RepositoryRegionReference("RR-99"))),
                catalog));
    }

    @Test
    void rejectsReferenceFromAnotherInvocationCatalog() {
        RepositoryRegionCatalog smaller = RepositoryRegionCatalog.of(
                RegionFixtures.REVISION, catalog.regions().subList(0, 1));

        assertThrows(AiGatewayException.class, () -> resolver.resolve(
                new AiRegionSelectionProposal(List.of(RepositoryRegionReference.of(3))),
                smaller),
                "另一个调用的 RR-3 不属于本次目录，必须失败");
    }

    @Test
    void rejectsDuplicateReferences() {
        assertThrows(AiGatewayException.class, () -> resolver.resolve(
                new AiRegionSelectionProposal(List.of(
                        RepositoryRegionReference.of(1), RepositoryRegionReference.of(1))),
                catalog));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(null, catalog));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(new AiRegionSelectionProposal(
                        List.of(RepositoryRegionReference.of(1))), null));
    }

    @Test
    void failureProducesNoPartialSelection() {
        // 第一条合法、第二条越界：整次选择失败，不会只保留第一条。
        assertThrows(AiGatewayException.class, () -> resolver.resolve(
                new AiRegionSelectionProposal(List.of(
                        RepositoryRegionReference.of(1),
                        new RepositoryRegionReference("RR-42"))),
                catalog));
    }
}
