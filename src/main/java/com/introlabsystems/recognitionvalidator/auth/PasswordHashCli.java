package com.introlabsystems.recognitionvalidator.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class PasswordHashCli {

    private PasswordHashCli() {
    }

    public static void main(String[] args) {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("Usage: PasswordHashCli <password>");
            System.exit(1);
        }
        System.out.println(new BCryptPasswordEncoder(12).encode(args[0]));
    }
}
