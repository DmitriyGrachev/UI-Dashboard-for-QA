package com.introlabsystems.recognitionvalidator.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

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
        } catch (RuntimeException exception) {
            throw new SlackApiException(method + " request failed", exception);
        }
    }

    private SlackApiException failure(SlackApiResponse response, String fallback) {
        String error = response.error() == null || response.error().isBlank()
                ? fallback
                : response.error();
        return new SlackApiException(fallback + ": " + error, response.error());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SlackApiResponse(boolean ok, String ts, String error) {
    }
}
