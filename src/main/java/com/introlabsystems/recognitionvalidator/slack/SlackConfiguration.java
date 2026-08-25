package com.introlabsystems.recognitionvalidator.slack;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class SlackConfiguration {

    @Bean(name = "slackNotificationExecutor")
    Executor slackNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setThreadNamePrefix("slack-notification-");
        executor.initialize();
        return executor;
    }

    @Bean
    RestClient slackRestClient(SlackProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(properties.connectTimeout().toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(properties.readTimeout().toMillis()));
        return RestClient.builder()
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    SlackWebApiClient slackWebApiClient(RestClient slackRestClient, SlackProperties properties) {
        return new SlackWebApiClient(slackRestClient, properties);
    }

    @Bean
    SlackMessageFormatter slackMessageFormatter() {
        return new SlackMessageFormatter();
    }
}
