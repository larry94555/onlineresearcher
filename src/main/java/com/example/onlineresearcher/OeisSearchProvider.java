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
 * Keyless search of the OEIS (Online Encyclopedia of Integer Sequences) via {@code oeis.org/search?fmt=json}.
 * OEIS is the authoritative reference for named integer sequences (e.g. the Jacobsthal numbers, A001045), so
 * it sharply improves answers to mathematical topics. No API key required.
 *
 * <p>The network call sits behind an injectable {@link JsonFetcher} so JSON parsing is unit-tested offline.
 */
@Component
public class OeisSearchProvider implements SearchProvider {

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
    public OeisSearchProvider(
            ObjectMapper mapper,
            @Value("${web.search.oeis-url:https://oeis.org/search}") String apiUrl,
            @Value("${web.search.oeis-enabled:true}") boolean enabled,
            @Value("${web.search.timeout-seconds:20}") int timeoutSeconds,
            @Value("${web.search.max-response-bytes:1048576}") int maxResponseBytes) {
        this(mapper, apiUrl, enabled, defaultFetcher(timeoutSeconds, maxResponseBytes));
    }

    OeisSearchProvider(ObjectMapper mapper, String apiUrl, boolean enabled, JsonFetcher fetcher) {
        this.mapper = mapper;
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
        this.enabled = enabled;
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return "oeis";
    }

    @Override
    public boolean enabled() {
        return enabled && !apiUrl.isBlank();
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) throws Exception {
        String sep = apiUrl.contains("?") ? "&" : "?";
        String url = apiUrl + sep + "fmt=json&q="
                + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        return parse(mapper, fetcher.fetch(url), maxResults);
    }

    /** Parses an OEIS {@code fmt=json} response. Package-private for offline unit testing. */
    static List<WebSearchResult> parse(ObjectMapper mapper, String json, int maxResults) throws Exception {
        List<WebSearchResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) return results;
        JsonNode root = mapper.readTree(json);
        JsonNode array = root.isArray() ? root : root.path("results");
        if (!array.isArray()) return results;
        for (JsonNode entry : array) {
            int number = entry.path("number").asInt(-1);
            String id = number >= 0 ? String.format("A%06d", number) : entry.path("id").asText("").trim();
            String name = entry.path("name").asText("").trim();
            if (id.isBlank() && name.isBlank()) continue;
            String data = firstTerms(entry.path("data").asText("").trim(), 12);
            String title = id.isBlank() ? name : (id + ": " + name);
            String snippet = data.isBlank() ? name : (name + " — first terms: " + data);
            String url = id.isBlank() ? "https://oeis.org/search?q=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    : "https://oeis.org/" + id;
            results.add(new WebSearchResult(title, url, snippet));
            if (results.size() >= maxResults) break;
        }
        return results;
    }

    /** Keeps only the first {@code n} comma-separated terms so a long sequence can't flood the context. */
    static String firstTerms(String data, int n) {
        if (data == null || data.isBlank()) return "";
        String[] terms = data.split(",");
        if (terms.length <= n) return String.join(", ", terms).trim();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) builder.append(", ");
            builder.append(terms[i].trim());
        }
        return builder.append(", …").toString();
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
