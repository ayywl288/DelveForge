package com.ayywl.delveforge.workbench;

import com.ayywl.delveforge.application.workbench.ConversationAgent;
import com.ayywl.delveforge.application.workbench.ProjectFiles.FolderPickerUnavailable;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SourceFolderSelectionTest {
    private final ConversationAgent agent = mock(ConversationAgent.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WorkbenchController(null, agent, null, null, 300)).build();

    @Test
    void returnsSelectedPathWithoutImporting() throws Exception {
        String folder = "D:\\开发\\项目 甲";
        when(agent.chooseSourceFolder()).thenReturn(Optional.of(folder));
        mvc.perform(post("/api/workbench/projects/choose-folder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(folder));
        verify(agent).chooseSourceFolder();
        verifyNoMoreInteractions(agent);
    }

    @Test
    void cancellationIsSuccessfulAndDesktopFailureIsRetryable() throws Exception {
        when(agent.chooseSourceFolder()).thenReturn(Optional.empty())
                .thenThrow(new FolderPickerUnavailable(new IOException()));
        mvc.perform(post("/api/workbench/projects/choose-folder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").isEmpty());
        mvc.perform(post("/api/workbench/projects/choose-folder"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
