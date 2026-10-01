package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import static com.ayywl.delveforge.application.opportunitydiscovery.direction.DirectionDiscoveryFixtures.ACCOUNTING_ASSET_ID;
import static com.ayywl.delveforge.application.opportunitydiscovery.direction.DirectionDiscoveryFixtures.ACCOUNTING_BASIS_2;
import static com.ayywl.delveforge.application.opportunitydiscovery.direction.DirectionDiscoveryFixtures.ACCOUNTING_PROFILE_ID;
import static com.ayywl.delveforge.application.opportunitydiscovery.direction.DirectionDiscoveryFixtures.USER_BASIS_1;
import static com.ayywl.delveforge.application.opportunitydiscovery.direction.DirectionDiscoveryFixtures.USER_PROFILE_ID;
import static com.ayywl.delveforge.application.opportunitydiscovery.direction.DirectionDiscoveryFixtures.USER_PROFILE_REVISION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.opportunitydiscovery.direction.InMemoryDiscoveryRepositories.ProductDirectionRecorder;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class GetProductDirectionUseCaseTest {

    private static final ProductDirectionId DIRECTION_ID = new ProductDirectionId("direction-1");

    private final ProductDirectionRecorder repository = new ProductDirectionRecorder();

    private final GetProductDirectionUseCase useCase = new GetProductDirectionUseCase(repository);

    /**
     * 读取返回的是保存过的**那一条**方向：身份与全部内容都与写入时一致。
     *
     * <p>这里不比较对象引用。替身与真实存储一样，读取时返回独立的对象——真实实现从
     * 数据库重建方向，本来就不可能交回调用方当初传进去的那个实例。要求同一个实例，
     * 验证的是替身的实现方式，而不是这个 Use Case 的行为。
     */
    @Test
    void returnsStoredDirection() {
        ProductDirection stored = direction(ProductDirectionStatus.CANDIDATE);
        repository.save(stored);

        ProductDirection loaded = useCase.get(DIRECTION_ID);

        assertEquals(DIRECTION_ID, loaded.id());
        assertEquals(USER_PROFILE_ID, loaded.userProfileId());
        assertEquals(USER_PROFILE_REVISION, loaded.userProfileRevision());
        assertEquals(List.of(ACCOUNTING_PROFILE_ID), loaded.repositoryProfileIds());
        assertEquals(List.of(ACCOUNTING_ASSET_ID), loaded.candidateAssetIds());
        assertEquals(ProductDirectionStatus.CANDIDATE, loaded.status());
        assertEquals(stored.evidenceSupport(), loaded.evidenceSupport());
    }

    @Test
    void failsWhenDirectionDoesNotExist() {
        assertThrows(ProductDirectionNotFoundException.class,
                () -> useCase.get(new ProductDirectionId("unknown-direction")));
    }

    @Test
    void rejectsMissingRepository() {
        assertThrows(IllegalArgumentException.class, () -> new GetProductDirectionUseCase(null));
    }

    /**
     * 读取不过滤生命周期状态。
     *
     * <p>一条已经被拒绝或已经被取代的方向同样是已经发生的领域事实，它必须能被读回来
     * （§10.5、RULE-DOM-007）。若读取只看 {@code CANDIDATE}，这些方向会从接口上消失，
     * 而它们恰恰是最需要保留历史解释能力的那一批。
     */
    @Test
    void returnsDirectionsInEveryLifecycleStatus() {
        for (ProductDirectionStatus status : ProductDirectionStatus.values()) {
            ProductDirectionId id = new ProductDirectionId("direction-" + status);
            repository.save(direction(id, status));

            assertEquals(status, useCase.get(id).status());
        }
    }

    private static ProductDirection direction(ProductDirectionStatus status) {
        return direction(DIRECTION_ID, status);
    }

    private static ProductDirection direction(ProductDirectionId id, ProductDirectionStatus status) {
        DirectionEvidenceSupport support = new DirectionEvidenceSupport(
                List.of(USER_BASIS_1),
                List.of(USER_BASIS_1, ACCOUNTING_BASIS_2),
                List.of(ACCOUNTING_BASIS_2));

        return ProductDirection.reconstitute(
                id,
                USER_PROFILE_ID,
                USER_PROFILE_REVISION,
                List.of(ACCOUNTING_PROFILE_ID),
                "可导出的记账工具",
                "现有记账工具的报表导出很麻烦",
                "单用户桌面记账工具 + 自定义报表导出",
                "用户长期自己维护记账工具，且技术栈匹配",
                List.of(ACCOUNTING_ASSET_ID),
                "相比现有工具增加了自定义报表",
                "可复用现有报表渲染能力",
                "中等：主要在导出与模板部分",
                List.of(),
                support,
                status);
    }
}
