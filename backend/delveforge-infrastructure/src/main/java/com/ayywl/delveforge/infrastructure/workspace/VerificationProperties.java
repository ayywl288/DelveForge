package com.ayywl.delveforge.infrastructure.workspace;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "delveforge.workspace.verification")
public record VerificationProperties(@DefaultValue("120s") Duration actionTimeout,
        @DefaultValue("300s") Duration invocationTimeout, @DefaultValue("1048576") int maxOutputBytes,
        @DefaultValue("16") int maxActions) {
    public VerificationProperties {
        if (actionTimeout == null || invocationTimeout == null || actionTimeout.isNegative() || actionTimeout.isZero()
                || invocationTimeout.isNegative() || invocationTimeout.isZero() || maxOutputBytes < 1024 || maxActions < 1) {
            throw new IllegalArgumentException("Verification budgets must be positive");
        }
    }
}
