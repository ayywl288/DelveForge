package com.ayywl.delveforge.application.repositoryanalysis.compatibility;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.workspace.RepositoryCompatibility;
import com.ayywl.delveforge.application.port.workspace.RepositoryCompatibilityPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;

/** 凭据与业务授权不从公开性推断；只读诊断不调用 AI、不准备副本。 */
public final class GetRepositoryCompatibilityUseCase {
    private final SoftwareAssetRepository assets;
    private final WorkspaceReadPort read;
    private final RepositoryCompatibilityPort compatibility;

    public GetRepositoryCompatibilityUseCase(SoftwareAssetRepository assets, WorkspaceReadPort read,
            RepositoryCompatibilityPort compatibility) {
        this.assets = assets;
        this.read = read;
        this.compatibility = compatibility;
    }

    public RepositoryCompatibility get(SoftwareAssetId id, String revision) {
        var asset = assets.findById(id).orElseThrow(() -> new SoftwareAssetNotFoundException(id));
        asset.requireAnalysisAllowed();
        WorkspaceRef source = new WorkspaceRef(asset.location());
        String pinned = revision == null ? read.headRevision(source) : revision;
        return compatibility.assess(source, pinned);
    }
}
