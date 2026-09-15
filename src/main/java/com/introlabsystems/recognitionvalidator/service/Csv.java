package com.introlabsystems.recognitionvalidator.service;

public final class Csv {
    private Csv() {}

    public static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        int start = 0;
        while (start < text.length() && (Character.isWhitespace(text.charAt(start))
                || Character.isSpaceChar(text.charAt(start)) || text.charAt(start) == '\uFEFF')) start++;
        // Keep untrusted strings as text even when a spreadsheet ignores leading whitespace.
        if (start < text.length() && "=+@-＝＋＠－".indexOf(text.charAt(start)) >= 0
                || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
