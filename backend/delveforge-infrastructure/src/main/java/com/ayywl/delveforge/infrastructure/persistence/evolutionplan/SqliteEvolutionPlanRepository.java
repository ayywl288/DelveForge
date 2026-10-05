package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.application.port.persistence.EvolutionPlanAlreadyExistsException;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** SQLite/MyBatis-Plus persistence of one coherent planning result.
 * Only inserts are supported in Task 1. A second plan has a new identity.
 * All parent/content/step/evidence writes share a transaction.
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
    public void save(EvolutionPlan plan) {
        String id = plan.id().value();
        if (planMapper.selectById(id) != null) throw new EvolutionPlanAlreadyExistsException(plan.id());
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
        if (planMapper.insertForSelectedDirection(row) != 1)
            throw new EvolutionPlanningPreconditionException("Direction is no longer SELECTED at plan commit");

        insertSection(id, "currentCapabilities", plan.currentState().capabilities());
        insertSection(id, "currentModules", plan.currentState().modules());
        insertSection(id, "currentLimitations", plan.currentState().limitations());
        insertSection(id, "reusableCapabilities", plan.reusableCapabilities());
        insertSection(id, "changes", plan.changes());
        insertSection(id, "risks", plan.risks());
        for (int position = 0; position < plan.steps().size(); position++)
            insertStep(plan.steps().get(position), position);
        for (int position = 0; position < plan.evidence().size(); position++)
            insertEvidence(id, position, plan.evidence().get(position));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EvolutionPlan> findById(EvolutionPlanId id) {
        EvolutionPlanDO row = planMapper.selectById(id.value());
        if (row == null) return Optional.empty();
        if (!EvolutionPlanStatus.PROPOSED.name().equals(row.getStatus()) || row.getWorkingCopyId() != null)
            throw new IllegalStateException("Unsupported stored plan lifecycle state");
        Map<String, List<String>> sections = loadSections(id.value());
        var stepRows = stepMapper.selectList(new LambdaQueryWrapper<EvolutionStepDO>()
                .eq(EvolutionStepDO::getPlanId, id.value()).orderByAsc(EvolutionStepDO::getPosition));
        List<PlanningStepProposal> definitions = new ArrayList<>();
        List<EvolutionStepId> ids = new ArrayList<>();
        for (EvolutionStepDO step : stepRows) {
            if (!EvolutionStepStatus.PENDING_CONFIRMATION.name().equals(step.getStatus()) || step.getBaselineRevision() != null)
                throw new IllegalStateException("Unsupported stored step lifecycle state");
            Map<String, List<String>> stepSections = loadStepSections(step.getId());
            definitions.add(new PlanningStepProposal(step.getGoal(), step.getScope(),
                    values(stepSections, "plannedChanges"), values(stepSections, "preconditions"),
                    values(stepSections, "verificationCriteria")));
            ids.add(new EvolutionStepId(step.getId()));
        }
        var content = new PlanningProposal(
                new CurrentState(row.getCurrentSummary(), values(sections, "currentCapabilities"),
                        values(sections, "currentModules"), values(sections, "currentLimitations")),
                new TargetState(row.getTargetProblem(), row.getTargetProduct(), row.getTargetDifferentiation()),
                values(sections, "reusableCapabilities"), values(sections, "changes"), definitions,
                values(sections, "risks"), loadEvidence(id.value()));
        return Optional.of(EvolutionPlan.reconstitute(id, new ProductDirectionId(row.getProductDirectionId()),
                new SoftwareAssetId(row.getBaseAssetId()), new RepositoryProfileId(row.getBaseRepositoryProfileId()),
                content, ids));
    }

    private void insertSection(String planId, String section, List<String> values) {
        for (int position = 0; position < values.size(); position++) {
            var row = new EvolutionPlanSectionItemDO();
            row.setPlanId(planId); row.setSection(section); row.setPosition(position); row.setValue(values.get(position));
            sectionMapper.insert(row);
        }
    }
    private void insertStep(EvolutionStep step, int position) {
        var row = new EvolutionStepDO();
        row.setId(step.id().value()); row.setPlanId(step.planId().value()); row.setPosition(position);
        row.setGoal(step.goal()); row.setScope(step.scope()); row.setStatus(step.status().name());
        row.setBaselineRevision(step.baselineRevision());
        stepMapper.insert(row);
        insertStepSection(step.id().value(), "plannedChanges", step.plannedChanges());
        insertStepSection(step.id().value(), "preconditions", step.preconditions());
        insertStepSection(step.id().value(), "verificationCriteria", step.verificationCriteria());
    }
    private void insertStepSection(String stepId, String section, List<String> values) {
        for (int position = 0; position < values.size(); position++) {
            var row = new EvolutionStepSectionItemDO();
            row.setStepId(stepId); row.setSection(section); row.setPosition(position); row.setValue(values.get(position));
            stepSectionMapper.insert(row);
        }
    }
    private void insertEvidence(String planId, int position, EvidenceBasis basis) {
        var row = new EvolutionPlanEvidenceDO();
        var evidence = basis.evidence();
        row.setPlanId(planId); row.setPosition(position); row.setSourceType(evidence.sourceType().name());
        row.setSourceRef(evidence.sourceRef()); row.setClaim(evidence.claim());
        row.setConfidence(evidence.confidence()); row.setConfirmed(evidence.confirmed() ? 1 : 0);
        if (basis.origin() instanceof UserProfileEvidenceOrigin origin) {
            row.setOriginKind("userProfile");
            row.setOriginUserProfileId(origin.userProfileId().value());
            row.setOriginUserProfileRevision(origin.userProfileRevision());
        } else if (basis.origin() instanceof RepositoryProfileEvidenceOrigin origin) {
            row.setOriginKind("repositoryProfile");
            row.setOriginRepositoryProfileId(origin.repositoryProfileId().value());
        } else {
            throw new IllegalStateException("Unsupported Evidence origin");
        }
        evidenceMapper.insert(row);
    }
    private Map<String, List<String>> loadSections(String id) {
        var rows = sectionMapper.selectList(new LambdaQueryWrapper<EvolutionPlanSectionItemDO>()
                .eq(EvolutionPlanSectionItemDO::getPlanId, id)
                .orderByAsc(EvolutionPlanSectionItemDO::getSection).orderByAsc(EvolutionPlanSectionItemDO::getPosition));
        Map<String, List<String>> result = new LinkedHashMap<>();
        rows.forEach(row -> result.computeIfAbsent(row.getSection(), key -> new ArrayList<>()).add(row.getValue()));
        return result;
    }
    private Map<String, List<String>> loadStepSections(String id) {
        var rows = stepSectionMapper.selectList(new LambdaQueryWrapper<EvolutionStepSectionItemDO>()
                .eq(EvolutionStepSectionItemDO::getStepId, id)
                .orderByAsc(EvolutionStepSectionItemDO::getSection).orderByAsc(EvolutionStepSectionItemDO::getPosition));
        Map<String, List<String>> result = new LinkedHashMap<>();
        rows.forEach(row -> result.computeIfAbsent(row.getSection(), key -> new ArrayList<>()).add(row.getValue()));
        return result;
    }
    private List<EvidenceBasis> loadEvidence(String id) {
        var rows = evidenceMapper.selectList(new LambdaQueryWrapper<EvolutionPlanEvidenceDO>()
                .eq(EvolutionPlanEvidenceDO::getPlanId, id).orderByAsc(EvolutionPlanEvidenceDO::getPosition));
        List<EvidenceBasis> result = new ArrayList<>();
        for (var row : rows) {
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
