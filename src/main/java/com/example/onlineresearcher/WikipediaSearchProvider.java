package com.example.onlineresearcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
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
 * Keyless web search via the MediaWiki (Wikipedia) API: {@code action=query&list=search&format=json}. This
 * is the most reliable token-free source for encyclopedic "what is X" and "how do X and Y relate" questions
 * — it returns clean JSON, is not bot-challenged like the DuckDuckGo HTML endpoint, and reliably surfaces
 * the lead facts (for example, that two similarly named concepts are both named after the same person).
 *
 * <p>The network call sits behind an injectable {@link JsonFetcher} so JSON parsing is unit-tested offline.
 */
@Component
public class WikipediaSearchProvider implements SearchProvider {

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
    public WikipediaSearchProvider(
            ObjectMapper mapper,
            @Value("${web.search.wikipedia-url:https://en.wikipedia.org/w/api.php}") String apiUrl,
            @Value("${web.search.wikipedia-enabled:true}") boolean enabled,
            @Value("${web.search.timeout-seconds:20}") int timeoutSeconds,
            @Value("${web.search.max-response-bytes:1048576}") int maxResponseBytes) {
        this(mapper, apiUrl, enabled, defaultFetcher(timeoutSeconds, maxResponseBytes));
    }

    WikipediaSearchProvider(ObjectMapper mapper, String apiUrl, boolean enabled, JsonFetcher fetcher) {
        this.mapper = mapper;
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
        this.enabled = enabled;
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return "wikipedia";
    }

    @Override
    public boolean enabled() {
        return enabled && !apiUrl.isBlank();
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) throws Exception {
        // Use generator=search + prop=extracts so each hit carries its intro paragraph (a real definition),
        // not just a 20-word highlight snippet. Definitions are what let the model reason about a topic
        // instead of guessing — the original failure was a hallucinated relationship from thin snippets.
        String sep = apiUrl.contains("?") ? "&" : "?";
        int limit = Math.max(1, maxResults);
        String url = apiUrl + sep + "action=query&format=json&generator=search&gsrlimit=" + limit
                + "&prop=extracts&exintro=1&explaintext=1&exlimit=" + limit
                + "&gsrsearch=" + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        return parse(mapper, fetcher.fetch(url), maxResults);
    }

    /** Max characters of intro extract kept per article (keeps one source from dominating the budget). */
    static final int MAX_EXTRACT_CHARS = 700;

    /**
     * Parses a MediaWiki response. Prefers {@code query.pages[*].extract} (intro paragraphs from
     * {@code generator=search&prop=extracts}); falls back to the legacy {@code query.search} snippet shape.
     * Package-private for offline unit testing.
     */
    static List<WebSearchResult> parse(ObjectMapper mapper, String json, int maxResults) throws Exception {
        List<WebSearchResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) return results;
        JsonNode root = mapper.readTree(json);
        JsonNode pages = root.path("query").path("pages");
        if (pages.isObject() && pages.size() > 0) {
            // pages is keyed by pageid; order by the search rank in each page's "index" field.
            List<JsonNode> ordered = new ArrayList<>();
            pages.elements().forEachRemaining(ordered::add);
            ordered.sort(java.util.Comparator.comparingInt(n -> n.path("index").asInt(Integer.MAX_VALUE)));
            for (JsonNode page : ordered) {
                String title = page.path("title").asText("").trim();
                if (title.isBlank()) continue;
                results.add(new WebSearchResult(title, articleUrl(title),
                        cleanExtract(page.path("extract").asText(""))));
                if (results.size() >= maxResults) break;
            }
            return results;
        }
        JsonNode hits = root.path("query").path("search");
        if (!hits.isArray()) return results;
        for (JsonNode hit : hits) {
            String title = hit.path("title").asText("").trim();
            if (title.isBlank()) continue;
            String snippet = Jsoup.parse(hit.path("snippet").asText("")).text().trim();
            results.add(new WebSearchResult(title, articleUrl(title), snippet));
            if (results.size() >= maxResults) break;
        }
        return results;
    }

    /** Cleans a plain-text Wikipedia extract: drops TeX {@code {\displaystyle ...}} blobs, trims length. */
    static String cleanExtract(String extract) {
        if (extract == null) return "";
        String text = extract.replaceAll("\\{\\\\displaystyle[^}]*\\}", " ")
                .replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_EXTRACT_CHARS ? text : text.substring(0, MAX_EXTRACT_CHARS).trim() + "…";
    }

    static String articleUrl(String title) {
        String slug = URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8).replace("%2F", "/");
        return "https://en.wikipedia.org/wiki/" + slug;
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
                            "onlineresearcher/0.1 (keyless research agent; contact: local user)")
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
