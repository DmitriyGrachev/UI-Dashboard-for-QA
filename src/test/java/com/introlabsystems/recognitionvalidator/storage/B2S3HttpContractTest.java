package com.introlabsystems.recognitionvalidator.storage;

import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class B2S3HttpContractTest {

    private static final String REGION = "eu-central-003";
    private static final String BUCKET = "audit-bucket";
    private static final String OBJECT_KEY = "validator/image-id.png";
    private static final byte[] GET_BODY = "cloud-image".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private final AtomicReference<ResponseMode> responseMode =
            new AtomicReference<>(ResponseMode.SUCCESS);
    private final AtomicReference<RecordedRequest> lastRequest = new AtomicReference<>();

    private HttpServer server;
    private S3Client client;
    private S3Presigner presigner;
    private B2S3ObjectStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("audit-access-key", "audit-secret-key")
        );
        var s3 = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        client = S3Client.builder()
                .region(Region.of(REGION))
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3)
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
        presigner = S3Presigner.builder()
                .region(Region.of(REGION))
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3)
                .build();
        storage = new B2S3ObjectStorage(client, presigner, properties(endpoint));
    }

    @AfterEach
    void tearDown() {
        if (presigner != null) {
            presigner.close();
        }
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void realSdkUploadAndDownloadUsePathStyleSigV4AndStreamBytes() throws Exception {
        byte[] uploadBytes = new byte[64 * 1024];
        Arrays.fill(uploadBytes, (byte) 0x5A);
        Path source = tempDir.resolve("image.png");
        Files.write(source, uploadBytes);

        storage.upload(OBJECT_KEY, source);

        RecordedRequest upload = lastRequest.get();
        assertThat(upload.method()).isEqualTo("PUT");
        assertThat(upload.decodedPath()).isEqualTo("/" + BUCKET + "/" + OBJECT_KEY);
        assertThat(upload.contentType()).isEqualTo("image/png");
        assertThat(upload.authorization())
                .startsWith("AWS4-HMAC-SHA256 Credential=audit-access-key/")
                .contains("/" + REGION + "/s3/aws4_request")
                .contains("Signature=");
        assertThat(upload.contentEncoding()).contains("aws-chunked");
        assertThat(upload.decodedContentLength()).isEqualTo(String.valueOf(uploadBytes.length));
        assertThat(decodeAwsChunked(upload.body())).containsExactly(uploadBytes);

        CloudObjectStorage.CloudContent content = storage.open(OBJECT_KEY);
        assertThat(content.contentLength()).isEqualTo(GET_BODY.length);
        try (var stream = content.stream()) {
            assertThat(stream.readAllBytes()).containsExactly(GET_BODY);
        }

        RecordedRequest download = lastRequest.get();
        assertThat(download.method()).isEqualTo("GET");
        assertThat(download.decodedPath()).isEqualTo("/" + BUCKET + "/" + OBJECT_KEY);
        assertThat(download.authorization())
                .startsWith("AWS4-HMAC-SHA256 Credential=audit-access-key/")
                .contains("/" + REGION + "/s3/aws4_request");
    }

    @Test
    void realPresignerUsesPathStyleSigV4AndRequestedExpiration() {
        URI result = storage.presignGet(OBJECT_KEY, Duration.ofMinutes(15));
        Map<String, String> query = parseQuery(result.getRawQuery());

        assertThat(result.getRawPath()).isEqualTo("/" + BUCKET + "/" + OBJECT_KEY);
        assertThat(query)
                .containsEntry("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                .containsEntry("X-Amz-Expires", "900")
                .containsKey("X-Amz-Date")
                .containsKey("X-Amz-Signature");
        assertThat(query.get("X-Amz-Credential"))
                .startsWith("audit-access-key/")
                .contains("/" + REGION + "/s3/aws4_request");
    }

    @Test
    void realSdkClassifiesMissingAndUnavailableResponses() {
        responseMode.set(ResponseMode.MISSING);
        assertThatThrownBy(() -> storage.open(OBJECT_KEY))
                .isInstanceOf(NoSuchKeyException.class)
                .satisfies(exception -> assertThat(((NoSuchKeyException) exception).statusCode())
                        .isEqualTo(404));

        responseMode.set(ResponseMode.UNAVAILABLE);
        assertThatThrownBy(() -> storage.open(OBJECT_KEY))
                .isInstanceOf(S3Exception.class)
                .satisfies(exception -> assertThat(((S3Exception) exception).statusCode())
                        .isEqualTo(503));
    }

    @Test
    void realSdkHeadChecksObjectWithoutDownloadingIt() {
        assertThat(storage.exists(OBJECT_KEY)).isTrue();
        assertThat(lastRequest.get().method()).isEqualTo("HEAD");

        responseMode.set(ResponseMode.MISSING);
        assertThat(storage.exists(OBJECT_KEY)).isFalse();
        assertThat(lastRequest.get().method()).isEqualTo("HEAD");
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        lastRequest.set(new RecordedRequest(
                exchange.getRequestMethod(),
                URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Encoding"),
                exchange.getRequestHeaders().getFirst("x-amz-decoded-content-length"),
                requestBody
        ));

        if ("PUT".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("ETag", "\"audit-etag\"");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        if ("HEAD".equals(exchange.getRequestMethod())) {
            int status = switch (responseMode.get()) {
                case SUCCESS -> 200;
                case MISSING -> 404;
                case UNAVAILABLE -> 503;
            };
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }

        switch (responseMode.get()) {
            case SUCCESS -> send(exchange, 200, "image/png", GET_BODY);
            case MISSING -> sendError(exchange, 404, "NoSuchKey", "object is missing");
            case UNAVAILABLE -> sendError(exchange, 503, "SlowDown", "temporary failure");
        }
    }

    private static void sendError(
            HttpExchange exchange,
            int status,
            String code,
            String message
    ) throws IOException {
        byte[] body = ("<Error><Code>" + code + "</Code><Message>" + message
                + "</Message></Error>").getBytes(StandardCharsets.UTF_8);
        send(exchange, status, "application/xml", body);
    }

    private static void send(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] body
    ) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2
                            ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                            : ""
            );
        }
        return values;
    }

    private static byte[] decodeAwsChunked(byte[] encoded) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int cursor = 0;
        while (cursor < encoded.length) {
            int lineEnd = findCrlf(encoded, cursor);
            String chunkHeader = new String(
                    encoded,
                    cursor,
                    lineEnd - cursor,
                    StandardCharsets.US_ASCII
            );
            int separator = chunkHeader.indexOf(';');
            String sizeHex = separator < 0 ? chunkHeader : chunkHeader.substring(0, separator);
            int chunkSize = Integer.parseInt(sizeHex, 16);
            cursor = lineEnd + 2;
            if (chunkSize == 0) {
                return decoded.toByteArray();
            }
            decoded.write(encoded, cursor, chunkSize);
            cursor += chunkSize;
            if (encoded[cursor] != '\r' || encoded[cursor + 1] != '\n') {
                throw new IllegalArgumentException("Malformed aws-chunked body");
            }
            cursor += 2;
        }
        throw new IllegalArgumentException("Missing terminal aws-chunked frame");
    }

    private static int findCrlf(byte[] value, int start) {
        for (int index = start; index + 1 < value.length; index++) {
            if (value[index] == '\r' && value[index + 1] == '\n') {
                return index;
            }
        }
        throw new IllegalArgumentException("Malformed aws-chunked header");
    }

    private static B2StorageProperties properties(URI endpoint) {
        return new B2StorageProperties(
                true,
                endpoint,
                BUCKET,
                "audit-access-key",
                "audit-secret-key",
                "validator",
                1_000,
                8,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofDays(3),
                Duration.ofMinutes(30),
                Duration.ofDays(21)
        );
    }

    private enum ResponseMode {
        SUCCESS,
        MISSING,
        UNAVAILABLE
    }

    private record RecordedRequest(
            String method,
            String decodedPath,
            String contentType,
            String authorization,
            String contentEncoding,
            String decodedContentLength,
            byte[] body
    ) {
    }
}
