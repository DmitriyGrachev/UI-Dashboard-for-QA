package com.introlabsystems.recognitionvalidator.web;

import com.introlabsystems.recognitionvalidator.auth.OperatorPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "validator.count-remaining-screenshots=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RemainingCountDisabledWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void remainingCounterIsNotRenderedWhenFeatureIsDisabled() throws Exception {
        OperatorPrincipal operator = new OperatorPrincipal(
                UUID.randomUUID(),
                "operator",
                "unused",
                true
        );

        mockMvc.perform(get("/review").with(user(operator)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"remaining-count\""))))
                .andExpect(content().string(not(containsString("Matching screenshots"))));
    }
}
