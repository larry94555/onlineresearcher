package com.example.onlineresearcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing tests for the reliable keyless JSON providers (Wikipedia, OEIS, DuckDuckGo Instant Answer). */
class ReferenceProviderParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void wikipediaParsesExtractPagesOrderedBySearchRank() throws Exception {
        // generator=search + prop=extracts shape: pages keyed by pageid, ordered by "index".
        String json = """
                {"query":{"pages":{
                  "3170":{"title":"Arithmetic function","index":2,"extract":"A number-theoretic function."},
                  "13547663":{"title":"Jacobsthal number","index":1,
                     "extract":"In mathematics, the Jacobsthal numbers are an integer sequence named after the German mathematician Ernst Jacobsthal."}
                }}}""";
        List<WebSearchResult> results = WikipediaSearchProvider.parse(mapper, json, 5);
        assertEquals(2, results.size());
        // index=1 must come first even though it appears second in the JSON object.
        assertEquals("Jacobsthal number", results.get(0).title());
        assertEquals("https://en.wikipedia.org/wiki/Jacobsthal_number", results.get(0).url());
        assertTrue(results.get(0).snippet().contains("named after the German mathematician Ernst Jacobsthal"));
    }

    @Test
    void wikipediaFallsBackToLegacySearchSnippetShape() throws Exception {
        String json = """
                {"query":{"search":[
                  {"title":"Jacobsthal number","snippet":"named after <span class=\\"searchmatch\\">Ernst Jacobsthal</span>"}
                ]}}""";
        List<WebSearchResult> results = WikipediaSearchProvider.parse(mapper, json, 5);
        assertEquals(1, results.size());
        assertEquals("named after Ernst Jacobsthal", results.get(0).snippet());
    }

    @Test
    void wikipediaCleanExtractDropsTexAndTruncates() {
        String cleaned = WikipediaSearchProvider.cleanExtract(
                "Defined as {\\displaystyle U_{n}(P,Q)} a Lucas sequence.");
        assertTrue(cleaned.contains("Lucas sequence"));
        assertFalse(cleaned.contains("displaystyle"));
    }

    @Test
    void wikipediaArticleUrlEncodesTitle() {
        assertEquals("https://en.wikipedia.org/wiki/Jacobsthal_number",
                WikipediaSearchProvider.articleUrl("Jacobsthal number"));
    }

    @Test
    void oeisParsesSequenceNumberIntoAnchorAndUrl() throws Exception {
        String json = """
                {"results":[
                  {"number":1045,"name":"Jacobsthal sequence: a(n) = a(n-1) + 2*a(n-2).","data":"0,1,1,3,5,11,21"}
                ]}""";
        List<WebSearchResult> results = OeisSearchProvider.parse(mapper, json, 5);
        assertEquals(1, results.size());
        assertTrue(results.get(0).title().startsWith("A001045:"));
        assertEquals("https://oeis.org/A001045", results.get(0).url());
        assertTrue(results.get(0).snippet().contains("0, 1, 1, 3, 5, 11, 21"));
    }

    @Test
    void oeisTruncatesLongSequenceData() throws Exception {
        // A long sequence must not flood the context (this caused the runaway digit dump in synthesis).
        String longData = "0,1,1,3,5,11,21,43,85,171,341,683,1365,2731,5461,10923,21845";
        String json = "{\"results\":[{\"number\":1045,\"name\":\"Jacobsthal\",\"data\":\"" + longData + "\"}]}";
        List<WebSearchResult> results = OeisSearchProvider.parse(mapper, json, 5);
        String snippet = results.get(0).snippet();
        assertTrue(snippet.contains("…"), "long sequence should be truncated with an ellipsis");
        assertFalse(snippet.contains("21845"), "terms past the cap should be dropped");
    }

    @Test
    void duckDuckGoInstantParsesAbstractAndRelatedTopics() throws Exception {
        String json = """
                {"Heading":"Jacobsthal number",
                 "AbstractText":"In mathematics, the Jacobsthal numbers are an integer sequence.",
                 "AbstractURL":"https://en.wikipedia.org/wiki/Jacobsthal_number",
                 "RelatedTopics":[
                   {"Text":"Ernst Jacobsthal - a German mathematician","FirstURL":"https://duckduckgo.com/Ernst_Jacobsthal"},
                   {"Name":"Category","Topics":[
                     {"Text":"Jacobsthal sum","FirstURL":"https://duckduckgo.com/Jacobsthal_sum"}
                   ]}
                 ]}""";
        List<WebSearchResult> results = DuckDuckGoInstantAnswerProvider.parse(mapper, json, 5);
        assertEquals("Jacobsthal number", results.get(0).title());
        assertTrue(results.get(0).snippet().contains("integer sequence"));
        // Flattens nested RelatedTopics groups.
        assertTrue(results.stream().anyMatch(r -> r.snippet().contains("Jacobsthal sum")));
    }

    @Test
    void providersHonorEnabledFlag() {
        assertFalse(new WikipediaSearchProvider(mapper, "", true, u -> "{}").enabled());
        assertFalse(new OeisSearchProvider(mapper, "https://oeis.org/search", false, u -> "{}").enabled());
        assertTrue(new DuckDuckGoInstantAnswerProvider(mapper, "https://api.duckduckgo.com/", true, u -> "{}")
                .enabled());
    }
}
