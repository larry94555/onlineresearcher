package com.example.onlineresearcher;

/**
 * A single web search result: a {@code title}, the destination {@code url}, and a short {@code snippet}.
 * Providers normalize their native shapes into this record so the research engine treats every source the
 * same way regardless of which keyless service produced it.
 */
public record WebSearchResult(String title, String url, String snippet) {
}
