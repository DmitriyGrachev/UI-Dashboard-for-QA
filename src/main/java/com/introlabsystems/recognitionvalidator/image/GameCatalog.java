package com.introlabsystems.recognitionvalidator.image;

import com.introlabsystems.recognitionvalidator.config.ValidatorProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class GameCatalog {

    private final List<String> games;

    @Autowired
    public GameCatalog(ValidatorProperties properties) {
        this(properties.games());
    }

    GameCatalog(List<String> games) {
        this.games = games.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    public Optional<String> match(String fileName) {
        return games.stream()
                .filter(game -> fileName.startsWith(game + "_"))
                .findFirst();
    }
}
