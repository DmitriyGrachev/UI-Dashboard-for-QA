package com.introlabsystems.recognitionvalidator.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.introlabsystems.recognitionvalidator.ai.config.AiQueueProperties;
import com.introlabsystems.recognitionvalidator.ai.dto.*;
import com.introlabsystems.recognitionvalidator.ai.repository.*;
import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import com.introlabsystems.recognitionvalidator.security.SecurityConfig;
import com.introlabsystems.recognitionvalidator.security.SecurityFailureHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AiSettingsController.class)
@Import({SecurityConfig.class, SecurityFailureHandler.class})
class AiRulePreviewWebTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AiSettingsRepository settings;
    @MockitoBean AiTaskRepository tasks;
    @MockitoBean AiQueueProperties queue;
    @MockitoBean ValidatorProperties validator;
    @MockitoBean UserDetailsService users;

    @Test
    void validatesDraftRequiresAdminAndCsrfAndNeverSavesSettings() throws Exception {
        when(validator.games()).thenReturn(List.of("bj_igt"));
        var draft = new AiSettings(99, true, List.of(new AiRule(null, "Unsaved", true, 1, "bj_igt",
                null, null, 0L, "private-session", null)));
        var now = Instant.parse("2026-10-01T00:00:00Z");
        when(tasks.preview(draft, 1)).thenReturn(new AiRulePreview(now, true,
                List.of(new AiRulePreview.Item("a".repeat(64), "screen.png", "bj_igt", now)), false));
        String url = "/admin/api/ai-queue/settings/rules/1/preview", body = mapper.writeValueAsString(draft);
        mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(user("operator").roles("OPERATOR")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(tasks);
        mvc.perform(post(url).with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].gameCode").value("bj_igt")).andExpect(jsonPath("$.hasMore").value(false));
        for (String invalid : List.of(body.replace("bj_igt", "unknown"), body.replace("\"priority\":1", "\"priority\":2"), "{}")) {
            mvc.perform(post(url).with(user("admin").roles("ADMIN")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        verify(tasks).preview(draft, 1);
        verifyNoMoreInteractions(tasks);
        verifyNoInteractions(settings);
    }
}
