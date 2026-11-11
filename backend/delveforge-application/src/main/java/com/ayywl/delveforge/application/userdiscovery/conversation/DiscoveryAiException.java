package com.ayywl.delveforge.application.userdiscovery.conversation;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;

/** Discovery 的固定诊断字段；不将 Provider 正文、用户输入或校验错误文本带入日志。 */
public final class DiscoveryAiException extends AiGatewayException {

    public enum Stage { PROFILE_EXTRACTION, DIALOGUE_PLANNING }

    private final Stage stage;

    public DiscoveryAiException(Stage stage, AiGatewayException cause) {
        this(stage, cause.category(), cause);
    }

    public DiscoveryAiException(Stage stage, Category category, RuntimeException cause) {
        super("Discovery AI operation failed", cause, category);
        this.stage = stage;
    }

    public Stage stage() {
        return stage;
    }
}
