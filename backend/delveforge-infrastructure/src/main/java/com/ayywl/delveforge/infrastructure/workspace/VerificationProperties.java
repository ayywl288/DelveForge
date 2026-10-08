package com.ayywl.delveforge.infrastructure.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 有界验证预算；单动作限制不能替代整次调用限制，输出上限同时约束报告材料。 */
@ConfigurationProperties(prefix = "delveforge.workspace.verification")
public record VerificationProperties(@DefaultValue("120s") Duration actionTimeout,
        @DefaultValue("300s") Duration invocationTimeout, @DefaultValue("1048576") int maxOutputBytes,
        @DefaultValue("16") int maxActions, @DefaultValue("100") int maxTestReports) {
    public VerificationProperties {
        if (actionTimeout == null || invocationTimeout == null || actionTimeout.isNegative() || actionTimeout.isZero()
                || invocationTimeout.isNegative() || invocationTimeout.isZero() || maxOutputBytes < 1024
                || maxActions < 1 || maxTestReports < 1) {
            throw new IllegalArgumentException("Verification budgets must be positive");
        }
    }
}
