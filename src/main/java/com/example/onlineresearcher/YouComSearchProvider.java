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
 * Keyless web search via You.com's public search endpoint, which returns LLM-ready structured snippets.
 * You.com's keyless tier is rate limited (about 100 queries/day), so this provider is disabled by default
 * ({@code web.search.youcom-enabled=false}); enable it when you want structured answers in the mix. Any
 * failure degrades gracefully and the agent falls back to the other providers.
 *
 * <p>The network call sits behind an injectable {@link JsonFetcher} so JSON parsing is unit-tested offline.
 */
@Component
public class YouComSearchProvider implements SearchProvider {

    /** Fetches the JSON body at a URL. Swappable so tests can return a canned document. */
    @FunctionalInterface
    public interface JsonFetcher {
        String fetch(String url) throws Exception;
    }

    private final ObjectMapper mapper;
    private final String endpoint;
    private final boolean enabled;
    private final JsonFetcher fetcher;

    @Autowired
    public YouComSearchProvider(
            ObjectMapper mapper,
            @Value("${web.search.youcom-url:https://api.you.com/public/search}") String endpoint,
            @Value("${web.search.youcom-enabled:false}") boolean enabled,
            @Value("${web.search.timeout-seconds:20}") int timeoutSeconds,
            @Value("${web.search.max-response-bytes:1048576}") int maxResponseBytes) {
        this(mapper, endpoint, enabled, defaultFetcher(timeoutSeconds, maxResponseBytes));
    }

    YouComSearchProvider(ObjectMapper mapper, String endpoint, boolean enabled, JsonFetcher fetcher) {
        this.mapper = mapper;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.enabled = enabled;
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return "you.com";
    }

    @Override
    public boolean enabled() {
        return enabled && !endpoint.isBlank();
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) throws Exception {
        String sep = endpoint.contains("?") ? "&" : "?";
        String url = endpoint + sep + "query="
                + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        return parse(mapper, fetcher.fetch(url), maxResults);
    }

    /**
     * Parses a You.com search response into results. You.com's public shapes vary, so this tolerantly
     * reads either a top-level {@code hits[]} array or {@code results[]}, pulling out snippets from a few
     * common field names. Package-private for offline unit testing.
     */
    static List<WebSearchResult> parse(ObjectMapper mapper, String json, int maxResults) throws Exception {
        List<WebSearchResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) return results;
        JsonNode root = mapper.readTree(json);
        JsonNode array = root.has("hits") ? root.path("hits") : root.path("results");
        if (!array.isArray()) return results;
        for (JsonNode item : array) {
            String title = item.path("title").asText("").trim();
            String link = firstNonBlank(item.path("url").asText("").trim(), item.path("link").asText("").trim());
            String snippet = snippetOf(item);
            if (link.isBlank() && title.isBlank()) continue;
            results.add(new WebSearchResult(title, link, snippet));
            if (results.size() >= maxResults) break;
        }
        return results;
    }

    private static String snippetOf(JsonNode item) {
        String description = item.path("description").asText("").trim();
        if (!description.isBlank()) return description;
        String snippet = item.path("snippet").asText("").trim();
        if (!snippet.isBlank()) return snippet;
        JsonNode snippets = item.path("snippets");
        if (snippets.isArray() && !snippets.isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode node : snippets) {
                if (joined.length() > 0) joined.append(' ');
                joined.append(node.asText("").trim());
            }
            return joined.toString().trim();
        }
        return "";
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
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
