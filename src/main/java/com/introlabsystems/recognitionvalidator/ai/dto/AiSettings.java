package com.introlabsystems.recognitionvalidator.ai.dto;

import java.util.Comparator;
import java.util.List;

public record AiSettings(long revision, boolean enabled, List<AiRule> rules) {
    public AiSettings {
        if (revision < 0 || rules == null || rules.size() > 20 || rules.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Settings require a revision and at most 20 rules");
        }
        if (rules.stream().map(AiRule::id).distinct().count() != rules.size()) {
            throw new IllegalArgumentException("Rule IDs must be unique");
        }
        if (enabled && rules.stream().noneMatch(AiRule::enabled)) {
            throw new IllegalArgumentException("Enable at least one selection rule first");
        }
        rules = rules.stream().sorted(Comparator.comparingInt(AiRule::priority)
                .thenComparing(rule -> rule.id().toString())).toList();
    }
}
