package com.example.onlineresearcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Keyless web search via a SearXNG instance's JSON API ({@code <base>/search?q=...&format=json}). SearXNG
 * is a privacy-preserving metasearch engine that aggregates Google, Bing, DuckDuckGo, Reddit and more,
 * and needs no API key. Point {@code web.search.searxng-url} at your own or a public instance; if it is
 * blank or unreachable, the provider degrades gracefully and the agent uses the others.
 *
 * <p>The network call sits behind an injectable {@link JsonFetcher} so the JSON parsing is unit-tested
 * offline.
 */
@Component
public class SearxngSearchProvider implements SearchProvider {

    /** Fetches the JSON body at a URL. Swappable so tests can return a canned document. */
    @FunctionalInterface
    public interface JsonFetcher {
        String fetch(String url) throws Exception;
    }

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final boolean enabled;
    private final JsonFetcher fetcher;

    @Autowired
    public SearxngSearchProvider(
            ObjectMapper mapper,
            @Value("${web.search.searxng-url:}") String baseUrl,
            @Value("${web.search.searxng-enabled:true}") boolean enabled,
            @Value("${web.search.timeout-seconds:20}") int timeoutSeconds,
            @Value("${web.search.max-response-bytes:1048576}") int maxResponseBytes) {
        this(mapper, baseUrl, enabled, defaultFetcher(timeoutSeconds, maxResponseBytes));
    }

    SearxngSearchProvider(ObjectMapper mapper, String baseUrl, boolean enabled, JsonFetcher fetcher) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.enabled = enabled;
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public boolean enabled() {
        return enabled && !baseUrl.isBlank();
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) throws Exception {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url = trimmed + "/search?format=json&q="
                + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        return parse(mapper, fetcher.fetch(url), maxResults);
    }

    /** Parses a SearXNG JSON response into results. Package-private for offline unit testing. */
    static List<WebSearchResult> parse(ObjectMapper mapper, String json, int maxResults) throws Exception {
        List<WebSearchResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) return results;
        JsonNode root = mapper.readTree(json);
        JsonNode array = root.path("results");
        if (!array.isArray()) return results;
        for (JsonNode item : array) {
            String title = item.path("title").asText("").trim();
            String link = item.path("url").asText("").trim();
            String snippet = item.path("content").asText("").trim();
            if (link.isBlank() && title.isBlank()) continue;
            results.add(new WebSearchResult(title, link, snippet));
            if (results.size() >= maxResults) break;
        }
        return results;
    }

    private static JsonFetcher defaultFetcher(int timeoutSeconds, int maxResponseBytes) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        int limit = Math.max(1, maxResponseBytes);
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) onlineresearcher/0.1 web_search")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            try (InputStream stream = response.body()) {
                return new String(stream.readNBytes(limit), StandardCharsets.UTF_8);
            }
        };
    }
}
