package com.ayywl.delveforge.app.api.productdirection;

import com.ayywl.delveforge.application.opportunitydiscovery.direction.DiscoverProductDirectionsRequest;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.DiscoverProductDirectionsUseCase;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.GetProductDirectionUseCase;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product Direction 业务端点。
 *
 * <pre>
 * POST  /api/product-directions/discovery   执行一次 Product Direction Discovery
 * GET   /api/product-directions/{id}        读取一条已保存的 Product Direction
 * </pre>
 *
 * <p>Controller 保持轻量（RULE-ARCH-005）：解析请求、映射为 Application Use Case 的输入、
 * 把结果映射为响应。它不判断方向是否合法、不生成标识、不决定状态、不接触 AI——
 * 这些由 Domain 与 Application 决定，失败由 {@code com.ayywl.delveforge.app.error} 统一翻译。
 *
 * <h2>discovery 端点的含义</h2>
 *
 * <p>它是「已经存在的 Product Direction Discovery 能力」对外的 HTTP 命令入口，面向系统
 * 外部调用、集成、后续的 smoke 验证与前后端联调。它<b>不</b>表示产品上要求用户手动点击
 * 「生成 Product Direction」：
 *
 * <pre>
 * readiness（判断输入是否已准备完成）
 * automatic discovery trigger（输入就绪时由系统进入发现）
 * </pre>
 *
 * <p>仍是 M2 中尚未完成的独立能力。本端点也不承担 Product Direction 的选择或拒绝——
 * {@code CANDIDATE → SELECTED} 与 {@code CANDIDATE → REJECTED} 由用户明确操作完成
 * （INV-D07），对应的用例不在本 Task 范围内。
 */
@RestController
@RequestMapping("/api/product-directions")
public class ProductDirectionController {

    private final DiscoverProductDirectionsUseCase discoverProductDirectionsUseCase;
    private final GetProductDirectionUseCase getProductDirectionUseCase;

    public ProductDirectionController(
            DiscoverProductDirectionsUseCase discoverProductDirectionsUseCase,
            GetProductDirectionUseCase getProductDirectionUseCase) {
        this.discoverProductDirectionsUseCase = discoverProductDirectionsUseCase;
        this.getProductDirectionUseCase = getProductDirectionUseCase;
    }

    /**
     * 执行一次 Product Direction Discovery，并把产生的候选方向整批保存下来。
     *
     * <p>请求体只说明「这次发现依据什么」；方向标识、状态、内容、依据与候选资产全部由
     * 服务端链路产生。链路失败时不写入任何方向，本端点也不会返回半批结果。
     *
     * <p>返回 201：这次调用产生了新的持久化资源（3 到 5 条候选方向），
     * 与 {@code POST /api/software-assets/{id}/analysis} 产生一份新快照的语义一致。
     */
    @PostMapping("/discovery")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDirectionDiscoveryResponse discover(
            @RequestBody ProductDirectionDiscoveryRequest request) {
        return new ProductDirectionDiscoveryResponse(
                discoverProductDirectionsUseCase.discover(toApplicationRequest(request))
                        .stream()
                        .map(ProductDirectionResponse::from)
                        .toList());
    }

    /**
     * 读取一条已经保存的 Product Direction。
     *
     * <p>状态不参与读取语义：已经被拒绝或已经被取代的方向同样能读回来（§10.5、
     * RULE-DOM-007），它们的历史解释能力不因被放弃而消失。
     */
    @GetMapping("/{id}")
    public ProductDirectionResponse get(@PathVariable String id) {
        return ProductDirectionResponse.from(
                getProductDirectionUseCase.get(new ProductDirectionId(id)));
    }

    /**
     * 把接口层的请求映射为 Application 的调用参数。
     *
     * <p>只做形状映射：请求体自身的合法性已经由
     * {@link ProductDirectionDiscoveryRequest} 的构造保证。
     */
    private static DiscoverProductDirectionsRequest toApplicationRequest(
            ProductDirectionDiscoveryRequest request) {
        return new DiscoverProductDirectionsRequest(
                new UserProfileId(request.userProfileId()),
                request.expectedRevision(),
                request.repositoryProfileIds().stream().map(RepositoryProfileId::new).toList());
    }
}
