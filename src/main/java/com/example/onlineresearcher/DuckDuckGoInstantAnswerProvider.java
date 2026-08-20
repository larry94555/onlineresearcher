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
 * Keyless web search via DuckDuckGo's Instant Answer API ({@code api.duckduckgo.com/?format=json}). Unlike
 * the scraped HTML endpoint — which now answers with an HTTP 202 anti-bot challenge — this JSON API returns
 * a Wikipedia-derived abstract plus related topics and is not bot-challenged, so it is a reliable token-free
 * source. It only has an answer for topics with a well-defined subject, so it is one source among several.
 *
 * <p>The network call sits behind an injectable {@link JsonFetcher} so JSON parsing is unit-tested offline.
 */
@Component
public class DuckDuckGoInstantAnswerProvider implements SearchProvider {

    /** Fetches the JSON body at a URL. Swappable so tests can return a canned document. */
    @FunctionalInterface
    public interface JsonFetcher {
        String fetch(String url) throws Exception;
    }

    private final ObjectMapper mapper;
    private final String apiUrl;
    private final boolean enabled;
    private final JsonFetcher fetcher;

    @Autowired
    public DuckDuckGoInstantAnswerProvider(
            ObjectMapper mapper,
            @Value("${web.search.ddg-instant-url:https://api.duckduckgo.com/}") String apiUrl,
            @Value("${web.search.ddg-instant-enabled:true}") boolean enabled,
            @Value("${web.search.timeout-seconds:20}") int timeoutSeconds,
            @Value("${web.search.max-response-bytes:1048576}") int maxResponseBytes) {
        this(mapper, apiUrl, enabled, defaultFetcher(timeoutSeconds, maxResponseBytes));
    }

    DuckDuckGoInstantAnswerProvider(ObjectMapper mapper, String apiUrl, boolean enabled, JsonFetcher fetcher) {
        this.mapper = mapper;
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
        this.enabled = enabled;
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return "duckduckgo-instant";
    }

    @Override
    public boolean enabled() {
        return enabled && !apiUrl.isBlank();
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) throws Exception {
        String sep = apiUrl.contains("?") ? "&" : "?";
        String url = apiUrl + sep + "format=json&no_html=1&skip_disambig=1&t=onlineresearcher&q="
                + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        return parse(mapper, fetcher.fetch(url), maxResults);
    }

    /** Parses a DuckDuckGo Instant Answer response. Package-private for offline unit testing. */
    static List<WebSearchResult> parse(ObjectMapper mapper, String json, int maxResults) throws Exception {
        List<WebSearchResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) return results;
        JsonNode root = mapper.readTree(json);
        String abstractText = root.path("AbstractText").asText("").trim();
        if (abstractText.isBlank()) {
            abstractText = root.path("Abstract").asText("").trim();   // identical to AbstractText with no_html=1
        }
        if (!abstractText.isBlank()) {
            String heading = root.path("Heading").asText("").trim();
            String url = root.path("AbstractURL").asText("").trim();
            results.add(new WebSearchResult(heading.isBlank() ? "DuckDuckGo abstract" : heading, url, abstractText));
        }
        collectRelated(root.path("RelatedTopics"), results, maxResults);
        return results.size() > maxResults ? new ArrayList<>(results.subList(0, maxResults)) : results;
    }

    /** RelatedTopics is a mix of leaf topics ({Text, FirstURL}) and category groups ({Name, Topics:[...]}). */
    private static void collectRelated(JsonNode related, List<WebSearchResult> results, int maxResults) {
        if (!related.isArray()) return;
        for (JsonNode node : related) {
            if (results.size() >= maxResults) return;
            if (node.has("Topics")) {
                collectRelated(node.path("Topics"), results, maxResults);
                continue;
            }
            String text = node.path("Text").asText("").trim();
            String url = node.path("FirstURL").asText("").trim();
            if (text.isBlank() && url.isBlank()) continue;
            String title = text.length() > 80 ? text.substring(0, 80) : text;
            results.add(new WebSearchResult(title, url, text));
        }
    }

    private static JsonFetcher defaultFetcher(int timeoutSeconds, int maxResponseBytes) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        int limit = Math.max(1, maxResponseBytes);
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "onlineresearcher/0.1 (keyless research agent)")
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
