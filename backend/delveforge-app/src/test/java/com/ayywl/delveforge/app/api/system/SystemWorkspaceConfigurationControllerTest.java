package com.ayywl.delveforge.app.api.system;

import com.ayywl.delveforge.app.error.ApiExceptionHandler;
import com.ayywl.delveforge.application.port.workspace.WorkspaceStorageUnavailableException;
import com.ayywl.delveforge.infrastructure.workspace.WorkspaceProperties;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemWorkspaceConfigurationControllerTest {

    @Test
    void onlyShowsConfiguredRootsAndDoesNotProvideAWriteEndpoint() throws Exception {
        Path root = Path.of("target", "new-root").toAbsolutePath();
        Path old = Path.of("target", "old-root").toAbsolutePath();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SystemWorkspaceConfigurationController(
                new WorkspaceProperties(root, List.of(old)))).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/system/workspace-configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.root").value(root.toString()))
                .andExpect(jsonPath("$.existingRoots[0]").value(old.toString()));
        mvc.perform(post("/api/system/workspace-configuration").content("arbitrary path"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void storageFailureHasSpecificSafeErrorWithoutExceptionText() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingStorageController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/test-storage-failure")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("WORKSPACE_STORAGE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("private-path-marker"))));
    }

    @RestController
    static class FailingStorageController {
        @GetMapping("/test-storage-failure")
        public String fail() {
            throw new WorkspaceStorageUnavailableException("private-path-marker");
        }
    }
}
