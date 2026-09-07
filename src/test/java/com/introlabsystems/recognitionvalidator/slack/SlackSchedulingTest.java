package com.introlabsystems.recognitionvalidator.slack;

import org.junit.jupiter.api.Test;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SlackSchedulingTest {

    @Test
    void slackDeliveryRunsWhileTheApplicationSchedulerIsBusy() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ThreadPoolTaskSchedulerBuilder.class, ThreadPoolTaskSchedulerBuilder::new);
            context.registerBean(SlackProperties.class, () -> new SlackProperties(
                    false, "", "", 10, "", Duration.ofSeconds(2),
                    Duration.ofSeconds(3), "https://slack.test/api"));
            context.register(SlackConfiguration.class);
            context.refresh();
            var application = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            var slack = context.getBean("slackDeliveryScheduler", ThreadPoolTaskScheduler.class);
            try {
                application.execute(() -> {
                    busy.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(busy.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(slack.submit(() -> "delivered").get(2, TimeUnit.SECONDS))
                        .isEqualTo("delivered");
                assertThat(SlackNotificationScheduler.class.getMethod("drainOnce")
                        .getAnnotation(Scheduled.class).scheduler()).isEqualTo("slackDeliveryScheduler");
            } finally {
                release.countDown();
            }
        }
    }
}
