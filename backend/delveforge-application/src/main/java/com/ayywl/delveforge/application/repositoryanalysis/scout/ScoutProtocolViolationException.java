package com.ayywl.delveforge.application.repositoryanalysis.scout;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;

/**
 * 模型这一次**没有按 Scout 的约定作答**。
 *
 * <pre>
 * 调用本身成功，返回的内容也解析得动   → 不属于本类型
 * 调用失败（网络 / 非 2xx / 空响应体）  → 不属于本类型（那是 {@link AiGatewayException}）
 * 返回的内容违反 Scout 输出契约        → 本类型
 * </pre>
 *
 * <h2>为什么要把它从 AiGatewayException 里分出来</h2>
 *
 * <p>这两类失败对调用方意味着不同的事：
 *
 * <pre>
 * 调用失败        这一次调用没有拿到答案，但**换一次也未必更好**——先修外部能力
 * 契约违反        模型这次没答好，**再问一次通常是值得的**：同一份输入、同一个模型，
 *                 上一次它给出了 7 个查看区域，这一次可能就给出 6 个
 * </pre>
 *
 * <p>真实证据：多仓 Smoke 里 memos 的第一次分析正是因为一次契约违反（File Scout 返回了
 * 7 个 {@code focusAreas}，契约上限 6）而整次作废，同一资产、同一 revision 再跑一次即成功。
 * 因此这一类失败允许**一次**有界重试（见 {@link RepositoryScoutExtraction}）。
 *
 * <h2>它仍然是 AiGatewayException</h2>
 *
 * <p>继承它而不是另立门户：对外这仍然是「外部 AI 能力没有给出可用结果」，
 * 接口层照旧映射成 502，既有的失败语义与测试都不受影响。
 * 需要区分对待的只有 Scout 自己的调用点——它们捕获本类型。
 *
 * <h2>它不会被用来放宽校验</h2>
 *
 * <p>抛出本类型不等于接受这份输出：解析与引用校验一条都没有放松，
 * 只是把「拒绝」变成「拒绝并再问一次」。第二次仍然不合法时，最终抛出的还是本类型，
 * 整次分析照旧失败关闭。
 */
public class ScoutProtocolViolationException extends AiGatewayException {

    public ScoutProtocolViolationException(String message) {
        super(message);
    }

    public ScoutProtocolViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
