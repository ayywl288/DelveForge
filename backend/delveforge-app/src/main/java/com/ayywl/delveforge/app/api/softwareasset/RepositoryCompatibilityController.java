package com.ayywl.delveforge.app.api.softwareasset;

import com.ayywl.delveforge.application.port.workspace.RepositoryCompatibility;
import com.ayywl.delveforge.application.repositoryanalysis.compatibility.GetRepositoryCompatibilityUseCase;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 返回固定版本的结构化诊断，不执行源码或创建演化授权。 */
@RestController
public final class RepositoryCompatibilityController {
    private final GetRepositoryCompatibilityUseCase compatibility;

    public RepositoryCompatibilityController(GetRepositoryCompatibilityUseCase compatibility) {
        this.compatibility = compatibility;
    }

    @GetMapping("/api/software-assets/{id}/compatibility")
    public RepositoryCompatibility get(@PathVariable String id, @RequestParam(required = false) String revision) {
        return compatibility.get(new SoftwareAssetId(id), revision);
    }
}
