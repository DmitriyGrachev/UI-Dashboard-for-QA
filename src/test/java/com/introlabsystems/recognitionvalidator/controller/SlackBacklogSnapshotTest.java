package com.introlabsystems.recognitionvalidator.controller;

import com.introlabsystems.recognitionvalidator.slack.RejectedBacklogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;

class SlackBacklogSnapshotTest extends AbstractWebIntegrationTest {

    @Autowired
    private RejectedBacklogRepository backlog;

    @MockitoSpyBean
    private NamedParameterJdbcTemplate namedJdbc;

    @Test
    void countAndDetailsStayConsistentWhenAnExportCommitsBetweenTheirQueries() throws Exception {
        String id = insertRejectedExportImage(801, "snapshot.png", new byte[]{1});
        doAnswer(invocation -> {
            Object count = invocation.callRealMethod();
            // A separate thread gets a separate transaction, like a concurrent export request.
            CompletableFuture.runAsync(() -> jdbc.update(
                    "UPDATE review_task SET rejected_downloaded_at = now() WHERE image_id = ?", id))
                    .get(5, TimeUnit.SECONDS);
            return count;
        }).when(namedJdbc).queryForObject(startsWith("SELECT COUNT(*) "),
                any(SqlParameterSource.class), eq(Long.class));

        var snapshot = backlog.snapshot(10);

        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.items()).hasSize(1);
        assertThat(snapshot.items().getFirst().fileName()).isEqualTo("snapshot.png");
        assertThat(jdbc.queryForObject(
                "SELECT rejected_downloaded_at IS NOT NULL FROM review_task WHERE image_id = ?",
                Boolean.class, id)).isTrue();
    }
}
