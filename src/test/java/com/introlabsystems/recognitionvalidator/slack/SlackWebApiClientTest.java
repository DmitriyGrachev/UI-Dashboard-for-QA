package com.introlabsystems.recognitionvalidator.slack;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SlackWebApiClientTest {

    private final SlackProperties properties = new SlackProperties(
            true, "xoxb-secret", "C123", 10, Duration.ofSeconds(2), Duration.ofSeconds(3),
            "https://slack.test/api"
    );

    @Test
    void postsAndUpdatesUsingBearerTokenAndSlackResponseTs() {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackWebApiClient client = new SlackWebApiClient(builder.build(), properties);
        server.expect(requestTo("https://slack.test/api/chat.postMessage"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer xoxb-secret"))
                .andExpect(jsonPath("$.channel").value("C123"))
                .andExpect(jsonPath("$.text").value("hello"))
                .andRespond(withSuccess("{\"ok\":true,\"ts\":\"123.456\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://slack.test/api/chat.update"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer xoxb-secret"))
                .andExpect(jsonPath("$.channel").value("C123"))
                .andExpect(jsonPath("$.ts").value("123.456"))
                .andExpect(jsonPath("$.text").value("updated"))
                .andRespond(withSuccess("{\"ok\":true,\"ts\":\"123.456\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(client.postMessage("hello")).isEqualTo("123.456");
        client.updateMessage("123.456", "updated");
        server.verify();
    }

    @Test
    void treatsHttp200OkFalseAsFailureAndExposesMessageNotFound() {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackWebApiClient client = new SlackWebApiClient(builder.build(), properties);
        server.expect(requestTo("https://slack.test/api/chat.update"))
                .andRespond(withSuccess("{\"ok\":false,\"error\":\"message_not_found\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.updateMessage("123.456", "updated"))
                .isInstanceOf(SlackApiException.class)
                .satisfies(error -> assertThat(((SlackApiException) error).errorCode())
                        .isEqualTo("message_not_found"));
        server.verify();
    }
}
