package com.ayywl.delveforge.app.api.softwareasset;

import com.ayywl.delveforge.app.api.repositoryprofile.RepositoryProfileResponse;
import com.ayywl.delveforge.application.repositoryanalysis.asset.GetSoftwareAssetUseCase;
import com.ayywl.delveforge.application.repositoryanalysis.asset.RegisterSoftwareAssetRequest;
import com.ayywl.delveforge.application.repositoryanalysis.asset.RegisterSoftwareAssetUseCase;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.AnalyzeRepositoryUseCase;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Software Asset 业务端点。
 *
 * <pre>
 * POST  /api/software-assets              登记一个本地 Git Repository 资产
 * GET   /api/software-assets/{id}         读取已登记的资产元数据
 * POST  /api/software-assets/{id}/analysis  分析该资产的 Repository，形成一份新的快照
 * </pre>
 *
 * <p>Controller 保持轻量（RULE-ARCH-005）：解析请求、映射为 Application Use Case 的输入、
 * 把结果映射为响应。它不判断资产是否可读、不访问 Workspace、不接触 AI、不决定
 * {@code analyzedRevision}——这些由 Application 与 Domain 决定，失败由
 * {@code com.ayywl.delveforge.app.error} 统一翻译。
 *
 * <p>{@code analysis} 端点不接收请求体：分析针对的是服务端当前解析出的 revision，
 * 以及服务端按策略从 Repository 读到的材料。客户端即使发送 {@code analyzedRevision}、
 * {@code evidence} 一类字段也不会被读取——它们不是这个端点的输入。
 */
@RestController
@RequestMapping("/api/software-assets")
public class SoftwareAssetController {

    private final RegisterSoftwareAssetUseCase registerSoftwareAssetUseCase;
    private final GetSoftwareAssetUseCase getSoftwareAssetUseCase;
    private final AnalyzeRepositoryUseCase analyzeRepositoryUseCase;

    public SoftwareAssetController(
            RegisterSoftwareAssetUseCase registerSoftwareAssetUseCase,
            GetSoftwareAssetUseCase getSoftwareAssetUseCase,
            AnalyzeRepositoryUseCase analyzeRepositoryUseCase) {
        this.registerSoftwareAssetUseCase = registerSoftwareAssetUseCase;
        this.getSoftwareAssetUseCase = getSoftwareAssetUseCase;
        this.analyzeRepositoryUseCase = analyzeRepositoryUseCase;
    }

    /**
     * 登记一个 Software Asset。
     *
     * <p>只登记元数据：本端点不判断 location 是否存在、是否是 Git Repository。
     *
     * <p>读取权限必须显式给出。缺省它会被当成「不允许读取」保存下来——那是一个
     * 由缺失输入制造出来的授权事实，而不是用户表达过的意思，因此这里直接拒绝，
     * 而不是替调用方补一个默认值（DOMAIN_MODEL.md §3.2、RULE-DOM-004）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SoftwareAssetResponse register(@RequestBody SoftwareAssetRegistrationRequest request) {
        if (request.readPermissionAllowed() == null) {
            throw new IllegalArgumentException(
                    "注册 Software Asset 必须显式给出 readPermissionAllowed");
        }
        return toResponse(registerSoftwareAssetUseCase.register(new RegisterSoftwareAssetRequest(
                request.location(),
                request.readPermissionAllowed(),
                request.licenseInfo(),
                request.usageAuthorization())));
    }

    @GetMapping("/{id}")
    public SoftwareAssetResponse get(@PathVariable String id) {
        return toResponse(getSoftwareAssetUseCase.get(new SoftwareAssetId(id)));
    }

    /**
     * 分析该资产的 Repository，返回形成的 Repository Profile。
     *
     * <p>每次调用都会产生一份新的快照：分析针对的是这一次解析出的 revision。
     * 现有快照不会被覆盖（见 {@code RepositoryProfileRepository} 的写入语义）。
     */
    @PostMapping("/{id}/analysis")
    @ResponseStatus(HttpStatus.CREATED)
    public RepositoryProfileResponse analyze(@PathVariable String id) {
        return RepositoryProfileResponse.from(
                analyzeRepositoryUseCase.analyze(new SoftwareAssetId(id)));
    }

    private static SoftwareAssetResponse toResponse(SoftwareAsset asset) {
        return new SoftwareAssetResponse(
                asset.id().value(),
                asset.type(),
                asset.source(),
                asset.location(),
                asset.readPermissionAllowed(),
                asset.licenseInfo().orElse(null),
                asset.usageAuthorization());
    }
}
