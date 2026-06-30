package com.example.onlineresearcher;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds DuckDuckGo query URLs and parses the HTML of its no-API endpoints
 * ({@code html.duckduckgo.com} and {@code lite.duckduckgo.com}) into {@link WebSearchResult}s. Kept
 * separate from the provider so the parsing can be unit-tested with canned HTML and no network access.
 */
final class DuckDuckGoSearch {

    private DuckDuckGoSearch() {}

    /** Builds {@code <endpoint>?q=<query>[&df=<d|w|m|y>]} for a query and optional recency filter. */
    static String buildUrl(String endpoint, String query, String timeRange) {
        StringBuilder url = new StringBuilder(endpoint)
                .append(endpoint.contains("?") ? "&" : "?")
                .append("q=").append(encode(query));
        String df = timeCode(timeRange);
        if (!df.isBlank()) url.append("&df=").append(df);
        return url.toString();
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** Maps a human time range to DuckDuckGo's {@code df} code; unknown values mean "no filter". */
    static String timeCode(String timeRange) {
        return switch (timeRange == null ? "" : timeRange.toLowerCase(Locale.ROOT)) {
            case "day" -> "d";
            case "week" -> "w";
            case "month" -> "m";
            case "year" -> "y";
            default -> "";
        };
    }

    /** Parses results from the full {@code html.duckduckgo.com} page. */
    static List<WebSearchResult> parseHtml(String html, String baseUrl, int limit) {
        Document document = Jsoup.parse(html == null ? "" : html, baseUrl);
        List<WebSearchResult> results = new ArrayList<>();
        for (Element result : document.select("div.result")) {
            Element link = result.selectFirst("a.result__a");
            if (link == null) continue;
            Element snippet = result.selectFirst(".result__snippet");
            results.add(new WebSearchResult(
                    link.text().trim(),
                    decodeRedirect(link.attr("href")),
                    snippet == null ? "" : snippet.text().trim()));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }

    /** Parses results from the lighter {@code lite.duckduckgo.com} page. */
    static List<WebSearchResult> parseLite(String html, String baseUrl, int limit) {
        Document document = Jsoup.parse(html == null ? "" : html, baseUrl);
        Elements links = document.select("a.result-link");
        Elements snippets = document.select(".result-snippet");
        List<WebSearchResult> results = new ArrayList<>();
        for (int index = 0; index < links.size(); index++) {
            Element link = links.get(index);
            String snippet = index < snippets.size() ? snippets.get(index).text().trim() : "";
            results.add(new WebSearchResult(link.text().trim(), decodeRedirect(link.attr("href")), snippet));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }

    /** DuckDuckGo wraps result links as redirects (…/l/?uddg=<encoded-url>); unwrap to the real URL. */
    static String decodeRedirect(String href) {
        if (href == null || href.isBlank()) return "";
        int marker = href.indexOf("uddg=");
        if (marker >= 0) {
            String encoded = href.substring(marker + "uddg=".length());
            int amp = encoded.indexOf('&');
            if (amp >= 0) encoded = encoded.substring(0, amp);
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        }
        return href.startsWith("//") ? "https:" + href : href;
    }
}
