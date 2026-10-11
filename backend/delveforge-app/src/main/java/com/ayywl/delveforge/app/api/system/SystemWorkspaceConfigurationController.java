package com.ayywl.delveforge.app.api.system;

import com.ayywl.delveforge.infrastructure.workspace.WorkspaceProperties;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 仅展示后端显式配置，不浏览目录、不检测文件、不允许浏览器修改存储授权。 */
@RestController
@RequestMapping("/api/system")
public final class SystemWorkspaceConfigurationController {

    private final WorkspaceProperties properties;

    public SystemWorkspaceConfigurationController(WorkspaceProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/workspace-configuration")
    public WorkspaceConfigurationResponse configuration() {
        return new WorkspaceConfigurationResponse(
                properties.root() == null ? null : properties.root().normalize().toString(),
                properties.existingRoots().stream().map(path -> path.normalize().toString()).toList());
    }

    public record WorkspaceConfigurationResponse(String root, List<String> existingRoots) {
    }
}
