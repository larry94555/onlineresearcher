package com.example.onlineresearcher;

import java.util.List;

/**
 * A single keyless web-search source (DuckDuckGo, SearXNG, You.com, Firecrawl, ...). Each provider
 * normalizes its native response into {@link WebSearchResult}s. The {@link WebResearchService} fans a
 * query out across every enabled provider and merges the results, so the agent gathers information from
 * all of them — exactly as the task requires — while degrading gracefully when one is unreachable.
 */
public interface SearchProvider {

    /** Stable identifier used in notes and citations (e.g. {@code duckduckgo-html}). */
    String name();

    /** Whether this provider is configured/enabled. Disabled providers are skipped silently. */
    boolean enabled();

    /**
     * Runs {@code query} and returns up to {@code maxResults} normalized results. Implementations should
     * throw on a hard failure (network/HTTP error) so the aggregator can record a diagnostic note; an
     * empty list means "reached the service, but it had nothing".
     */
    List<WebSearchResult> search(String query, int maxResults) throws Exception;
}
