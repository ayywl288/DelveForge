package com.ayywl.delveforge.app.api.userprofile;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.io.IOException;

/**
 * 请求体里「User Profile 的某一版」在反序列化边界上的严格读取：只接受 JSON 整数。
 *
 * <h2>谁在用</h2>
 *
 * <pre>
 * POST /api/user-profiles/{id}/confirm          ConfirmUserProfileRequest.revision
 * POST /api/product-directions/discovery        ProductDirectionDiscoveryRequest.expectedRevision
 * </pre>
 *
 * <p>两个字段是同一件事：调用方声明它依据的是 User Profile 的哪一版。因此这条读取规则
 * 只有一个实现，放在 User Profile 资源下——版本属于 User Profile，接口层按资源分包，
 * 但跨资源的字段语义不应该因此被复制成两份会彼此漂移的规则。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>Jackson 默认允许把浮点数<b>有损地</b>转换成整数。请求体里写 {@code 3.9}，字段会被
 * 静默读成 {@code 3}——等到请求类型自己的构造器开始校验时，用户写下的 3.9 早已不见，
 * 剩下的只是一个看起来完全合法的版本号。两个端点都会因此执行一次它从未被要求执行的操作：
 *
 * <pre>
 * confirm     「用户确认了第 3.9 版」被当成「确认第 3 版」并成功
 * discovery   针对「第 3.9 版」的发现照着第 3 版执行并返回候选方向
 * </pre>
 *
 * <p>那正是这两个端点都不允许发生的事：确认的意义是「用户同意了这一版内容」
 * （DOMAIN_MODEL.md §6.1），发现的结果要能追溯到确定的一版画像（INV-D01、INV-D08）。
 * 服务端不得替调用方把小数「换算」成另一个版本。缺失由包装类型与 {@code 0} 的区分拦住，
 * 有损转换则必须在这里拦住——再晚就拦不住了，因为信息已经丢掉。
 *
 * <h2>为什么是单字段而不是全局开关</h2>
 *
 * <p>Jackson 提供的开关（{@code DeserializationFeature.ACCEPT_FLOAT_AS_INT}）是全局的，
 * 打开它会同时改变所有接口的既有行为。这里只把需要这条语义的字段标记为严格读取，
 * 其余字段的宽松行为保持不变。
 *
 * <h2>被拒绝的取值</h2>
 *
 * <pre>
 * 3.9 / 3.0 / 1e2   小数与指数写法：不是这个字段的契约
 * "3"               字符串：JSON 里它是一条文本，不是版本号
 * true / [] / {}    更不是
 * null              放行，由请求类型按「没给」处理
 * </pre>
 *
 * <p>前几类在反序列化阶段失败，由统一错误映射翻译为 400 {@code INVALID_REQUEST}；
 * 拒绝发生在任何业务代码被调用之前。{@code null} 刻意不在这里拦：字段「没给」是一个
 * 请求形状问题，由各请求类型自己的构造器判定，本类不替它决定。
 *
 * <p>失败时的文本只用于服务端诊断，不会出现在响应体里（见 {@code ApiExceptionHandler}）。
 */
public final class UserProfileRevisionDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {

        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            try {
                return Integer.parseInt(parser.getText().trim());
            } catch (NumberFormatException outOfRange) {
                throw InvalidFormatException.from(
                        parser,
                        "User Profile revision 超出 32 位整数范围",
                        parser.getText(),
                        Integer.class);
            }
        }
        throw InvalidFormatException.from(
                parser,
                "User Profile revision 必须是整数，不能由 " + token + " 转换得到",
                parser.getText(),
                Integer.class);
    }
}
