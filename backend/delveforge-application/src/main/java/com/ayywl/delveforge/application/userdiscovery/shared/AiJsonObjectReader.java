package com.ayywl.delveforge.application.userdiscovery.shared;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * 把 AI 返回的原始内容读成「恰好一个 json 对象」。
 *
 * <p>共享这条契约的解析器不止一个，因此集中实现一次：
 *
 * <pre>
 * 内容非空
 * 是合法 json
 * 根节点是对象，而不是数组或标量
 * json 之后没有多余内容——解释文字与第二个 json 值都算多余
 * </pre>
 *
 * <p>最后一条不能省：{@code readTree(String)} 只读第一个值就返回，后面附带的解释文字
 * 会被静默忽略，模型输出就会以「部分内容」被当成完整结果接受。
 *
 * <p>不满足契约意味着模型没有按要求作答，属于外部 AI 能力失败，
 * 因此统一抛 {@link AiGatewayException}。
 */
public final class AiJsonObjectReader {

    private final ObjectMapper objectMapper;

    public AiJsonObjectReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode read(String rawAiOutput) {
        if (rawAiOutput == null || rawAiOutput.isBlank()) {
            throw new AiGatewayException("AI 返回内容为空");
        }

        JsonNode root;
        try (JsonParser parser = objectMapper.getFactory().createParser(rawAiOutput)) {
            root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw new AiGatewayException("AI 返回内容在 json 对象之后还有多余内容");
            }
        } catch (JsonProcessingException exception) {
            throw new AiGatewayException("AI 返回内容不是合法 json", exception);
        } catch (IOException exception) {
            throw new AiGatewayException("无法读取 AI 返回内容", exception);
        }

        if (root == null || !root.isObject()) {
            throw new AiGatewayException("AI 返回内容不是 json 对象");
        }
        return root;
    }
}
