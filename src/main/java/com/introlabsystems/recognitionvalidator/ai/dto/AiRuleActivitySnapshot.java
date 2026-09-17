package com.introlabsystems.recognitionvalidator.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AiRuleActivitySnapshot(long revision, Instant generatedAt, List<UUID> lastIssuedRuleIds,
                                     List<AiRuleActivity> rules) {
    public static AiRuleActivitySnapshot from(long revision, Instant now, List<AiRuleActivity> rules) {
        Instant last = rules.stream().map(AiRuleActivity::lastIssuedAt).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        var latest = last == null ? List.<UUID>of() : rules.stream()
                .filter(rule -> last.equals(rule.lastIssuedAt())).map(AiRuleActivity::ruleId).toList();
        return new AiRuleActivitySnapshot(revision, now, latest, List.copyOf(rules));
    }
}
