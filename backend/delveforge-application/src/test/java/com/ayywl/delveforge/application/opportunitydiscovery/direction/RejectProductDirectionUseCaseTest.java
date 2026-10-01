package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.opportunitydiscovery.direction.InMemoryDiscoveryRepositories.ProductDirectionRecorder;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionStatusConflictException;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStateException;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证「用户明确拒绝方向」的编排。
 *
 * <p>只有 {@code CANDIDATE → REJECTED} 是合法的（§6.2）。拒绝不涉及 INV-D09，
 * 因此它不读取任何别的方向，也不写第二条。
 */
class RejectProductDirectionUseCaseTest {

    private static final ProductDirectionId TARGET_ID = new ProductDirectionId("direction-1");

    private final ProductDirectionRecorder repository = new ProductDirectionRecorder();

    private final RejectProductDirectionUseCase useCase =
            new RejectProductDirectionUseCase(repository);

    @Test
    void rejectsCandidate() {
        repository.seed(candidate(TARGET_ID));

        ProductDirection rejected = useCase.reject(TARGET_ID);

        assertEquals(ProductDirectionStatus.REJECTED, rejected.status());
        assertEquals(TARGET_ID, rejected.id());
        assertEquals(1, repository.transitionCalls(), "拒绝只写它自己这一条");
        assertEquals(0, repository.singleSaveCalls(), "状态变化不该走普通的 save");
        assertEquals(0, repository.batchCalls(), "拒绝不涉及取代，不该走整批");
        assertEquals(ProductDirectionStatus.REJECTED,
                repository.stored(TARGET_ID).orElseThrow().status());
    }

    /**
     * 拒绝只改状态：内容、分析来源与依据都原样保留（§10.5、RULE-DOM-007）。
     */
    @Test
    void keepsDiscoveryProvenanceAndContent() {
        repository.seed(candidate(TARGET_ID));

        useCase.reject(TARGET_ID);

        ProductDirection stored = repository.stored(TARGET_ID).orElseThrow();
        assertEquals(USER_PROFILE_ID, stored.userProfileId());
        assertEquals(USER_PROFILE_REVISION, stored.userProfileRevision());
        assertEquals(List.of(REPOSITORY_PROFILE_ID), stored.repositoryProfileIds());
        assertEquals(List.of(ASSET_ID), stored.candidateAssetIds());
        assertEquals(TITLE, stored.title());
        assertEquals(List.of(BASIS), stored.evidenceSupport().allBases());
    }

    /**
     * 被拒绝的方向保留下来，不因为被放弃而被删除——它仍然回答「当初为什么提出它」。
     */
    @Test
    void keepsTheRejectedDirectionReadable() {
        repository.seed(candidate(TARGET_ID));

        useCase.reject(TARGET_ID);

        assertEquals(TITLE, repository.findById(TARGET_ID).orElseThrow().title());
        assertEquals(ProductDirectionStatus.REJECTED,
                repository.findById(TARGET_ID).orElseThrow().status());
    }

    @Test
    void rejectsUnknownDirectionWithoutWritingAnything() {
        assertThrows(ProductDirectionNotFoundException.class,
                () -> useCase.reject(new ProductDirectionId("unknown-direction")));

        assertNothingWasWritten();
    }

    /**
     * 已经 SELECTED 的方向不能直接被拒绝。「不想继续用这个方向了」的正确表达是选择另一个
     * 候选方向，由它把当前方向变成 SUPERSEDED（§6.2）。
     */
    @Test
    void rejectsRejectingASelectedDirection() {
        ProductDirection selected = candidate(TARGET_ID);
        selected.select();
        repository.seed(selected);

        assertThrows(ProductDirectionStateException.class, () -> useCase.reject(TARGET_ID));

        assertNothingWasWritten();
        assertEquals(ProductDirectionStatus.SELECTED,
                repository.stored(TARGET_ID).orElseThrow().status());
    }

    @Test
    void rejectsRejectingAnAlreadyRejectedDirection() {
        ProductDirection rejected = candidate(TARGET_ID);
        rejected.reject();
        repository.seed(rejected);

        assertThrows(ProductDirectionStateException.class, () -> useCase.reject(TARGET_ID));

        assertNothingWasWritten();
    }

    @Test
    void rejectsRejectingASupersededDirection() {
        ProductDirection superseded = candidate(TARGET_ID);
        superseded.select();
        superseded.supersede();
        repository.seed(superseded);

        assertThrows(ProductDirectionStateException.class, () -> useCase.reject(TARGET_ID));

        assertNothingWasWritten();
    }

    /**
     * 拒绝不读取当前 SELECTED 方向：它不产生新的当前方向，也就与 INV-D09 无关。
     */
    @Test
    void doesNotConsultTheCurrentSelectedDirection() {
        repository.seed(candidate(TARGET_ID));

        useCase.reject(TARGET_ID);

        assertEquals(0, repository.currentSelectedCalls(),
                "拒绝不需要知道当前是哪个方向");
    }

    /**
     * 这次拒绝所依据的状态在写入时已经变化（另一个请求推进了同一条方向）时，
     * 冲突原样向上传递，且不留下任何写入。
     *
     * <p>它是持久化层的条件更新报出来的，编排层不吞掉、也不改写成别的失败。
     */
    @Test
    void propagatesStatusConflictRaisedByPersistence() {
        repository.seed(candidate(TARGET_ID));
        repository.failTransitionsWith(new ProductDirectionStatusConflictException(
                TARGET_ID, ProductDirectionStatus.CANDIDATE));

        assertThrows(ProductDirectionStatusConflictException.class,
                () -> useCase.reject(TARGET_ID));

        assertEquals(1, repository.transitionCalls(), "确实尝试过写入");
        assertEquals(0, repository.singleSaveCalls());
        assertEquals(ProductDirectionStatus.CANDIDATE,
                repository.stored(TARGET_ID).orElseThrow().status());
    }

    @Test
    void rejectsMissingIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> useCase.reject(null));

        assertNothingWasWritten();
    }

    @Test
    void rejectsMissingRepository() {
        assertThrows(IllegalArgumentException.class,
                () -> new RejectProductDirectionUseCase(null));
    }

    private void assertNothingWasWritten() {
        assertEquals(0, repository.singleSaveCalls(), "不该发生单条保存");
        assertEquals(0, repository.batchCalls(), "不该发生整批保存");
        assertEquals(0, repository.transitionCalls(), "不该发生生命周期转换写入");
    }

    private static final UserProfileId USER_PROFILE_ID = new UserProfileId("user-profile-1");

    private static final int USER_PROFILE_REVISION = 3;

    private static final RepositoryProfileId REPOSITORY_PROFILE_ID =
            new RepositoryProfileId("repository-profile-1");

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final String TITLE = "个人记账 + 报表导出";

    private static final Evidence EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "user-profile-1#interests", "用户长期关注记账工具",
            0.8, true);

    private static final EvidenceBasis BASIS = new EvidenceBasis(
            EVIDENCE, new UserProfileEvidenceOrigin(USER_PROFILE_ID, USER_PROFILE_REVISION));

    private static final DirectionEvidenceSupport SUPPORT =
            new DirectionEvidenceSupport(List.of(BASIS), List.of(BASIS), List.of(BASIS));

    private static ProductDirection candidate(ProductDirectionId id) {
        return ProductDirection.create(
                id,
                USER_PROFILE_ID,
                USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                TITLE,
                "现有记账工具缺少可导出的报表",
                "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配",
                List.of(ASSET_ID),
                "相比现有工具增加了自定义报表",
                "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分",
                List.of("模板格式复杂度可能超预期"),
                SUPPORT);
    }
}
