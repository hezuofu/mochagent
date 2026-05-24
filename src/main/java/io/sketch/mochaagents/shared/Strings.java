package io.sketch.mochaagents.shared;

public final class Strings {
    private Strings() {}

    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
