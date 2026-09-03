package com.introlabsystems.recognitionvalidator.ai.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.introlabsystems.recognitionvalidator.ai.dto.AiResult;
import com.introlabsystems.recognitionvalidator.ai.dto.AiTask;
import com.introlabsystems.recognitionvalidator.ai.exception.AiQueueException;
import com.introlabsystems.recognitionvalidator.ai.service.AiQueueService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/integration/ai/tasks")
public class AiTaskController {
    private final AiQueueService queue;
    private final ObjectMapper json;

    public AiTaskController(AiQueueService queue, ObjectMapper mapper) {
        this.queue = queue;
        this.json = mapper.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @PostMapping("/claim")
    public ResponseEntity<Claims> claim(@RequestParam(defaultValue = "1") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Claims(queue.claim(size)));
    }

    @PostMapping(value = "/{imageId}/result", consumes = MediaType.APPLICATION_JSON_VALUE)
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
    public record Claims(List<AiTask> items) {}
    public record Completed(String imageId, String status) {}
}
