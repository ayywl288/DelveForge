package com.ayywl.delveforge.app.api.productdirection;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/**
 * {@code POST /api/product-directions/discovery} 的请求体。
 *
 * <p>它只包含「这次发现依据什么」这一个问题，且每一部分都必须由调用方明确给出：
 *
 * <pre>
 * userProfileId          用哪一份用户画像
 * expectedRevision       调用方认为它停在哪一版
 * repositoryProfileIds   用哪几份 Repository 分析快照
 * </pre>
 *
 * <h2>为什么要求 expectedRevision</h2>
 *
 * <p>发现的结果会被记录成「依据确定的某一版用户画像」（INV-D01、INV-D08）。若服务端默默
 * 取当前最新版本，用户看到的输入与系统实际依据的输入可能已经不是同一份——用户认可的是
 * 他当时看的那一版。因此调用方必须说出它依据的是哪一版；与存储中的版本不一致时请求会被
 * 拒绝（409），而不是被换一个版本执行。
 *
 * <h2>字段一律用包装类型</h2>
 *
 * <p>{@code expectedRevision} 用 {@link Integer} 而不是 {@code int}，是为了把「字段缺失」
 * 与「revision 为 0」区分开：前者是请求形状错误，后者是调用方给了一个不对应任何版本的
 * 取值——两者都由这里拒绝，而不是让默认值 0 静默进入下游。原始类型会把缺失的字段变成 0，
 * 那等于替调用方声明了一个它没有表达过的依据版本。
 *
 * <p>校验放在本类型的构造里，因此「形状不合法的发现请求」这个对象根本构造不出来，
 * Controller 不承担这套判断，也不需要在使用前再检查一遍。
 *
 * <h2>有损转换必须在构造之前就被拦住</h2>
 *
 * <p>还有一类取值是构造器拦不住的：{@code "expectedRevision": 3.9}。Jackson 默认允许把
 * 浮点数有损地读成整数，等到这里校验时字段已经是 {@code 3}——一个看起来完全合法的版本号，
 * 于是针对「第 3.9 版」的请求会照着第 3 版执行并成功。信息在进入本类型之前就已经丢掉了。
 *
 * <p>因此本字段带 {@link ExpectedRevisionDeserializer}：只接受 JSON 整数，小数、指数写法、
 * 字符串等一律在反序列化阶段失败并映射为 400，拒绝发生在任何业务代码被调用之前。
 * 其它接口的宽松行为不受影响。
 *
 * <h2>不属于本请求体的东西</h2>
 *
 * <p>Product Direction 的标识、状态、推荐内容、Evidence 与 EvidenceOrigin、最终的
 * repositoryProfileIds 与候选资产，都由服务端既有链路决定，不是这个端点的输入。
 * 请求体里出现这些字段也不会被读取（Jackson 忽略约定之外的字段），
 * 因此客户端无法通过它们凭空制造领域事实。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象，也不暴露 Application 的请求类型。
 *
 * @param userProfileId        本次发现所依据的 User Profile；不得为 {@code null} 或空白
 * @param expectedRevision     调用方认为该 Profile 停在哪一版；必须是 JSON 整数，
 *                             不得为 {@code null}，不得小于 1
 * @param repositoryProfileIds 本次发现可见的 Repository Profile；不得为 {@code null} 或空，
 *                             元素不得为 {@code null} 或空白
 * @throws IllegalArgumentException 任一参数不满足上述约束
 */
public record ProductDirectionDiscoveryRequest(
        String userProfileId,
        @JsonDeserialize(using = ExpectedRevisionDeserializer.class) Integer expectedRevision,
        List<String> repositoryProfileIds) {

    public ProductDirectionDiscoveryRequest {
        if (userProfileId == null || userProfileId.isBlank()) {
            throw new IllegalArgumentException(
                    "Product Direction Discovery 请求必须给出 userProfileId");
        }
        if (expectedRevision == null) {
            throw new IllegalArgumentException(
                    "Product Direction Discovery 请求必须给出 expectedRevision");
        }
        if (expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "Product Direction Discovery 请求的 expectedRevision 必须是确定的版本: "
                            + expectedRevision);
        }
        if (repositoryProfileIds == null || repositoryProfileIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Product Direction Discovery 请求至少需要一个 repositoryProfileIds");
        }
        for (String repositoryProfileId : repositoryProfileIds) {
            if (repositoryProfileId == null || repositoryProfileId.isBlank()) {
                throw new IllegalArgumentException(
                        "Product Direction Discovery 请求的 repositoryProfileIds "
                                + "不能包含空值");
            }
        }
        repositoryProfileIds = List.copyOf(repositoryProfileIds);
    }
}
