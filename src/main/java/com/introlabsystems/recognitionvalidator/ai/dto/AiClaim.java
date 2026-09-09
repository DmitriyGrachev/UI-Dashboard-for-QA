package com.introlabsystems.recognitionvalidator.ai.dto;

import java.time.Instant;
import java.util.UUID;

public record AiClaim(String imageId, UUID claimId, String imageName, String gameCode, Instant leaseExpiresAt) {}
