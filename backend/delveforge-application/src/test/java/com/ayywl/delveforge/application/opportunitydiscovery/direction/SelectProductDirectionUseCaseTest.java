package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.opportunitydiscovery.direction.InMemoryDiscoveryRepositories.ProductDirectionRecorder;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionIntegrityConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionSelectionConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionStatusConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionTransition;
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
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;

/**
 * 验证「用户明确选择方向」的编排，以及 INV-D09 的跨 Aggregate 协调。
 *
 * <p>Persistence 在 Port 边界用替身替代（AGENTS.md §10.2），因此本类不依赖 Spring 与 SQLite。
 * 替身按写入时保存快照，所以「失败不留下持久化副作用」在这里是可验证的，而不是靠约定。
 *
 * <p>存储层的唯一约束与事务回滚属于 Infrastructure，由 SQLite 的集成测试覆盖。
 */
class SelectProductDirectionUseCaseTest {

    private static final ProductDirectionId SELECTED_ID = new ProductDirectionId("direction-1");

    private static final ProductDirectionId TARGET_ID = new ProductDirectionId("direction-2");

    private final ProductDirectionRecorder repository = new ProductDirectionRecorder();

    private final SelectProductDirectionUseCase useCase =
            new SelectProductDirectionUseCase(repository, new EvolutionPlanRepository() {
                public void save(EvolutionPlan plan) { throw new UnsupportedOperationException(); }
                public java.util.Optional<EvolutionPlan> findById(EvolutionPlanId id) { return java.util.Optional.empty(); }
                public List<EvolutionPlan> findByProductDirectionId(ProductDirectionId id) { return List.of(); }
            }, new EvolutionLifecycleCommitPort() {
                public void commitActivation(EvolutionPlan plan, WorkingCopy copy, SoftwareAsset asset) {
                    throw new UnsupportedOperationException();
                }
                public void commitDirectionSelection(List<ProductDirectionTransition> directions,
                        List<EvolutionPlanTransition> plans) {
                    assertTrue(plans.isEmpty());
                    repository.saveTransitions(directions);
                }
            });

    // ---------------------------------------------------------------------
    // 当前没有 SELECTED 方向
    // ---------------------------------------------------------------------

    /**
     * 没有当前方向时就是一次普通的状态转移：写入一次，且不是整批。
     *
     * <p>「恰好一次保存」只能靠观察调用验证——先保存后校验、或保存两次，
     * 最终状态是一样的。
     */
    @Test
    void selectsCandidateWhenNothingIsSelectedYet() {
        repository.seed(candidate(TARGET_ID));

        ProductDirection selected = useCase.select(TARGET_ID);

        assertEquals(ProductDirectionStatus.SELECTED, selected.status());
        assertEquals(TARGET_ID, selected.id(), "返回的应当是这次被选中的那个方向");
        assertEquals(1, repository.transitionCalls(), "只应当发生一次转换写入");
        assertEquals(0, repository.singleSaveCalls(), "状态变化不该走普通的 save");
        assertEquals(0, repository.batchCalls(), "没有需要取代的方向，不该走整批");
        assertEquals(ProductDirectionStatus.SELECTED,
                repository.stored(TARGET_ID).orElseThrow().status());
    }

    // ---------------------------------------------------------------------
    // 切换方向
    // ---------------------------------------------------------------------

    /**
     * 切换是一次业务操作：原方向离开 SELECTED，新方向进入 SELECTED，两条一起写。
     */
    @Test
    void supersedesThePreviousDirectionAndSelectsTheTargetInOneBatch() {
        repository.seed(selected(SELECTED_ID));
        repository.seed(candidate(TARGET_ID));

        ProductDirection selected = useCase.select(TARGET_ID);

        assertEquals(ProductDirectionStatus.SELECTED, selected.status());
        assertEquals(0, repository.singleSaveCalls(), "切换不该拆成单条保存");
        assertEquals(1, repository.transitionCalls(), "切换必须是一次整批写入");

        assertEquals(ProductDirectionStatus.SUPERSEDED,
                repository.stored(SELECTED_ID).orElseThrow().status());
        assertEquals(ProductDirectionStatus.SELECTED,
                repository.stored(TARGET_ID).orElseThrow().status());
    }

    /**
     * 批次内的顺序就是语义：原方向必须先离开 SELECTED，新方向才能进入——存储层只允许
     * 存在一个当前 SELECTED 方向，反过来写会在中间态撞上唯一约束（见 SQLite 集成测试）。
     */
    @Test
    void writesThePreviousDirectionBeforeTheTarget() {
        repository.seed(selected(SELECTED_ID));
        repository.seed(candidate(TARGET_ID));

        useCase.select(TARGET_ID);

        List<List<ProductDirectionTransition>> batches = repository.transitionBatches();
        assertEquals(1, batches.size());
        assertEquals(List.of(SELECTED_ID, TARGET_ID),
                batches.get(0).stream().map(t -> t.direction().id()).toList(),
                "切换批次里原方向必须排在目标方向之前");
    }

    /**
     * 状态变化只改状态：两个方向的 discovery basis 与推荐内容都原样保留（§10.5）。
     */
    @Test
    void keepsDiscoveryProvenanceAndContentOfBothDirections() {
        repository.seed(selected(SELECTED_ID));
        repository.seed(candidate(TARGET_ID));

        useCase.select(TARGET_ID);

        ProductDirection storedPrevious = repository.stored(SELECTED_ID).orElseThrow();
        ProductDirection storedTarget = repository.stored(TARGET_ID).orElseThrow();

        for (ProductDirection direction : List.of(storedPrevious, storedTarget)) {
            assertEquals(USER_PROFILE_ID, direction.userProfileId());
            assertEquals(USER_PROFILE_REVISION, direction.userProfileRevision());
            assertEquals(List.of(REPOSITORY_PROFILE_ID), direction.repositoryProfileIds());
            assertEquals(List.of(ASSET_ID), direction.candidateAssetIds());
            assertEquals(TITLE, direction.title());
            assertEquals(List.of("模板格式复杂度可能超预期"), direction.risks());
            assertEquals(List.of(BASIS), direction.evidenceSupport().allBases());
        }
        assertEquals(ProductDirectionStatus.SUPERSEDED, storedPrevious.status());
        assertEquals(ProductDirectionStatus.SELECTED, storedTarget.status());
    }

    /**
     * 被取代的方向不会被删除：它仍然读得回来，只是不再进入 Evolution Planning（§10.5）。
     */
    @Test
    void keepsTheSupersededDirectionReadable() {
        repository.seed(selected(SELECTED_ID));
        repository.seed(candidate(TARGET_ID));

        useCase.select(TARGET_ID);

        assertTrue(repository.stored(SELECTED_ID).isPresent(),
                "原方向必须继续存在，而不是被删除");
        assertEquals(TITLE, repository.stored(SELECTED_ID).orElseThrow().title());
    }

    // ---------------------------------------------------------------------
    // 失败：都不得留下持久化副作用
    // ---------------------------------------------------------------------

    @Test
    void rejectsUnknownDirectionWithoutWritingAnything() {
        repository.seed(selected(SELECTED_ID));

        assertThrows(ProductDirectionNotFoundException.class,
                () -> useCase.select(new ProductDirectionId("unknown-direction")));

        assertNothingWasWritten();
        assertEquals(ProductDirectionStatus.SELECTED,
                repository.stored(SELECTED_ID).orElseThrow().status(),
                "原方向不得被提前取代");
    }

    /**
     * 目标已经是 SELECTED 时，它自己就是那个「当前方向」。这必须被拒绝，而不是被当成
     * 一次「切换到它自己」——那会让 select 在同一个方向上演一次自我取代。
     */
    @Test
    void rejectsSelectingAnAlreadySelectedDirection() {
        repository.seed(selected(TARGET_ID));

        assertThrows(ProductDirectionStateException.class, () -> useCase.select(TARGET_ID));

        assertNothingWasWritten();
        assertEquals(ProductDirectionStatus.SELECTED,
                repository.stored(TARGET_ID).orElseThrow().status());
    }

    @Test
    void rejectsSelectingARejectedDirection() {
        ProductDirection rejected = candidate(TARGET_ID);
        rejected.reject();
        repository.seed(rejected);
        repository.seed(selected(SELECTED_ID));

        assertThrows(ProductDirectionStateException.class, () -> useCase.select(TARGET_ID));

        assertNothingWasWritten();
        assertEquals(ProductDirectionStatus.SELECTED,
                repository.stored(SELECTED_ID).orElseThrow().status(),
                "目标状态不合法时不得先动原方向");
    }

    @Test
    void rejectsSelectingASupersededDirection() {
        ProductDirection superseded = candidate(TARGET_ID);
        superseded.select();
        superseded.supersede();
        repository.seed(superseded);

        assertThrows(ProductDirectionStateException.class, () -> useCase.select(TARGET_ID));

        assertNothingWasWritten();
    }

    /**
     * 存储里多于一条当前 SELECTED 方向时，查询本身失败——不挑一条，也不继续选择。
     */
    @Test
    void propagatesCorruptedCurrentSelectionWithoutWritingAnything() {
        repository.seed(selected(new ProductDirectionId("direction-corrupted-1")));
        repository.seed(selected(new ProductDirectionId("direction-corrupted-2")));
        repository.seed(candidate(TARGET_ID));

        assertThrows(ProductDirectionIntegrityConflictException.class,
                () -> useCase.select(TARGET_ID));

        assertNothingWasWritten();
        assertEquals(ProductDirectionStatus.CANDIDATE,
                repository.stored(TARGET_ID).orElseThrow().status());
    }

    /**
     * 整批写入失败时整个切换不生效：原方向仍然是 SELECTED，目标方向仍然是 CANDIDATE。
     *
     * <p>真实的事务回滚由 SQLite 集成测试覆盖；这里验证的是编排层不吞掉失败、
     * 也不在失败之后补一次写入。
     */
    @Test
    void leavesBothDirectionsUntouchedWhenTheSwitchCannotBePersisted() {
        repository.seed(selected(SELECTED_ID));
        repository.seed(candidate(TARGET_ID));
        repository.failTransitionsWith(new IllegalStateException("写入失败"));

        assertThrows(IllegalStateException.class, () -> useCase.select(TARGET_ID));

        assertEquals(1, repository.transitionCalls(), "失败后不得重试或补写");
        assertEquals(0, repository.singleSaveCalls());
        assertEquals(ProductDirectionStatus.SELECTED,
                repository.stored(SELECTED_ID).orElseThrow().status());
        assertEquals(ProductDirectionStatus.CANDIDATE,
                repository.stored(TARGET_ID).orElseThrow().status());
    }

    /**
     * 并发选择失败的形状（单条写入路径）：本层读到「当前没有 SELECTED 方向」，
     * 但在写入那一刻，另一个请求已经选好了一个。
     *
     * <p>这正是「先查再写」挡不住的那种竞态，存储层的唯一约束是最终守卫。它是业务冲突，
     * 不属于编排层能裁决的事情，因此原样向上传递——Interface 层据此翻译成 409，
     * 而不是让 SQLite 的约束细节穿出去。
     */
    @Test
    void propagatesSelectionConflictRaisedByTheSingleSave() {
        repository.seed(candidate(TARGET_ID));
        repository.failTransitionsWith(new ProductDirectionSelectionConflictException(TARGET_ID));

        assertThrows(ProductDirectionSelectionConflictException.class,
                () -> useCase.select(TARGET_ID));

        assertEquals(1, repository.transitionCalls(), "确实尝试过写入");
        assertEquals(0, repository.singleSaveCalls());
        assertEquals(ProductDirectionStatus.CANDIDATE,
                repository.stored(TARGET_ID).orElseThrow().status(),
                "冲突时目标方向不得留下任何状态变化");
    }

    /**
     * 同一个冲突也可能发生在切换的整批写入上：读出当前方向之后、写下去之前，
     * 并发的选择把它变成了别的形状。整批失败，两条都不生效。
     */
    @Test
    void propagatesSelectionConflictRaisedByTheBatchSwitch() {
        repository.seed(selected(SELECTED_ID));
        repository.seed(candidate(TARGET_ID));
        repository.failTransitionsWith(new ProductDirectionSelectionConflictException(TARGET_ID));

        assertThrows(ProductDirectionSelectionConflictException.class,
                () -> useCase.select(TARGET_ID));

        assertEquals(1, repository.transitionCalls(), "失败后不得重试");
        assertEquals(ProductDirectionStatus.SELECTED,
                repository.stored(SELECTED_ID).orElseThrow().status());
        assertEquals(ProductDirectionStatus.CANDIDATE,
                repository.stored(TARGET_ID).orElseThrow().status());
    }

    /**
     * 切换所依据的状态在写入时已经变化时，冲突原样向上传递，两个方向都不留下改动。
     *
     * <p>这是并发切换的真实形状：另一个请求在这中间推进了其中一条方向，
     * 本次手上那份认知已经作废。
     */
    @Test
    void propagatesStatusConflictRaisedByTheBatchSwitch() {
        repository.seed(selected(SELECTED_ID));
        repository.seed(candidate(TARGET_ID));
        repository.failTransitionsWith(new ProductDirectionStatusConflictException(
                TARGET_ID, ProductDirectionStatus.CANDIDATE));

        assertThrows(ProductDirectionStatusConflictException.class,
                () -> useCase.select(TARGET_ID));

        assertEquals(1, repository.transitionCalls(), "失败后不得重试");
        assertEquals(ProductDirectionStatus.SELECTED,
                repository.stored(SELECTED_ID).orElseThrow().status());
        assertEquals(ProductDirectionStatus.CANDIDATE,
                repository.stored(TARGET_ID).orElseThrow().status());
    }

    @Test
    void rejectsMissingIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> useCase.select(null));

        assertNothingWasWritten();
    }

    @Test
    void rejectsMissingRepository() {
        assertThrows(IllegalArgumentException.class,
                () -> new SelectProductDirectionUseCase(null, null, null));
    }

    private void assertNothingWasWritten() {
        assertEquals(0, repository.singleSaveCalls(), "不该发生单条保存");
        assertEquals(0, repository.batchCalls(), "不该发生整批保存");
        assertEquals(0, repository.transitionCalls(), "不该发生生命周期转换写入");
    }

    // ---------------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------------

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

    private static ProductDirection selected(ProductDirectionId id) {
        ProductDirection direction = candidate(id);
        direction.select();
        return direction;
    }
}
