package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 选定仓库内容的统一能力；旧枚举保留用于历史投影的协议兼容。 */
public final class RepositoryContentPolicy {
    public enum Kind { SOURCE, PUBLIC_RESOURCE, PRESERVED_ONLY }

    public record Capability(Kind kind, Map<String, String> settings) {
        public Capability {
            settings = Map.copyOf(settings);
        }

        public boolean preserved() { return true; }
        public boolean modelVisible() { return kind != Kind.PRESERVED_ONLY; }
        public boolean executionAvailable() { return kind != Kind.PRESERVED_ONLY; }
        public boolean mutable() { return kind == Kind.SOURCE; }
    }

    public RepositoryContentPolicy(RepositorySecretPolicy secrets) {
    }

    /** 用户选定快照是任务输入；内容形态不授予或撤销能力，路径和资源预算由 Workspace 校验。 */
    public Capability classify(String path, byte[] bytes) {
        return new Capability(Kind.SOURCE, Map.of());
    }

    /** 缺少可执行源码或构建描述时不能用剩余测试制造验证成功；资源缺失仍须显式记执行投影。 */
    public static boolean buildInput(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        // 未知扩展名默认可能参与构建，不能把 Go/C++/脚本等漏掉；明确的资料和私有资源另行保留。
        return !name.startsWith(".env") && !Set.of(".npmrc", ".netrc", ".pypirc").contains(name)
                && !name.matches(".*\\.(md|txt|rst|png|jpg|jpeg|gif|svg|ico|pdf|properties|pem|crt|cer|key|jks|p12|pfx)");
    }

}
