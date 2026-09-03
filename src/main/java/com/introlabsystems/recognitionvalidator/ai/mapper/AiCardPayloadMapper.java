package com.introlabsystems.recognitionvalidator.ai.mapper;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AiCardPayloadMapper {
    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("ace", "A"), Map.entry("two", "2"), Map.entry("three", "3"),
            Map.entry("four", "4"), Map.entry("five", "5"), Map.entry("six", "6"),
            Map.entry("seven", "7"), Map.entry("eight", "8"), Map.entry("nine", "9"),
            Map.entry("ten", "10"), Map.entry("jack", "J"), Map.entry("queen", "Q"), Map.entry("king", "K"));

    public String expected(String payloadRaw) {
        if (payloadRaw == null || payloadRaw.length() > 4096) throw invalid();
        List<String> cards = new ArrayList<>();
        boolean active = false;
        for (String raw : payloadRaw.split("_")) {
            String token = raw.trim().toLowerCase(Locale.ROOT);
            if (!active) {
                if (token.equals("u")) active = true;
                continue;
            }
            if (token.equals("u") || token.equals("d") || token.startsWith("b")) break;
            if (token.isEmpty()) continue;
            String named = NAMED.get(token);
            if (named != null) { cards.add(named); continue; }
            String compact = token.toUpperCase(Locale.ROOT);
            for (int i = 0; i < compact.length();) {
                if (compact.startsWith("10", i)) { cards.add("10"); i += 2; }
                else {
                    char rank = compact.charAt(i++);
                    if ("A23456789JQK".indexOf(rank) < 0) throw invalid();
                    cards.add(String.valueOf(rank));
                }
            }
        }
        if (cards.isEmpty()) throw invalid();
        String expected = String.join("", cards) + (cards.size() == 2 ? "" : ",");
        if (expected.length() > 512) throw invalid();
        return expected;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Recognition payload has no supported active hand");
    }
}
