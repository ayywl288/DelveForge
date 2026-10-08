package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;

import java.util.Locale;
import java.util.Set;

/** 使用规范的 Git 相对路径，拒绝 Windows 别名及内部路径，不静默修补模型输入。 */
public final class ProposalPathPolicy {
    private static final Set<String> INTERNAL = Set.of(".git", ".hg", ".svn", ".codex", ".agents", ".openai");
    private final RepositorySecretPolicy secrets;

    public ProposalPathPolicy(RepositorySecretPolicy secrets) {
        this.secrets = secrets;
    }

    public String requireSafe(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.chars().anyMatch(ch -> ch < 32 || ch == 127 || ":*?\"<>|".indexOf(ch) >= 0)) {
            throw new AiGatewayException("Proposal requires a normalized relative path");
        }
        for (String part : path.split("/", -1)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".")
                    || part.endsWith(" ") || INTERNAL.contains(lower)
                    || lower.matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) {
                throw new AiGatewayException("Proposal path is protected or ambiguous");
            }
        }
        if (secrets.excludes(path)) {
            throw new AiGatewayException("Proposal path is excluded by the repository secret boundary");
        }
        return path;
    }

    public boolean allows(String path) {
        try {
            requireSafe(path);
            return true;
        } catch (AiGatewayException denied) {
            return false;
        }
    }
}
