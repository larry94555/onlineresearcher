package com.example.onlineresearcher;

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
import java.util.List;

/**
 * Keyless web search via DuckDuckGo's no-API endpoints: the full {@code html.duckduckgo.com} page first,
 * then the lighter {@code lite.duckduckgo.com} page as a fallback. Both are queried with an HTTP
 * {@code POST} (a plain GET now returns an HTTP 202 anti-bot challenge page with no results). The HTML is
 * parsed by {@link DuckDuckGoSearch}. This is the most reliable token-free provider and works out of the
 * box. The network call sits behind an injectable {@link HtmlFetcher} so parsing is unit-tested offline.
 */
@Component
public class DuckDuckGoSearchProvider implements SearchProvider {

    /** Fetches the HTML at a URL. Swappable so tests can return canned pages without a network. */
    @FunctionalInterface
    public interface HtmlFetcher {
        String fetch(String url) throws Exception;
    }

    private final String htmlUrl;
    private final String liteUrl;
    private final HtmlFetcher fetcher;

    @Autowired
    public DuckDuckGoSearchProvider(
            @Value("${web.search.duckduckgo-url:https://html.duckduckgo.com/html/}") String htmlUrl,
            @Value("${web.search.duckduckgo-lite-url:https://lite.duckduckgo.com/lite/}") String liteUrl,
            @Value("${web.search.timeout-seconds:20}") int timeoutSeconds,
            @Value("${web.search.max-response-bytes:1048576}") int maxResponseBytes) {
        this(htmlUrl, liteUrl, defaultFetcher(timeoutSeconds, maxResponseBytes));
    }

    DuckDuckGoSearchProvider(String htmlUrl, String liteUrl, HtmlFetcher fetcher) {
        this.htmlUrl = htmlUrl;
        this.liteUrl = liteUrl;
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return "duckduckgo";
    }

    @Override
    public boolean enabled() {
        return (htmlUrl != null && !htmlUrl.isBlank()) || (liteUrl != null && !liteUrl.isBlank());
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) throws Exception {
        Exception last = null;
        if (htmlUrl != null && !htmlUrl.isBlank()) {
            try {
                String url = DuckDuckGoSearch.buildUrl(htmlUrl, query, "");
                List<WebSearchResult> results = DuckDuckGoSearch.parseHtml(fetcher.fetch(url), url, maxResults);
                if (!results.isEmpty()) return results;
            } catch (Exception failure) {
                last = failure;
            }
        }
        if (liteUrl != null && !liteUrl.isBlank()) {
            try {
                String url = DuckDuckGoSearch.buildUrl(liteUrl, query, "");
                List<WebSearchResult> results = DuckDuckGoSearch.parseLite(fetcher.fetch(url), url, maxResults);
                if (!results.isEmpty()) return results;
            } catch (Exception failure) {
                last = failure;
            }
        }
        if (last != null) throw last;
        return List.of();
    }

    private static HtmlFetcher defaultFetcher(int timeoutSeconds, int maxResponseBytes) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        int limit = Math.max(1, maxResponseBytes);
        return url -> {
            // DuckDuckGo's HTML endpoints answer a GET with an HTTP 202 anti-bot challenge page (no
            // results); a POST with the query as a form body returns the real HTTP 200 results page. The
            // query is already form-encoded in the URL's query string, so split it off and send it as body.
            int mark = url.indexOf('?');
            String target = mark >= 0 ? url.substring(0, mark) : url;
            String body = mark >= 0 ? url.substring(mark + 1) : "";
            HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) onlineresearcher/0.1 web_search")
                    .header("Accept", "text/html")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            try (InputStream stream = response.body()) {
                return new String(stream.readNBytes(limit), StandardCharsets.UTF_8);
            }
        };
    }
}
