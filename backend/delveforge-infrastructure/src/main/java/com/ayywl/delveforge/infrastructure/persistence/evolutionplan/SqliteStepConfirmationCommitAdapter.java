package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.application.port.persistence.EvolutionLifecycleConflictException;
import com.ayywl.delveforge.application.port.persistence.StepConfirmationCommitPort;
import com.ayywl.delveforge.domain.asset.AssetUsagePolicy;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.evolution.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SqliteStepConfirmationCommitAdapter implements StepConfirmationCommitPort {
    private final EvolutionStepMapper steps;

    public SqliteStepConfirmationCommitAdapter(EvolutionStepMapper steps) {
        this.steps = steps;
    }

    @Override
    @Transactional
    public void confirm(EvolutionPlan candidate, EvolutionStepId id, WorkingCopy copy, SoftwareAsset asset) {
        new StepExecutionPolicy().requireExecutionAllowed(candidate, id, copy);
        new AssetUsagePolicy().requireEvolutionBaseAllowed(asset);
        if (!candidate.baseAssetId().equals(asset.id())
                || steps.confirmAtBasis(candidate, id, copy, asset, asset.licenseInfo().orElse(null)) != 1) {
            throw new EvolutionLifecycleConflictException("Step confirmation basis changed before commit");
        }
    }
}
