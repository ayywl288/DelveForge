package com.ayywl.delveforge.application.port.workspace;

import java.util.List;

/** 对固定版本的确定性诊断；只含路径、结构和计数，不含源码或凭据值。 */
public record RepositoryCompatibility(String revision, String status, String policyVersion, String mavenDirectory,
        List<String> modules, Integer buildJdk, long sourceBytes, int files, int textFiles, int opaqueFiles,
        List<String> generatedPaths, List<String> immutablePaths, List<Issue> issues) {
    public record Issue(String classification, String code, String path, String action, boolean blocking) {
    }

    public RepositoryCompatibility {
        modules = List.copyOf(modules);
        generatedPaths = List.copyOf(generatedPaths);
        immutablePaths = List.copyOf(immutablePaths);
        issues = List.copyOf(issues);
    }

    public boolean supported() {
        return issues.stream().noneMatch(Issue::blocking);
    }

    public boolean preparationRequired() {
        return !generatedPaths.isEmpty();
    }
}
