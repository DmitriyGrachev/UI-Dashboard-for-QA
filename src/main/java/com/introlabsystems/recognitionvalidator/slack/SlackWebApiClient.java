package com.introlabsystems.recognitionvalidator.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

public class SlackWebApiClient {

    private final RestClient restClient;
    private final SlackProperties properties;

    public SlackWebApiClient(RestClient restClient, SlackProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public String postMessage(String text) {
        SlackApiResponse response = call("chat.postMessage", Map.of(
                "channel", properties.channelId(),
                "text", text
        ));
        if (!response.ok() || response.ts() == null || response.ts().isBlank()) {
            throw failure(response, "chat.postMessage did not return a message timestamp");
        }
        return response.ts();
    }

    public void updateMessage(String messageTs, String text) {
        SlackApiResponse response = call("chat.update", Map.of(
                "channel", properties.channelId(),
                "ts", messageTs,
                "text", text
        ));
        if (!response.ok()) {
            throw failure(response, "chat.update failed");
        }
    }

    private SlackApiResponse call(String method, Map<String, String> body) {
        try {
            SlackApiResponse response = restClient.post()
                    .uri("/" + method)
                    .header("Authorization", "Bearer " + properties.botToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(SlackApiResponse.class);
            if (response == null) {
                throw new SlackApiException(
                        method + " returned an empty response", (String) null
                );
            }
            return response;
        } catch (SlackApiException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            int statusCode = exception.getStatusCode().value();
            HttpHeaders headers = exception.getResponseHeaders();
            Duration retryAfter = parseRetryAfter(
                    headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER));
            boolean retryable = statusCode == 429 || (statusCode >= 500 && statusCode < 600);
            throw new SlackApiException(
                    method + " request failed with HTTP " + statusCode,
                    null,
                    statusCode,
                    retryAfter,
                    retryable,
                    exception
            );
        } catch (RuntimeException exception) {
            throw new SlackApiException(method + " request failed", exception);
        }
    }

    private Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        try {
            int seconds = Integer.parseInt(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                Instant deadline = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                Duration delay = Duration.between(Instant.now(), deadline);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException ignoredDate) {
                return null;
            }
        }
    }

    private SlackApiException failure(SlackApiResponse response, String fallback) {
        String error = response.error() == null || response.error().isBlank()
                ? fallback
                : response.error();
        return new SlackApiException(
                fallback + ": " + error,
                response.error(),
                null,
                null,
                isTransientApiError(response.error())
        );
    }

    private boolean isTransientApiError(String errorCode) {
        return "ratelimited".equals(errorCode)
                || "internal_error".equals(errorCode)
                || "service_unavailable".equals(errorCode)
                || "fatal_error".equals(errorCode)
                || "update_failed".equals(errorCode);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SlackApiResponse(boolean ok, String ts, String error) {
    }
}
