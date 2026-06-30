package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebResearchServiceTest {

    /** Minimal fake provider for offline aggregation tests. */
    private static SearchProvider provider(String name, boolean enabled, List<WebSearchResult> results,
                                           RuntimeException toThrow) {
        return new SearchProvider() {
            @Override public String name() { return name; }
            @Override public boolean enabled() { return enabled; }
            @Override public List<WebSearchResult> search(String query, int maxResults) {
                if (toThrow != null) throw toThrow;
                return results;
            }
        };
    }

    @Test
    void mergesResultsFromAllEnabledProviders() {
        SearchProvider a = provider("a", true,
                List.of(new WebSearchResult("A1", "https://a/1", "s")), null);
        SearchProvider b = provider("b", true,
                List.of(new WebSearchResult("B1", "https://b/1", "s")), null);
        WebResearchService service = new WebResearchService(List.of(a, b));

        WebResearchService.Aggregated result = service.search("q", 5);

        assertEquals(2, result.results().size());
        assertEquals(List.of("a", "b"), result.providersUsed());
    }

    @Test
    void deduplicatesByUrlAcrossProviders() {
        SearchProvider a = provider("a", true,
                List.of(new WebSearchResult("Same", "https://x/1", "s")), null);
        SearchProvider b = provider("b", true,
                List.of(new WebSearchResult("Same again", "https://x/1/", "s")), null);
        WebResearchService service = new WebResearchService(List.of(a, b));

        WebResearchService.Aggregated result = service.search("q", 5);

        assertEquals(1, result.results().size(), "trailing-slash URLs are the same source");
    }

    @Test
    void skipsDisabledProviders() {
        SearchProvider enabled = provider("on", true,
                List.of(new WebSearchResult("T", "https://t", "s")), null);
        SearchProvider disabled = provider("off", false,
                List.of(new WebSearchResult("X", "https://x", "s")), null);
        WebResearchService service = new WebResearchService(List.of(enabled, disabled));

        WebResearchService.Aggregated result = service.search("q", 5);

        assertEquals(List.of("on"), result.providersUsed());
        assertEquals(List.of("on"), service.enabledProviders());
    }

    @Test
    void recordsNoteWhenProviderFailsButOthersStillWork() {
        SearchProvider good = provider("good", true,
                List.of(new WebSearchResult("Ok", "https://ok", "s")), null);
        SearchProvider bad = provider("bad", true, List.of(), new RuntimeException("HTTP 429"));
        WebResearchService service = new WebResearchService(List.of(good, bad));

        WebResearchService.Aggregated result = service.search("q", 5);

        assertEquals(1, result.results().size());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("bad") && n.contains("429")));
    }

    @Test
    void emptyQueryReturnsNoResults() {
        WebResearchService service = new WebResearchService(List.of());
        assertTrue(service.search("  ", 5).isEmpty());
        assertFalse(service.search("  ", 5).notes().isEmpty());
    }
}
