package com.introlabsystems.recognitionvalidator.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record AiTask(String imageId, UUID claimId, URI url, @JsonProperty("image_name") String imageName,
                     String game, Instant leaseExpiresAt) {}
