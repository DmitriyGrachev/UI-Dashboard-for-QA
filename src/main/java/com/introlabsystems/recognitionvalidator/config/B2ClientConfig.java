package com.introlabsystems.recognitionvalidator.config;

import com.introlabsystems.recognitionvalidator.storage.B2S3ObjectStorage;
import com.introlabsystems.recognitionvalidator.storage.CloudObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "validator.b2", name = "enabled", havingValue = "true")
public class B2ClientConfig {

    @Bean(destroyMethod = "close")
    public S3Client b2S3Client(B2StorageProperties properties) {
        return S3Client.builder()
                .region(properties.region())
                .endpointOverride(properties.endpoint())
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(s3Configuration())
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner b2S3Presigner(B2StorageProperties properties) {
        return S3Presigner.builder()
                .region(properties.region())
                .endpointOverride(properties.endpoint())
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(s3Configuration())
                .build();
    }

    @Bean
    public CloudObjectStorage b2ObjectStorage(
            S3Client b2S3Client,
            S3Presigner b2S3Presigner,
            B2StorageProperties properties
    ) {
        return new B2S3ObjectStorage(b2S3Client, b2S3Presigner, properties);
    }

    @Bean(name = "b2UploadExecutor", destroyMethod = "shutdown")
    public ExecutorService b2UploadExecutor(B2StorageProperties properties) {
        return new ThreadPoolExecutor(
                properties.uploadConcurrency(),
                properties.uploadConcurrency(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.uploadBatchSize())),
                new B2UploadThreadFactory()
        );
    }

    private static StaticCredentialsProvider credentials(B2StorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey())
        );
    }

    private static S3Configuration s3Configuration() {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
    }

    private static final class B2UploadThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "b2-upload-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
