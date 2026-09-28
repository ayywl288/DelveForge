package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.repositoryanalysis.profile.RepositoryProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.domain.direction.DirectionProposal;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionDiscoveryService;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行一次完整的 Product Direction Discovery。
 *
 * <p>它把此前各自独立的能力串成一条链路，本身不新增任何领域判断：
 *
 * <pre>
 * UserProfile（按标识加载 + 版本/状态校验）
 *         +
 * RepositoryProfile 1..N（按标识加载）
 *         ↓
 * DirectionDiscoveryInputs     固定本次发现的输入
 *         ↓
 * DirectionDiscoveryExtraction Prompt → AI → 严格解析 → 解析引用
 *         ↓
 * DirectionProposal 3..5       已经带真实依据的领域提案
 *         ↓
 * ProductDirectionDiscoveryService  领域校验与构造
 *         ↓
 * ProductDirection 3..5（CANDIDATE）
 *         ↓
 * 一次整批保存
 * </pre>
 *
 * <h2>它是编排，不是判断</h2>
 *
 * <p>本类不做领域判断：什么算一条合法方向由 {@code ProductDirectionDiscoveryService} 与
 * {@code ProductDirection} Aggregate 决定，模型输出是否符合约定由解析与解析引用决定。
 * 它只负责「按什么顺序调用谁」，以及把失败挡在正确的时机。
 *
 * <h2>失败都发生在写入之前</h2>
 *
 * <p>唯一一次写入在整条链路的最后，且是整批的：加载失败、版本过期、状态不符、AI 失败、
 * 解析失败、领域拒绝——任何一种都不会留下已经写入的方向。整批保存本身也是原子的
 * （见 {@link ProductDirectionRepository#saveAll}），因此不存在「只写进去一部分候选」
 * 的中间状态。
 *
 * <h2>不回答「什么时候该做发现」</h2>
 *
 * <p>本类只回答「被要求做一次发现时怎么做」。判断输入是否已经准备完成、是否自动触发，
 * 属于后续的 readiness / auto-trigger 能力。
 */
public class DiscoverProductDirectionsUseCase {

    private final UserProfileRepository userProfileRepository;
    private final RepositoryProfileRepository repositoryProfileRepository;
    private final DirectionDiscoveryExtraction discoveryExtraction;
    private final ProductDirectionDiscoveryService discoveryService;
    private final ProductDirectionRepository productDirectionRepository;

    public DiscoverProductDirectionsUseCase(
            UserProfileRepository userProfileRepository,
            RepositoryProfileRepository repositoryProfileRepository,
            DirectionDiscoveryExtraction discoveryExtraction,
            ProductDirectionDiscoveryService discoveryService,
            ProductDirectionRepository productDirectionRepository) {

        if (userProfileRepository == null) {
            throw new IllegalArgumentException(
                    "DiscoverProductDirectionsUseCase 必须指定 userProfileRepository");
        }
        if (repositoryProfileRepository == null) {
            throw new IllegalArgumentException(
                    "DiscoverProductDirectionsUseCase 必须指定 repositoryProfileRepository");
        }
        if (discoveryExtraction == null) {
            throw new IllegalArgumentException(
                    "DiscoverProductDirectionsUseCase 必须指定 discoveryExtraction");
        }
        if (discoveryService == null) {
            throw new IllegalArgumentException(
                    "DiscoverProductDirectionsUseCase 必须指定 discoveryService");
        }
        if (productDirectionRepository == null) {
            throw new IllegalArgumentException(
                    "DiscoverProductDirectionsUseCase 必须指定 productDirectionRepository");
        }

        this.userProfileRepository = userProfileRepository;
        this.repositoryProfileRepository = repositoryProfileRepository;
        this.discoveryExtraction = discoveryExtraction;
        this.discoveryService = discoveryService;
        this.productDirectionRepository = productDirectionRepository;
    }

    /**
     * 依据给定的输入发现候选 Product Direction，并把它们整批保存下来。
     *
     * @param request 本次发现的调用参数，不得为 {@code null}
     * @return 已经保存的候选方向，顺序与模型给出的提案一致；全部为 {@code CANDIDATE}
     * @throws IllegalArgumentException            request 为 {@code null}，
     *                                             或其内容不满足形状要求
     * @throws UserProfileNotFoundException        指定的 User Profile 不存在
     * @throws StaleUserProfileRevisionException   它已经不是调用方依据的那一版
     * @throws UserProfileNotConfirmedException    它尚未 {@code CONFIRMED}
     * @throws RepositoryProfileNotFoundException  指定的 Repository Profile 不存在
     * @throws com.ayywl.delveforge.application.port.ai.AiGatewayException
     *                                             AI 调用失败，或返回内容不满足约定
     * @throws com.ayywl.delveforge.domain.direction.ProductDirectionDiscoveryException
     *                                             Discovery 结果不满足领域要求
     */
    public List<ProductDirection> discover(DiscoverProductDirectionsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "DiscoverProductDirectionsUseCase 必须指定 request");
        }

        // 下面三步都发生在调用 AI 之前：输入不成立时不该先付一次模型调用的代价，
        // 也不该拿到一份基于错误输入的结果。
        UserProfile userProfile = userProfileRepository.findById(request.userProfileId())
                .orElseThrow(() -> new UserProfileNotFoundException(request.userProfileId()));
        requireCurrentRevision(userProfile, request);
        requireConfirmed(userProfile);

        List<RepositoryProfile> repositoryProfiles = loadAll(request.repositoryProfileIds());

        // 同一份 inputs 同时用于 Prompt、引用目录与资产白名单：三者必须来自同一个时点，
        // 否则模型看到的输入与它被允许引用的东西会对不上。
        DirectionDiscoveryInputs inputs =
                DirectionDiscoveryInputs.of(userProfile, repositoryProfiles);

        List<DirectionProposal> proposals = discoveryExtraction.extract(inputs);
        List<ProductDirection> directions =
                discoveryService.discover(userProfile, repositoryProfiles, proposals);

        // 整条链路的唯一一次写入，且是整批的。
        productDirectionRepository.saveAll(directions);

        return directions;
    }

    /**
     * 调用方依据的那一版必须仍是当前版本。
     *
     * <p>不默默改用当前版本：用户确认与查看的是那一版，拿之后的版本去推荐，得到的就不再是
     * 他认可过的那份画像所支持的方向（INV-D01、INV-D08）。
     *
     * <p>这里只把它当作过期输入的拦截。按历史 revision 回看某一次发现是另一项能力，
     * 当前不提供，也不因为这道校验而需要提供。
     */
    private static void requireCurrentRevision(UserProfile userProfile,
                                               DiscoverProductDirectionsRequest request) {
        if (userProfile.revision() != request.expectedRevision()) {
            throw new StaleUserProfileRevisionException(
                    request.userProfileId(), request.expectedRevision(), userProfile.revision());
        }
    }

    private static void requireConfirmed(UserProfile userProfile) {
        if (userProfile.status() != UserProfileStatus.CONFIRMED) {
            throw new UserProfileNotConfirmedException(userProfile.id(), userProfile.status());
        }
    }

    /**
     * 加载全部 requested Repository Profile，任何一个取不到就整体失败。
     *
     * <p>在调用 AI 之前完成：模型只应看到这次真正拿得到的分析快照，缺一份就不该开始。
     */
    private List<RepositoryProfile> loadAll(List<RepositoryProfileId> repositoryProfileIds) {
        List<RepositoryProfile> repositoryProfiles =
                new ArrayList<>(repositoryProfileIds.size());
        for (RepositoryProfileId profileId : repositoryProfileIds) {
            repositoryProfiles.add(repositoryProfileRepository.findById(profileId)
                    .orElseThrow(() -> new RepositoryProfileNotFoundException(profileId)));
        }
        return List.copyOf(repositoryProfiles);
    }
}
