package com.introlabsystems.recognitionvalidator.slack;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

@Configuration
public class SlackConfiguration {

    @Bean
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean
    ThreadPoolTaskScheduler slackDeliveryScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(1).threadNamePrefix("slack-delivery-").build();
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
