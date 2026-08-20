# Testing Guide

This document details (a) the **use cases** the Online Researcher agent supports and how to try each one,
and (b) the **automated tests** that cover the implementation. The automated tests run completely offline —
no `llama-server` and no network are required.

---

## 1. Running the automated tests

```bash
mvn test
```

All tests are deterministic and offline: the language model and every web provider are replaced with
in-memory fakes, and skills are written to a temp directory.

### Test suites and what they cover

| Test class | What it verifies |
|------------|------------------|
| `TokenEstimatorTest` | Local token estimation: empty/null = 0, chars-per-token math, per-message overhead. |
| `ConversationMemoryTest` | Prior turns are included when within budget; old turns compact into a summary when over budget; **the assembled request always stays within `maxTokens - reserve`** (the < 8100 guarantee). |
| `DuckDuckGoSearchTest` | Query-URL building (encoding + recency filter), HTML and Lite result parsing, redirect URL decoding, result limits. |
| `SearchProviderParsingTest` | SearXNG / Firecrawl / You.com JSON parsing; `enabled()` honors config and blank URLs; disabled-by-default providers. |
| `ReferenceProviderParsingTest` | **Wikipedia / OEIS / DuckDuckGo-Instant-Answer** JSON parsing: Wikipedia intro-**extract** parsing ordered by search rank (+ legacy snippet fallback + TeX stripping), **OEIS long-sequence truncation**, `A%06d` sequence anchors + URLs, abstract + nested related-topic flattening, `enabled()` flags. |
| `WebResearchServiceTest` | Fan-out across providers, **de-duplication by URL**, skipping disabled providers, graceful per-provider failure notes, empty-query handling. |
| `QueryPlanningTest` | **Query hardening (the fix for the Jacobsthal failure):** markdown/LaTeX stripping, rejecting headers and full-sentence "queries", **entity decomposition** of "between X and Y" / "X vs Y", **shared-namesake extraction** (`jacobsthal function`/`jacobsthal number` → also searches `jacobsthal`), and a **regression test that replays the exact garbage queries from the original failure log** and asserts only clean keyword queries reach the providers. |
| `SkillStoreTest` | Skill save/load round-trip on disk, header/separator parsing, render→parse round-trip, missing-skill handling. |
| `ResearchSkillServiceTest` | Bootstraps the research skill from web + model and **persists** it; falls back to built-in guidance when there are no web results; reuses a current-version skill without calling the model; **rebuilds an older/unversioned skill** so new strategy is applied; asserts the built-in guidance includes the **authoritative-source strategy** (identify, check against, prefer). |
| `SportsScoreSkillServiceTest` | The **sports-score skill** is created, persisted, and versioned; its content covers the agent-accessible live-score sources (sports-data APIs, news/RSS, search panels, betting feeds) and warns against fabricating a score; a current-version skill is reused, an unversioned one is rebuilt. |
| `SportsTopicDetectorTest` | Score/result questions with a sports context are detected (`world cup game`, `who won`, `final score`), while non-sports uses of "score"/"result" (credit score, exam score) and pure-history sports questions are **not** triggered. |
| `ResearchServiceTest` | The full flow: **asks a clarifying question** when unclear then researches once clear; **re-searches when information is insufficient**; proceeds after the clarification cap; **does not bleed a previous unrelated topic into a new answer** (stateless synthesis); **applies the sports-score skill** for a score question; **fail-to-find fallback** — when the first search can't answer, it queries model-suggested agent-accessible sites before giving up; **reports the exact not-found sentence** when everything fails; **appends a clarifying question** only when one could help. |
| `FailToFindSkillServiceTest` | The **failing-to-find-information skill** is created, persisted, and versioned; its content states the fallback policy, the exact not-found sentence, and the optional clarifying question; an unversioned skill is rebuilt. |
| `TerminalPromptRunnerTest` | The console loop reads a topic, prints the researched answer, shows the prompt, and stops on `exit`. |
| `OnlineResearcherApplicationTests` | The Spring context wires every bean together (with the model, terminal, and network disabled). |

Current status: **65 tests, all passing.**

---

## 2. Agent use cases (manual / live testing)

These require a working `llama-server` on your `PATH` and internet access for the web providers. Start the
app with `mvn spring-boot:run` (or `java -jar target/onlineresearcher.jar`) and try each case at the
`research>` prompt.

### UC-1 — Straightforward factual research
- **Input:** `the health benefits of green tea`
- **Expected:** The agent searches the web, then prints a sourced summary of key findings with a **Sources**
  list (titles + URLs) and a `Sources searched via:` footer listing the providers used.
- **Tests the:** end-to-end happy path (clarity → search → sufficiency → synthesis).

### UC-2 — Vague prompt that needs clarification
- **Input:** `apples`
- **Expected:** Instead of researching, the agent asks a clarifying question (e.g. "nutrition, cultivation,
  varieties, or something else?") and the prompt changes to `your answer>`. After you answer (e.g.
  `nutrition`), it researches the clarified topic.
- **Tests the:** clarifying-question loop and the `your answer>` paused state.

### UC-3 — Follow-up and topic isolation
- **Input:** `the health benefits of green tea` then `now compare green tea to black tea`
- **Expected:** Each question is researched and answered on its own merits. Conversation memory feeds the
  *clarity* step (so follow-ups are understood in context), but the **answer is grounded only in the current
  question's evidence** — a new, unrelated question is never answered with content from a previous topic.
- **Note:** because the answer step is stateless, a follow-up that relies on a pronoun (`compare it to black
  tea`) may need the subject restated (`compare green tea to black tea`). This is the deliberate trade-off
  that prevents cross-topic answer bleed.
- **Tests the:** topic isolation (no bleed) while memory still informs clarification.

### UC-4 — Long session / memory compaction (the 8100-token guarantee)
- **Input:** Ask many topics in a row (10+), each producing a long answer.
- **Expected:** The app keeps working without context-overflow errors; older turns are summarized
  automatically. Watch the logs for `[memory] summarization failed` (only on model error) — normally
  compaction is silent.
- **Tests the:** auto-compaction keeping `memory + prompt < 8100 tokens`.

### UC-5 — Ambiguous/underspecified question
- **Input:** `is it safe?`
- **Expected:** The agent recognizes it cannot research this without scope and asks what "it" refers to.
- **Tests the:** clarity detection on context-free prompts.

### UC-6 — Topic with thin or conflicting coverage (re-search)
- **Input:** A narrow or very recent topic (e.g. `the outcome of <a very recent niche event>`).
- **Expected:** If the first pass returns too little, the agent issues new, more specific queries and tries
  again (up to `research.max-attempts`), and notes if information may be incomplete.
- **Tests the:** sufficiency evaluation and the re-search feedback loop.

### UC-7 — First-run research skill creation
- **Setup:** Delete `skills/research.md` (or run in a fresh directory).
- **Input:** any topic.
- **Expected:** Logs show `research skill not found; bootstrapping it from web best practices...`, then a
  `skills/research.md` file is created. Subsequent runs load it without rebuilding.
- **Tests the:** web-driven skill creation and persistence.

### UC-8 — Gathering from multiple providers
- **Setup:** Enable extra providers, e.g.
  `--web.search.youcom-enabled=true` and a reachable `--web.search.searxng-url=...`.
- **Input:** any topic.
- **Expected:** The `Sources searched via:` footer lists multiple providers; results are merged and
  de-duplicated.
- **Tests the:** multi-provider fan-out and merge.

### UC-9 — Graceful degradation when a provider is down
- **Setup:** Point SearXNG at an unreachable URL (`--web.search.searxng-url=https://does-not-exist.invalid`).
- **Input:** any topic.
- **Expected:** Research still completes using DuckDuckGo; the broken provider is skipped with a warning in
  the logs, not a crash.
- **Tests the:** best-effort provider handling.

### UC-10 — No results at all (offline / all providers blocked), with fallback
- **Setup:** Disconnect from the network (model still local) or disable all providers.
- **Input:** any topic.
- **Expected:** The agent first runs the **fail-to-find fallback** (asks the model which keyless,
  agent-accessible sites would cover the topic and tries them). When that also fails it ends with the exact
  sentence **"I was not able to find any information on that topic."** — optionally followed by one
  clarifying question — never a fabricated answer or a vague trail-off.
- **Tests the:** honest, clear failure and the fallback pass.

### UC-11 — Exit
- **Input:** `exit` or `quit`
- **Expected:** The loop ends and the app shuts down (stopping the managed `llama-server`).

### UC-12 — "Relationship" question with a negative answer (the Jacobsthal case)
- **Input:** `is there any relation between jacobsthal number and jacobsthal function besides their name?`
- **Expected:** The agent decomposes the topic into entity queries (`jacobsthal number`, `jacobsthal
  function`, and the pair), reaches Wikipedia/OEIS/DuckDuckGo-Instant-Answer, finds clear independent
  definitions, and concludes they are **unrelated except for being named after the same person (Ernst
  Jacobsthal)** — with sources. This is the scenario that previously failed with "0 unique sources" because
  the only providers (DDG-HTML 202, public SearXNG 403) were blocked and the generated queries were markdown
  garbage.
- **Tests the:** keyless JSON providers, query decomposition/sanitization, and the negative-answer path.

---

### UC-13 — Sports score (sports-score skill)
- **Input:** `what was the score in the world cup game between Japan and Brazil today`
- **Expected:** The agent recognizes this as a score question and applies the **sports-score skill** on top
  of the research skill. With only the bundled keyless providers (which don't carry live scores) it should
  honestly report that it could not retrieve a live score and point you to authoritative live sources
  (sports-data APIs, ESPN/Athletic live feeds, search score panels, sportsbooks) — **never an invented
  scoreline**. The logs will show it was triggered (the skill text mentions Sportradar/sportsbooks).
- **Tests the:** skill selection by topic, and honest failure on time-sensitive data the providers lack.

### UC-14 — Fail-to-find fallback and clear result
- **Input:** a deliberately obscure or unanswerable query (e.g. `the exact attendance of a tiny local event
  with no web presence`).
- **Expected:** When the normal search can't answer, the agent asks the model which keyless,
  agent-accessible sites might have it and queries those. If that still fails, the response ends with exactly
  **"I was not able to find any information on that topic."** and, when a clarification could help, one short
  follow-up question. The result is never vague.
- **Tests the:** the `failing-to-find-information` skill and its enforced behavior.

## 3. Tips for live testing

- **Watch `llama-server.log`** in the working directory for model/server status; first run downloads the
  model and can take several minutes before `research>` becomes responsive.
- **Force a fresh research skill:** delete `skills/research.md`.
- **Reduce latency while testing:** lower `research.max-attempts=1` and `research.max-queries=2`.
- **Use an external model server:** set `--llama.manage-server=false` and run your own `llama-server` on
  port 8081 (same as roleflow).
