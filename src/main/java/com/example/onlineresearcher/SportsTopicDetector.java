package com.example.onlineresearcher;

import java.util.List;
import java.util.Locale;

/**
 * Heuristic detector for "what's the score/result of a match" questions, so the {@code sports-score} skill
 * is applied only when relevant. It requires BOTH a score/result intent and a sports context, so unrelated
 * uses of the word "score" (credit score, test score, music score) do not trigger it.
 */
final class SportsTopicDetector {

    private SportsTopicDetector() {}

    /** Words signalling the user wants a score/result/outcome. */
    private static final List<String> INTENT = List.of(
            "score", "scoreline", "scoreboard", "final score", "who won", "who is winning",
            "what was the result", "result of", "how many goals", "winner of", "did .* win", "beat");

    /** Words signalling a sports/match context. */
    private static final List<String> SPORTS = List.of(
            "world cup", "match", "game", "fixture", "tournament", " vs ", " vs.", "versus",
            "premier league", "la liga", "serie a", "bundesliga", "champions league", "europa league",
            "nba", "nfl", "nhl", "mlb", "mls", "ipl", "olympics", "grand prix", "formula 1", "f1",
            "test match", "odi", "playoff", "final", "semifinal", "quarterfinal", "grand slam",
            "football", "soccer", "basketball", "baseball", "hockey", "cricket", "tennis", "rugby");

    /** True when {@code topic} reads like a request for a sports match score/result. */
    static boolean isSportsScore(String topic) {
        if (topic == null || topic.isBlank()) return false;
        String text = " " + topic.toLowerCase(Locale.ROOT) + " ";
        return matchesAny(text, INTENT) && matchesAny(text, SPORTS);
    }

    private static boolean matchesAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (needle.contains(".*")) {
                if (java.util.regex.Pattern.compile("\\b" + needle + "\\b").matcher(text).find()) return true;
            } else if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
