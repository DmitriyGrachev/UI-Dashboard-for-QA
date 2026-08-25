package com.introlabsystems.recognitionvalidator.storage;

import com.introlabsystems.recognitionvalidator.config.B2StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class B2S3ObjectStorageTest {

    private static final URI ENDPOINT = URI.create("https://s3.eu-central-003.backblazeb2.com");

    @TempDir
    Path tempDir;

    @Test
    void uploadsFileAsPngPutWithConfiguredBucketAndKey() throws Exception {
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        Path source = tempDir.resolve("image.png");
        Files.write(source, new byte[]{1, 2, 3, 4});
        doAnswer(invocation -> {
            PutObjectRequest request = invocation.getArgument(0);
            RequestBody body = invocation.getArgument(1);
            assertThat(request.contentType()).isEqualTo("image/png");
            assertThat(body.contentLength()).isEqualTo(4);
            assertThat(body.contentStreamProvider().newStream().readAllBytes())
                    .containsExactly(1, 2, 3, 4);
            return null;
        }).when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        new B2S3ObjectStorage(client, presigner, properties()).upload("object-key", source);

        var request = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("bucket");
        assertThat(request.getValue().key()).isEqualTo("object-key");
    }

    @Test
    void presignsConfiguredBucketAndKeyWithRequestedTtl() throws Exception {
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        PresignedGetObjectRequest signed = mock(PresignedGetObjectRequest.class);
        URL url = URI.create("https://download.example/object-key").toURL();
        when(signed.url()).thenReturn(url);
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(signed);

        URI result = new B2S3ObjectStorage(client, presigner, properties())
                .presignGet("object-key", Duration.ofMinutes(10));

        var request = org.mockito.ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(request.capture());
        assertThat(result).isEqualTo(URI.create("https://download.example/object-key"));
        assertThat(request.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(request.getValue().getObjectRequest().bucket()).isEqualTo("bucket");
        assertThat(request.getValue().getObjectRequest().key()).isEqualTo("object-key");
    }

    @Test
    void opensGetResponseStreamAndReportsContentLength() {
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        @SuppressWarnings("unchecked")
        ResponseInputStream<GetObjectResponse> responseStream = mock(ResponseInputStream.class);
        GetObjectResponse response = GetObjectResponse.builder().contentLength(4L).build();
        when(responseStream.response()).thenReturn(response);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

        CloudObjectStorage.CloudContent content = new B2S3ObjectStorage(client, presigner, properties())
                .open("object-key");

        assertThat(content.stream()).isSameAs(responseStream);
        assertThat(content.contentLength()).isEqualTo(4L);
    }

    @Test
    void propagatesConfirmedMissingObject() {
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        NoSuchKeyException missing = NoSuchKeyException.builder().message("missing").build();
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(missing);

        assertThatThrownBy(() -> new B2S3ObjectStorage(client, presigner, properties()).open("object-key"))
                .isSameAs(missing);
    }

    @Test
    void checksObjectExistenceWithoutDownloadingIt() {
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        boolean exists = new B2S3ObjectStorage(client, presigner, properties())
                .exists("object-key");

        var request = org.mockito.ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(client).headObject(request.capture());
        assertThat(exists).isTrue();
        assertThat(request.getValue().bucket()).isEqualTo("bucket");
        assertThat(request.getValue().key()).isEqualTo("object-key");
    }

    @Test
    void reportsConfirmedMissingObjectWithoutFailingTheRecoveryBatch() {
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        assertThat(new B2S3ObjectStorage(client, presigner, properties())
                .exists("object-key")).isFalse();
    }

    @Test
    void propagatesTransientSdkFailureForCallerRetry() throws Exception {
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        SdkException transientFailure = SdkClientException.create("temporary failure");
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(transientFailure);
        Path source = tempDir.resolve("image.png");
        Files.write(source, new byte[]{1});

        assertThatThrownBy(() -> new B2S3ObjectStorage(client, presigner, properties())
                .upload("object-key", source))
                .isSameAs(transientFailure);
    }

    private static B2StorageProperties properties() {
        return new B2StorageProperties(
                true,
                ENDPOINT,
                "bucket",
                "access",
                "secret",
                "screenshots",
                100,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofMinutes(15),
                Duration.ofDays(21),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(45),
                Duration.ofMinutes(2),
                4
        );
    }
}
