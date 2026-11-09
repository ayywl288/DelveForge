package com.ayywl.delveforge.application.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 单次请求的容量；输出预留取自 Agent 实际发送的 responseTokens。 */
public record AgentContextBudget(int contextWindowTokens, int responseTokens, double compactionThreshold) {
    public AgentContextBudget {
        if (responseTokens < 1 || contextWindowTokens <= responseTokens
                || !(compactionThreshold > 0 && compactionThreshold <= 1)) {
            throw new IllegalArgumentException("Invalid Agent context budget");
        }
    }

    public long usableInputTokens() {
        return contextWindowTokens - responseTokens;
    }

    public long triggerTokens() {
        return BigDecimal.valueOf(usableInputTokens()).multiply(BigDecimal.valueOf(compactionThreshold))
                .setScale(0, RoundingMode.CEILING).longValueExact();
    }

    public long targetTokens() {
        return usableInputTokens() * 3 / 4;
    }
}
