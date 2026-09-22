package com.ayywl.delveforge.app.api;

import com.ayywl.delveforge.application.repositoryanalysis.profile.GetRepositoryProfileUseCase;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Repository Profile 查询端点。
 *
 * <pre>
 * GET  /api/repository-profiles/{id}      读取一份已经保存的分析快照
 * </pre>
 *
 * <p>只读：快照没有再分析的入口，也没有状态机。需要新的分析结果时，对相应的
 * Software Asset 再次调用 {@code POST /api/software-assets/{id}/analysis}，
 * 那会产生另一份快照，而不是改写这一份。
 */
@RestController
@RequestMapping("/api/repository-profiles")
public class RepositoryProfileController {

    private final GetRepositoryProfileUseCase getRepositoryProfileUseCase;

    public RepositoryProfileController(GetRepositoryProfileUseCase getRepositoryProfileUseCase) {
        this.getRepositoryProfileUseCase = getRepositoryProfileUseCase;
    }

    @GetMapping("/{id}")
    public RepositoryProfileResponse get(@PathVariable String id) {
        return SoftwareAssetController.toResponse(
                getRepositoryProfileUseCase.get(new RepositoryProfileId(id)));
    }
}
