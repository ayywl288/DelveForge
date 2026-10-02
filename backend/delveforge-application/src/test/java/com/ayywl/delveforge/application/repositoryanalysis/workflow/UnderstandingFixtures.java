package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapBuilder;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryMaterialBudget;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadExecutor;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadPlanner;
import com.ayywl.delveforge.application.repositoryanalysis.region.RegionNavigationLimits;
import com.ayywl.delveforge.application.repositoryanalysis.region.RegionRecursionBudget;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryBranchScoutRunner;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryRegionNavigator;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryRegionScoutExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.region.ScoutCallBudget;
import com.ayywl.delveforge.application.repositoryanalysis.scout.FileCatalogPayload;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutExtraction;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 组装一条完整的理解链路，供 workflow 包的测试复用。
 *
 * <p>两条 Scout 路径的组件都在里面，因此同一份夹具既能构造「小仓库旁路分层」的场景，
 * 也能构造「超出预算走分层」的场景——两者的差别只有 {@code maxCatalogBytes} 一个取值。
 *
 * <p>文件目录的字节门槛同时给到 {@code RepositoryUnderstanding} 与
 * {@code RepositoryRegionNavigator}，与生产装配一致：两个门槛必须同源，
 * 否则分层可能交出一个超过 flat 上限的分支目录。
 */
final class UnderstandingFixtures {

    /** Region 目录（目录描述符载荷）的上限，与文件目录不是同一个量。 */
    static final int REGION_CATALOG_BYTES = 65_536;

    private UnderstandingFixtures() {
    }

    static RepositoryUnderstanding understanding(AiGateway gateway,
                                                 WorkspaceReadPort workspace,
                                                 RepositoryMaterialBudget foundation,
                                                 RepositoryMaterialBudget targetedSource,
                                                 int maxCatalogBytes) {
        return understanding(gateway, workspace, foundation, targetedSource, maxCatalogBytes,
                new RegionRecursionBudget(8, 12), new ScoutCallBudget(18));
    }

    static RepositoryUnderstanding understanding(AiGateway gateway,
                                                 WorkspaceReadPort workspace,
                                                 RepositoryMaterialBudget foundation,
                                                 RepositoryMaterialBudget targetedSource,
                                                 int maxCatalogBytes,
                                                 RegionRecursionBudget regionBudget,
                                                 ScoutCallBudget scoutCallBudget) {
        ObjectMapper objectMapper = new ObjectMapper();
        RepositoryScoutExtraction fileScout =
                new RepositoryScoutExtraction(gateway, objectMapper);
        RepositoryRegionScoutExtraction regionScout = new RepositoryRegionScoutExtraction(
                gateway, objectMapper, new RegionNavigationLimits(REGION_CATALOG_BYTES, 6));
        RepositoryRegionNavigator navigator = new RepositoryRegionNavigator(
                regionScout,
                new FileCatalogPayload(objectMapper),
                maxCatalogBytes,
                regionBudget,
                scoutCallBudget);
        RepositoryBranchScoutRunner branchRunner = new RepositoryBranchScoutRunner(
                fileScout, regionBudget, scoutCallBudget);

        return new RepositoryUnderstanding(
                new RepositoryMapBuilder(workspace),
                fileScout,
                navigator,
                branchRunner,
                new RepositoryReadPlanner(foundation, targetedSource),
                new RepositoryReadExecutor(workspace, foundation, targetedSource),
                maxCatalogBytes);
    }
}
