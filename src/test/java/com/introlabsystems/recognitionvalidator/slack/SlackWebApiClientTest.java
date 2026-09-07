package com.introlabsystems.recognitionvalidator.slack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class SlackWebApiClientTest {

    private final SlackProperties properties = new SlackProperties(
            true, "xoxb-secret", "C123", 10, "", Duration.ofSeconds(2), Duration.ofSeconds(3),
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
                .satisfies(error -> {
                    SlackApiException exception = (SlackApiException) error;
                    assertThat(exception.errorCode()).isEqualTo("message_not_found");
                    assertThat(exception.statusCode()).isNull();
                    assertThat(exception.retryAfter()).isNull();
                    assertThat(exception.isRetryable()).isFalse();
                });
        server.verify();
    }

    @Test
    void exposesRetryAfterAndRetryableStatusForHttp429() {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackWebApiClient client = new SlackWebApiClient(builder.build(), properties);
        server.expect(requestTo("https://slack.test/api/chat.update"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "7")
                        .body("{\"ok\":false,\"error\":\"ratelimited\"}"));

        assertThatThrownBy(() -> client.updateMessage("123.456", "updated"))
                .isInstanceOf(SlackApiException.class)
                .satisfies(error -> {
                    SlackApiException exception = (SlackApiException) error;
                    assertThat(exception.statusCode()).isEqualTo(429);
                    assertThat(exception.retryAfter()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("xoxb-secret");
                });
        server.verify();
    }

    @Test
    void classifiesHttp5xxAsRetryableWithoutLeakingResponseText() {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackWebApiClient client = new SlackWebApiClient(builder.build(), properties);
        server.expect(requestTo("https://slack.test/api/chat.update"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .body("upstream token=xoxb-secret"));

        assertThatThrownBy(() -> client.updateMessage("123.456", "updated"))
                .isInstanceOf(SlackApiException.class)
                .satisfies(error -> {
                    SlackApiException exception = (SlackApiException) error;
                    assertThat(exception.statusCode()).isEqualTo(502);
                    assertThat(exception.retryAfter()).isNull();
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("xoxb-secret");
                });
        server.verify();
    }

    @Test
    void classifiesTransportTimeoutAsRetryableWithoutLeakingCauseText() {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackWebApiClient client = new SlackWebApiClient(builder.build(), properties);
        server.expect(requestTo("https://slack.test/api/chat.update"))
                .andRespond(withException(new SocketTimeoutException("token=xoxb-secret")));

        assertThatThrownBy(() -> client.updateMessage("123.456", "updated"))
                .isInstanceOf(SlackApiException.class)
                .satisfies(error -> {
                    SlackApiException exception = (SlackApiException) error;
                    assertThat(exception.statusCode()).isNull();
                    assertThat(exception.retryAfter()).isNull();
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("xoxb-secret");
                });
        server.verify();
    }

    @Test
    void classifiesTransportRequestErrorAsRetryableWithoutLeakingCauseText() {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackWebApiClient client = new SlackWebApiClient(builder.build(), properties);
        server.expect(requestTo("https://slack.test/api/chat.update"))
                .andRespond(withException(new IOException("token=xoxb-secret")));

        assertThatThrownBy(() -> client.updateMessage("123.456", "updated"))
                .isInstanceOf(SlackApiException.class)
                .satisfies(error -> {
                    SlackApiException exception = (SlackApiException) error;
                    assertThat(exception.statusCode()).isNull();
                    assertThat(exception.retryAfter()).isNull();
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("xoxb-secret");
                });
        server.verify();
    }

    @Test
    void ignoresNegativeRetryAfterWhileKeepingHttp429Retryable() {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackWebApiClient client = new SlackWebApiClient(builder.build(), properties);
        server.expect(requestTo("https://slack.test/api/chat.update"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "-1"));

        assertThatThrownBy(() -> client.updateMessage("123.456", "updated"))
                .isInstanceOf(SlackApiException.class)
                .satisfies(error -> {
                    SlackApiException exception = (SlackApiException) error;
                    assertThat(exception.statusCode()).isEqualTo(429);
                    assertThat(exception.retryAfter()).isNull();
                    assertThat(exception.isRetryable()).isTrue();
                });
        server.verify();
    }

    @Test
    void ignoresOversizedRetryAfterWhileKeepingHttp429Retryable() {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackWebApiClient client = new SlackWebApiClient(builder.build(), properties);
        server.expect(requestTo("https://slack.test/api/chat.update"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "2147483648"));

        assertThatThrownBy(() -> client.updateMessage("123.456", "updated"))
                .isInstanceOf(SlackApiException.class)
                .satisfies(error -> {
                    SlackApiException exception = (SlackApiException) error;
                    assertThat(exception.statusCode()).isEqualTo(429);
                    assertThat(exception.retryAfter()).isNull();
                    assertThat(exception.isRetryable()).isTrue();
                });
        server.verify();
    }

    @Test
    void keepsInvalidAuthPermanentAndDoesNotLeakToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackWebApiClient client = new SlackWebApiClient(builder.build(), properties);
        server.expect(requestTo("https://slack.test/api/chat.update"))
                .andRespond(withSuccess("{\"ok\":false,\"error\":\"invalid_auth\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.updateMessage("123.456", "updated"))
                .isInstanceOf(SlackApiException.class)
                .satisfies(error -> {
                    SlackApiException exception = (SlackApiException) error;
                    assertThat(exception.errorCode()).isEqualTo("invalid_auth");
                    assertThat(exception.statusCode()).isNull();
                    assertThat(exception.retryAfter()).isNull();
                    assertThat(exception.isRetryable()).isFalse();
                    assertThat(exception.getMessage()).doesNotContain("xoxb-secret");
                });
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ratelimited", "internal_error", "service_unavailable", "fatal_error", "update_failed"
    })
    void classifiesTransientSlackApiErrorsAsRetryable(String errorCode) {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackWebApiClient client = new SlackWebApiClient(builder.build(), properties);
        server.expect(requestTo("https://slack.test/api/chat.update"))
                .andRespond(withSuccess("{\"ok\":false,\"error\":\"" + errorCode + "\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.updateMessage("123.456", "updated"))
                .isInstanceOf(SlackApiException.class)
                .satisfies(error -> {
                    SlackApiException exception = (SlackApiException) error;
                    assertThat(exception.errorCode()).isEqualTo(errorCode);
                    assertThat(exception.statusCode()).isNull();
                    assertThat(exception.retryAfter()).isNull();
                    assertThat(exception.isRetryable()).isTrue();
                });
        server.verify();
    }

    @Test
    void treatsMalformedSlackResponseAsRetryableWithoutLeakingResponseText() {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackWebApiClient client = new SlackWebApiClient(builder.build(), properties);
        server.expect(requestTo("https://slack.test/api/chat.update"))
                .andRespond(withSuccess("upstream token=xoxb-secret",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.updateMessage("123.456", "updated"))
                .isInstanceOf(SlackApiException.class)
                .satisfies(error -> {
                    SlackApiException exception = (SlackApiException) error;
                    assertThat(exception.statusCode()).isNull();
                    assertThat(exception.retryAfter()).isNull();
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("xoxb-secret");
                });
        server.verify();
    }
}
