package com.example.onlineresearcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fans a search query out across every enabled {@link SearchProvider} and merges the results. The task
 * asks the agent to "gather information from all sources" — this service is where that happens: it queries
 * DuckDuckGo, SearXNG, You.com and Firecrawl, deduplicates by URL (keeping the first/most relevant hit),
 * and records a per-provider note so a provider that is down or empty is visible rather than silent.
 *
 * <p>Every provider is best-effort: a failing or unreachable provider contributes a note and is skipped,
 * so research keeps working with whichever sources responded.
 */
@Component
public class WebResearchService {
    private static final Logger log = LoggerFactory.getLogger(WebResearchService.class);

    /** The merged outcome of one query across all providers. */
    public record Aggregated(List<WebSearchResult> results, List<String> providersUsed, List<String> notes) {
        public boolean isEmpty() {
            return results.isEmpty();
        }
    }

    private final List<SearchProvider> providers;

    public WebResearchService(List<SearchProvider> providers) {
        this.providers = providers == null ? List.of() : providers;
    }

    /** All provider names that are currently enabled (for diagnostics and the README's status line). */
    public List<String> enabledProviders() {
        List<String> names = new ArrayList<>();
        for (SearchProvider provider : providers) {
            if (provider.enabled()) names.add(provider.name());
        }
        return names;
    }

    /**
     * Runs {@code query} on every enabled provider, returning up to {@code perProvider} results from each,
     * deduplicated across providers by URL.
     */
    public Aggregated search(String query, int perProvider) {
        Map<String, WebSearchResult> byUrl = new LinkedHashMap<>();
        List<String> used = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return new Aggregated(List.of(), used, List.of("empty query"));
        }
        int limit = Math.max(1, perProvider);
        for (SearchProvider provider : providers) {
            if (!provider.enabled()) continue;
            try {
                List<WebSearchResult> results = provider.search(query.strip(), limit);
                int added = 0;
                for (WebSearchResult result : results) {
                    String key = dedupKey(result);
                    if (key.isBlank()) continue;
                    if (byUrl.putIfAbsent(key, result) == null) added++;
                }
                used.add(provider.name());
                notes.add(provider.name() + ": " + results.size() + " result(s), " + added + " new");
            } catch (Exception failure) {
                String message = failure.getMessage();
                notes.add(provider.name() + ": " + (message == null || message.isBlank()
                        ? failure.getClass().getSimpleName() : message));
                log.warn("[research] provider {} failed for '{}': {}", provider.name(), query, message);
            }
        }
        return new Aggregated(new ArrayList<>(byUrl.values()), used, notes);
    }

    private static String dedupKey(WebSearchResult result) {
        String url = result.url() == null ? "" : result.url().trim().toLowerCase(Locale.ROOT);
        if (!url.isBlank()) {
            // Normalize trailing slash so http://x/ and http://x are treated as one source.
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return result.title() == null ? "" : result.title().trim().toLowerCase(Locale.ROOT);
    }
}
