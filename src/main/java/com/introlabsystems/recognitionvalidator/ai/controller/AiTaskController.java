package com.introlabsystems.recognitionvalidator.ai.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.introlabsystems.recognitionvalidator.ai.dto.AiResult;
import com.introlabsystems.recognitionvalidator.ai.dto.AiReject;
import com.introlabsystems.recognitionvalidator.ai.dto.AiTask;
import com.introlabsystems.recognitionvalidator.ai.exception.AiQueueException;
import com.introlabsystems.recognitionvalidator.ai.service.AiQueueService;
import com.introlabsystems.recognitionvalidator.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/integration/ai/tasks")
@Tag(name = "AI tasks")
@SecurityRequirement(name = OpenApiConfig.INTEGRATION_API_KEY)
public class AiTaskController {
    private final AiQueueService queue;
    private final ObjectMapper json;

    public AiTaskController(AiQueueService queue, ObjectMapper mapper) {
        this.queue = queue;
        this.json = mapper.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.json.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    @PostMapping("/claim")
    @Operation(summary = "Claim pending screenshots for AI review")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Claimed tasks", content = @Content(
                    schema = @Schema(implementation = Claims.class), examples = @ExampleObject(value =
                    "{\"items\":[{\"imageId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"claimId\":\"314fd2b3-0e1a-46e8-a974-a5a99d40a01b\",\"url\":\"https://validator.example/api/integration/images/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/content?expires=1788948300&signature=...\",\"imageName\":\"frame.png\",\"game\":\"bj_igt\",\"leaseExpiresAt\":\"2026-09-09T10:05:00Z\"}]}"))),
            @ApiResponse(responseCode = "400", description = "Invalid size", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
            @ApiResponse(responseCode = "503", description = "Integration, database, or image delivery unavailable", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class)))
    })
    public ResponseEntity<Claims> claim(@Parameter(description = "Number of tasks (1–20)", example = "5", in = ParameterIn.QUERY)
                                        @RequestParam(defaultValue = "1") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Claims(queue.claim(size)));
    }

    @PostMapping(value = "/{imageId}/result", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Submit an AI review result")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            schema = @Schema(implementation = AiResult.class), examples = @ExampleObject(name = "match", value =
            "{\"claimId\":\"314fd2b3-0e1a-46e8-a974-a5a99d40a01b\",\"valid\":true,\"verdict\":\"MATCH\",\"certainty\":97,\"confidence\":94,\"message\":\"Cards match\"}")))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Result accepted or already accepted identically", content = @Content(schema = @Schema(implementation = Completed.class))),
            @ApiResponse(responseCode = "400", description = "Invalid image ID, JSON, or result fields", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
            @ApiResponse(responseCode = "404", description = "AI task not found", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class))),
            @ApiResponse(responseCode = "409", description = "Expired/replaced claim or conflicting result", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class))),
            @ApiResponse(responseCode = "413", description = "Body exceeds 16 KiB", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class))),
            @ApiResponse(responseCode = "503", description = "Database unavailable", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class)))
    })
    public ResponseEntity<Completed> result(@PathVariable String imageId, HttpServletRequest request) throws IOException {
        if (!imageId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid image ID");
        byte[] body = request.getInputStream().readNBytes(16 * 1024 + 1);
        if (body.length > 16 * 1024) throw new AiQueueException(HttpStatus.PAYLOAD_TOO_LARGE, "RESULT_TOO_LARGE", "Result exceeds 16 KiB");
        AiResult result;
        try { result = json.readValue(body, AiResult.class); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid AI result fields or JSON"); }
        if (result == null) throw new IllegalArgumentException("Result is required");
        queue.complete(imageId, result);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Completed(imageId, "COMPLETED"));
    }

    @PostMapping(value = "/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Reject an unusable AI task without creating a mismatch")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            schema = @Schema(implementation = AiReject.class), examples = @ExampleObject(value =
            "{\"imageId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"claimId\":\"314fd2b3-0e1a-46e8-a974-a5a99d40a01b\",\"message\":\"Image could not be decoded\"}")))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task rejected or already rejected identically", content = @Content(schema = @Schema(implementation = Completed.class))),
            @ApiResponse(responseCode = "400", description = "Invalid JSON or reject fields", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
            @ApiResponse(responseCode = "404", description = "AI task not found", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class))),
            @ApiResponse(responseCode = "409", description = "Expired/replaced claim or completed task", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class))),
            @ApiResponse(responseCode = "413", description = "Body exceeds 16 KiB", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class))),
            @ApiResponse(responseCode = "503", description = "Database unavailable", content = @Content(schema = @Schema(implementation = AiExceptionHandler.Error.class)))
    })
    public ResponseEntity<Completed> reject(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(16 * 1024 + 1);
        if (body.length > 16 * 1024) {
            throw new AiQueueException(HttpStatus.PAYLOAD_TOO_LARGE, "REJECT_TOO_LARGE", "Reject exceeds 16 KiB");
        }
        AiReject reject;
        try { reject = json.readValue(body, AiReject.class); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid AI reject fields or JSON"); }
        if (reject == null) throw new IllegalArgumentException("Reject is required");
        queue.reject(reject);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Completed(reject.imageId(), "REJECTED"));
    }
    public record Claims(List<AiTask> items) {}
    public record Completed(String imageId, String status) {}
}
