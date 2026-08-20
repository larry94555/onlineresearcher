package com.example.onlineresearcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchProviderParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void searxngParsesResults() throws Exception {
        String json = """
                {"results":[
                  {"title":"T1","url":"https://1","content":"c1"},
                  {"title":"T2","url":"https://2","content":"c2"}
                ]}""";
        List<WebSearchResult> results = SearxngSearchProvider.parse(mapper, json, 5);
        assertEquals(2, results.size());
        assertEquals("T1", results.get(0).title());
        assertEquals("https://1", results.get(0).url());
        assertEquals("c1", results.get(0).snippet());
    }

    @Test
    void searxngProviderUsesInjectedFetcherAndRespectsEnabled() throws Exception {
        String json = "{\"results\":[{\"title\":\"T\",\"url\":\"https://u\",\"content\":\"s\"}]}";
        SearxngSearchProvider enabled = new SearxngSearchProvider(mapper, "https://searx.test", true,
                url -> json);
        assertTrue(enabled.enabled());
        assertEquals(1, enabled.search("q", 5).size());

        SearxngSearchProvider blankUrl = new SearxngSearchProvider(mapper, "", true, url -> json);
        assertFalse(blankUrl.enabled());

        SearxngSearchProvider turnedOff = new SearxngSearchProvider(mapper, "https://searx.test", false,
                url -> json);
        assertFalse(turnedOff.enabled());
    }

    @Test
    void firecrawlParsesDataArray() throws Exception {
        String json = """
                {"data":[
                  {"title":"Doc","url":"https://doc","description":"about things"}
                ]}""";
        List<WebSearchResult> results = FirecrawlSearchProvider.parse(mapper, json, 5);
        assertEquals(1, results.size());
        assertEquals("Doc", results.get(0).title());
        assertEquals("about things", results.get(0).snippet());
    }

    @Test
    void firecrawlDisabledByDefaultConfig() {
        FirecrawlSearchProvider provider = new FirecrawlSearchProvider(mapper,
                "https://api.firecrawl.dev/v1/search", false, (url, body) -> "{}");
        assertFalse(provider.enabled());
    }

    @Test
    void youComParsesHitsArray() throws Exception {
        String json = """
                {"hits":[
                  {"title":"H","url":"https://h","snippets":["part one","part two"]}
                ]}""";
        List<WebSearchResult> results = YouComSearchProvider.parse(mapper, json, 5);
        assertEquals(1, results.size());
        assertEquals("H", results.get(0).title());
        assertTrue(results.get(0).snippet().contains("part one"));
        assertTrue(results.get(0).snippet().contains("part two"));
    }
}
