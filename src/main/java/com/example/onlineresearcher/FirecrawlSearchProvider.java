package com.example.onlineresearcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyless web search/scrape via Firecrawl's {@code /v1/search} endpoint. Firecrawl converts pages to clean
 * Markdown, which is ideal when the agent needs to read article/documentation content rather than just
 * find URLs. Firecrawl's keyless allowance is limited, so this provider is disabled by default
 * ({@code web.search.firecrawl-enabled=false}); enable it (and optionally point at your own endpoint) when
 * you want deep-read content. Any failure degrades gracefully.
 *
 * <p>The network call sits behind an injectable {@link JsonFetcher} so JSON parsing is unit-tested offline.
 */
@Component
public class FirecrawlSearchProvider implements SearchProvider {

    /** Posts a JSON body to a URL and returns the JSON response. Swappable for offline tests. */
    @FunctionalInterface
    public interface JsonFetcher {
        String fetch(String url, String jsonBody) throws Exception;
    }

    private final ObjectMapper mapper;
    private final String endpoint;
    private final boolean enabled;
    private final JsonFetcher fetcher;

    @Autowired
    public FirecrawlSearchProvider(
            ObjectMapper mapper,
            @Value("${web.search.firecrawl-url:https://api.firecrawl.dev/v1/search}") String endpoint,
            @Value("${web.search.firecrawl-enabled:false}") boolean enabled,
            @Value("${web.search.timeout-seconds:20}") int timeoutSeconds,
            @Value("${web.search.max-response-bytes:1048576}") int maxResponseBytes) {
        this(mapper, endpoint, enabled, defaultFetcher(timeoutSeconds, maxResponseBytes));
    }

    FirecrawlSearchProvider(ObjectMapper mapper, String endpoint, boolean enabled, JsonFetcher fetcher) {
        this.mapper = mapper;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.enabled = enabled;
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return "firecrawl";
    }

    @Override
    public boolean enabled() {
        return enabled && !endpoint.isBlank();
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", query == null ? "" : query);
        request.put("limit", Math.max(1, maxResults));
        String body = mapper.writeValueAsString(request);
        return parse(mapper, fetcher.fetch(endpoint, body), maxResults);
    }

    /** Parses a Firecrawl search response into results. Package-private for offline unit testing. */
    static List<WebSearchResult> parse(ObjectMapper mapper, String json, int maxResults) throws Exception {
        List<WebSearchResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) return results;
        JsonNode root = mapper.readTree(json);
        JsonNode array = root.has("data") ? root.path("data") : root.path("results");
        if (!array.isArray()) return results;
        for (JsonNode item : array) {
            String title = item.path("title").asText("").trim();
            String link = item.path("url").asText("").trim();
            String snippet = firstNonBlank(
                    item.path("description").asText("").trim(),
                    item.path("snippet").asText("").trim(),
                    truncate(item.path("markdown").asText("").trim(), 400));
            if (link.isBlank() && title.isBlank()) continue;
            results.add(new WebSearchResult(title, link, snippet));
            if (results.size() >= maxResults) break;
        }
        return results;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static JsonFetcher defaultFetcher(int timeoutSeconds, int maxResponseBytes) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        int limit = Math.max(1, maxResponseBytes);
        return (url, jsonBody) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) onlineresearcher/0.1 web_search")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
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
