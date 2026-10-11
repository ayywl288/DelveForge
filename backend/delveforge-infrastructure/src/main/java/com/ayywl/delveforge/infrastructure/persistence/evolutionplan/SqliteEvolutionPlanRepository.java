package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.application.port.persistence.EvolutionPlanAlreadyExistsException;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.domain.asset.AssetAuthorizationConflictException;
import com.ayywl.delveforge.domain.asset.AssetUsagePolicy;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evolution.CurrentState;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStateException;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanningPreconditionException;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.ExecutionResult;
import com.ayywl.delveforge.domain.evolution.TargetState;
import com.ayywl.delveforge.domain.evolution.VerificationCriterion;
import com.ayywl.delveforge.domain.evolution.VerificationResult;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 将一次规划结果作为整体持久化，根、内容、Step 与 Evidence 共用事务。
 * 规划保存只允许插入；生命周期更新经 EvolutionLifecycleCommitPort 提交。
 * 再次规划使用新身份，保留旧结果。
 */
@Repository
public class SqliteEvolutionPlanRepository implements EvolutionPlanRepository {
    private final EvolutionPlanMapper planMapper;
    private final EvolutionPlanSectionItemMapper sectionMapper;
    private final EvolutionStepMapper stepMapper;
    private final EvolutionStepSectionItemMapper stepSectionMapper;
    private final EvolutionPlanEvidenceMapper evidenceMapper;

    public SqliteEvolutionPlanRepository(EvolutionPlanMapper plans, EvolutionPlanSectionItemMapper sections,
            EvolutionStepMapper steps, EvolutionStepSectionItemMapper stepSections, EvolutionPlanEvidenceMapper evidence) {
        this.planMapper = plans;
        this.sectionMapper = sections;
        this.stepMapper = steps;
        this.stepSectionMapper = stepSections;
        this.evidenceMapper = evidence;
    }

    @Override
    @Transactional
    public void saveAtAssetAuthorization(EvolutionPlan plan, SoftwareAsset expectedAsset) {
        new AssetUsagePolicy().requireEvolutionBaseAllowed(expectedAsset);
        if (!plan.baseAssetId().equals(expectedAsset.id())) {
            throw new IllegalArgumentException("Plan and authorization asset must match");
        }
        if (planMapper.lockPlanningAuthorization(expectedAsset, expectedAsset.licenseInfo().orElseThrow()) != 1) {
            throw new AssetAuthorizationConflictException();
        }
        save(plan);
    }

    @Override
    @Transactional
    public void save(EvolutionPlan plan) {
        if (plan.status() != EvolutionPlanStatus.PROPOSED || plan.workingCopyId() != null) {
            throw new EvolutionPlanStateException("New planning results must be unbound PROPOSED Plans");
        }
        String id = plan.id().value();
        if (planMapper.selectById(id) != null) {
            throw new EvolutionPlanAlreadyExistsException(plan.id());
        }
        EvolutionPlanDO row = new EvolutionPlanDO();
        row.setId(id);
        row.setProductDirectionId(plan.productDirectionId().value());
        row.setBaseAssetId(plan.baseAssetId().value());
        row.setBaseRepositoryProfileId(plan.baseRepositoryProfileId().value());
        row.setWorkingCopyId(plan.workingCopyId());
        row.setCurrentSummary(plan.currentState().summary());
        row.setTargetProblem(plan.targetState().problem());
        row.setTargetProduct(plan.targetState().targetProduct());
        row.setTargetDifferentiation(plan.targetState().differentiation());
        row.setStatus(plan.status().name());
        if (planMapper.insertForSelectedDirection(row) != 1) {
            throw new EvolutionPlanningPreconditionException("Direction is no longer SELECTED at plan commit");
        }

        insertSection(id, "currentCapabilities", plan.currentState().capabilities());
        insertSection(id, "currentModules", plan.currentState().modules());
        insertSection(id, "currentLimitations", plan.currentState().limitations());
        insertSection(id, "reusableCapabilities", plan.reusableCapabilities());
        insertSection(id, "changes", plan.changes());
        insertSection(id, "risks", plan.risks());
        for (int position = 0; position < plan.steps().size(); position++) {
            insertStep(plan.steps().get(position), position);
        }
        for (int position = 0; position < plan.evidence().size(); position++) {
            insertEvidence(id, position, plan.evidence().get(position));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EvolutionPlan> findById(EvolutionPlanId id) {
        EvolutionPlanDO row = planMapper.selectById(id.value());
        if (row == null) {
            return Optional.empty();
        }
        Map<String, List<String>> sections = loadSections(id.value());
        List<EvolutionStepDO> stepRows = stepMapper.selectList(new LambdaQueryWrapper<EvolutionStepDO>()
                .eq(EvolutionStepDO::getPlanId, id.value()).orderByAsc(EvolutionStepDO::getPosition));
        List<EvolutionStep> steps = new ArrayList<>();
        for (EvolutionStepDO step : stepRows) {
            Map<String, List<String>> stepSections = loadStepSections(step.getId());
            steps.add(EvolutionStep.reconstitute(new EvolutionStepId(step.getId()),
                    new EvolutionPlanId(step.getPlanId()), step.getGoal(), step.getScope(),
                    values(stepSections, "plannedChanges"),
                    loadCriteria(step.getId()), EvolutionStepStatus.valueOf(step.getStatus()),
                    step.getBaselineRevision(), StepResultJson.read(step.getExecutionResult(), ExecutionResult.class),
                    StepResultJson.read(step.getVerificationResult(), VerificationResult.class)));
        }

        // 已保存的是被接受的领域事实；读取时直接重建正式值，不重新制造 AI 提案。
        CurrentState currentState = new CurrentState(row.getCurrentSummary(), values(sections, "currentCapabilities"),
                values(sections, "currentModules"), values(sections, "currentLimitations"));
        TargetState targetState = new TargetState(row.getTargetProblem(), row.getTargetProduct(), row.getTargetDifferentiation());
        return Optional.of(EvolutionPlan.reconstitute(id, new ProductDirectionId(row.getProductDirectionId()),
                new SoftwareAssetId(row.getBaseAssetId()), new RepositoryProfileId(row.getBaseRepositoryProfileId()),
                currentState, targetState, values(sections, "reusableCapabilities"), values(sections, "changes"),
                steps, values(sections, "risks"), loadEvidence(id.value()),
                row.getWorkingCopyId() == null ? null : new WorkingCopyId(row.getWorkingCopyId()),
                EvolutionPlanStatus.valueOf(row.getStatus())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvolutionPlan> findByProductDirectionId(ProductDirectionId id) {
        return planMapper.selectList(new LambdaQueryWrapper<EvolutionPlanDO>()
                .eq(EvolutionPlanDO::getProductDirectionId, id.value()).orderByAsc(EvolutionPlanDO::getId))
                .stream().map(row -> findById(new EvolutionPlanId(row.getId())).orElseThrow()).toList();
    }

    private void insertSection(String planId, String section, List<String> values) {
        for (int position = 0; position < values.size(); position++) {
            var row = new EvolutionPlanSectionItemDO();
            row.setPlanId(planId);
            row.setSection(section);
            row.setPosition(position);
            row.setValue(values.get(position));
            sectionMapper.insert(row);
        }
    }

    private void insertStep(EvolutionStep step, int position) {
        var row = new EvolutionStepDO();
        row.setId(step.id().value());
        row.setPlanId(step.planId().value());
        row.setPosition(position);
        row.setGoal(step.goal());
        row.setScope(step.scope());
        row.setStatus(step.status().name());
        row.setBaselineRevision(step.baselineRevision());
        stepMapper.insert(row);
        insertStepSection(step.id().value(), "plannedChanges", step.plannedChanges());
        for (int index = 0; index < step.verificationCriteria().size(); index++) {
            VerificationCriterion criterion = step.verificationCriteria().get(index);
            var item = new EvolutionStepSectionItemDO();
            item.setStepId(step.id().value());
            item.setSection("verificationCriteria");
            item.setPosition(index);
            item.setValue(criterion.description());
            item.setCriterionKind(criterion.kind().name());
            stepSectionMapper.insert(item);
        }
    }

    private void insertStepSection(String stepId, String section, List<String> values) {
        for (int position = 0; position < values.size(); position++) {
            var row = new EvolutionStepSectionItemDO();
            row.setStepId(stepId);
            row.setSection(section);
            row.setPosition(position);
            row.setValue(values.get(position));
            stepSectionMapper.insert(row);
        }
    }

    private void insertEvidence(String planId, int position, EvidenceBasis basis) {
        var row = new EvolutionPlanEvidenceDO();
        Evidence evidence = basis.evidence();
        row.setPlanId(planId);
        row.setPosition(position);
        row.setSourceType(evidence.sourceType().name());
        row.setSourceRef(evidence.sourceRef());
        row.setClaim(evidence.claim());
        row.setConfidence(evidence.confidence());
        row.setConfirmed(evidence.confirmed() ? 1 : 0);
        if (basis.origin() instanceof UserProfileEvidenceOrigin origin) {
            row.setOriginKind("userProfile");
            row.setOriginUserProfileId(origin.userProfileId().value());
            row.setOriginUserProfileRevision(origin.userProfileRevision());
        } else {
            if (basis.origin() instanceof RepositoryProfileEvidenceOrigin origin) {
                row.setOriginKind("repositoryProfile");
                row.setOriginRepositoryProfileId(origin.repositoryProfileId().value());
            } else {
                throw new IllegalStateException("Unsupported Evidence origin");
            }
        }
        evidenceMapper.insert(row);
    }

    private Map<String, List<String>> loadSections(String id) {
        List<EvolutionPlanSectionItemDO> rows = sectionMapper.selectList(new LambdaQueryWrapper<EvolutionPlanSectionItemDO>()
                .eq(EvolutionPlanSectionItemDO::getPlanId, id)
                .orderByAsc(EvolutionPlanSectionItemDO::getSection).orderByAsc(EvolutionPlanSectionItemDO::getPosition));
        Map<String, List<String>> result = new LinkedHashMap<>();
        rows.forEach(row -> result.computeIfAbsent(row.getSection(), key -> new ArrayList<>()).add(row.getValue()));
        return result;
    }

    private Map<String, List<String>> loadStepSections(String id) {
        List<EvolutionStepSectionItemDO> rows = stepSectionMapper.selectList(new LambdaQueryWrapper<EvolutionStepSectionItemDO>()
                .eq(EvolutionStepSectionItemDO::getStepId, id)
                .orderByAsc(EvolutionStepSectionItemDO::getSection).orderByAsc(EvolutionStepSectionItemDO::getPosition));
        Map<String, List<String>> result = new LinkedHashMap<>();
        rows.forEach(row -> result.computeIfAbsent(row.getSection(), key -> new ArrayList<>()).add(row.getValue()));
        return result;
    }

    private List<VerificationCriterion> loadCriteria(String stepId) {
        return stepSectionMapper.selectList(new LambdaQueryWrapper<EvolutionStepSectionItemDO>()
                .eq(EvolutionStepSectionItemDO::getStepId, stepId)
                .eq(EvolutionStepSectionItemDO::getSection, "verificationCriteria")
                .orderByAsc(EvolutionStepSectionItemDO::getPosition)).stream()
                .map(row -> new VerificationCriterion(VerificationCriterion.Kind.valueOf(row.getCriterionKind()), row.getValue()))
                .toList();
    }

    private List<EvidenceBasis> loadEvidence(String id) {
        List<EvolutionPlanEvidenceDO> rows = evidenceMapper.selectList(new LambdaQueryWrapper<EvolutionPlanEvidenceDO>()
                .eq(EvolutionPlanEvidenceDO::getPlanId, id).orderByAsc(EvolutionPlanEvidenceDO::getPosition));
        List<EvidenceBasis> result = new ArrayList<>();
        for (EvolutionPlanEvidenceDO row : rows) {
            EvidenceOrigin origin = switch (row.getOriginKind()) {
                case "userProfile" -> new UserProfileEvidenceOrigin(new UserProfileId(row.getOriginUserProfileId()),
                        row.getOriginUserProfileRevision());
                case "repositoryProfile" -> new RepositoryProfileEvidenceOrigin(
                        new RepositoryProfileId(row.getOriginRepositoryProfileId()));
                default -> throw new IllegalStateException("Unsupported stored Evidence origin");
            };
            result.add(new EvidenceBasis(new Evidence(EvidenceSourceType.valueOf(row.getSourceType()),
                    row.getSourceRef(), row.getClaim(), row.getConfidence(), row.getConfirmed() != 0), origin));
        }
        return List.copyOf(result);
    }

    private static List<String> values(Map<String, List<String>> sections, String section) {
        return sections.getOrDefault(section, List.of());
    }
}
