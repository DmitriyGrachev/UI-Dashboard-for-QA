package com.introlabsystems.recognitionvalidator.ai.dto;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record AiTask(String imageId, UUID claimId, URI url, String expected, String game, Instant leaseExpiresAt) {}
