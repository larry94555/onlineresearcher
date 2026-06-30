package com.example.onlineresearcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDuckGoSearchTest {

    @Test
    void buildUrlEncodesQueryAndAddsTimeFilter() {
        String url = DuckDuckGoSearch.buildUrl("https://html.duckduckgo.com/html/", "java records", "week");
        assertTrue(url.contains("q=java+records"));
        assertTrue(url.endsWith("&df=w"));
    }

    @Test
    void unknownTimeRangeAddsNoFilter() {
        String url = DuckDuckGoSearch.buildUrl("https://x/", "q", "decade");
        assertTrue(url.contains("q=q"));
        assertTrue(!url.contains("df="));
    }

    @Test
    void parseHtmlExtractsResultsAndDecodesRedirect() {
        String html = """
                <html><body>
                  <div class="result">
                    <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&rut=1">First</a>
                    <a class="result__snippet">Snippet one</a>
                  </div>
                  <div class="result">
                    <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fb">Second</a>
                    <a class="result__snippet">Snippet two</a>
                  </div>
                </body></html>
                """;
        List<WebSearchResult> results = DuckDuckGoSearch.parseHtml(html, "https://html.duckduckgo.com/", 10);
        assertEquals(2, results.size());
        assertEquals("First", results.get(0).title());
        assertEquals("https://example.com/a", results.get(0).url());
        assertEquals("Snippet one", results.get(0).snippet());
    }

    @Test
    void parseHtmlRespectsLimit() {
        String html = """
                <div class="result"><a class="result__a" href="https://a">A</a></div>
                <div class="result"><a class="result__a" href="https://b">B</a></div>
                <div class="result"><a class="result__a" href="https://c">C</a></div>
                """;
        assertEquals(2, DuckDuckGoSearch.parseHtml(html, "https://x/", 2).size());
    }

    @Test
    void parseLiteExtractsResults() {
        String html = """
                <html><body>
                  <a class="result-link" href="https://example.org/1">Lite One</a>
                  <span class="result-snippet">Lite snippet one</span>
                  <a class="result-link" href="https://example.org/2">Lite Two</a>
                  <span class="result-snippet">Lite snippet two</span>
                </body></html>
                """;
        List<WebSearchResult> results = DuckDuckGoSearch.parseLite(html, "https://lite/", 10);
        assertEquals(2, results.size());
        assertEquals("Lite One", results.get(0).title());
        assertEquals("https://example.org/1", results.get(0).url());
        assertEquals("Lite snippet one", results.get(0).snippet());
    }
}
