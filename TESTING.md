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
| `WebResearchServiceTest` | Fan-out across providers, **de-duplication by URL**, skipping disabled providers, graceful per-provider failure notes, empty-query handling. |
| `SkillStoreTest` | Skill save/load round-trip on disk, header/separator parsing, render→parse round-trip, missing-skill handling. |
| `ResearchSkillServiceTest` | Bootstraps the research skill from web + model and **persists** it; falls back to built-in guidance when there are no web results; reuses an existing skill without calling the model. |
| `ResearchServiceTest` | The full flow: **asks a clarifying question** when unclear then researches once clear; **re-searches when information is insufficient**; proceeds to research after the clarification cap is reached. |
| `TerminalPromptRunnerTest` | The console loop reads a topic, prints the researched answer, shows the prompt, and stops on `exit`. |
| `OnlineResearcherApplicationTests` | The Spring context wires every bean together (with the model, terminal, and network disabled). |

Current status: **34 tests, all passing.**

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

### UC-3 — Follow-up that depends on memory
- **Input:** `the health benefits of green tea` then `now compare it to black tea`
- **Expected:** The second turn understands that "it" refers to green tea, because earlier turns are in
  conversation memory.
- **Tests the:** conversation memory feeding prior context into a new research turn.

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

### UC-10 — No results at all (offline / all providers blocked)
- **Setup:** Disconnect from the network (model still local) or disable all providers.
- **Input:** any topic.
- **Expected:** A clear message that no web results could be retrieved, listing the enabled providers and a
  short research log — rather than a fabricated answer.
- **Tests the:** honest failure when the web is unavailable.

### UC-11 — Exit
- **Input:** `exit` or `quit`
- **Expected:** The loop ends and the app shuts down (stopping the managed `llama-server`).

---

## 3. Tips for live testing

- **Watch `llama-server.log`** in the working directory for model/server status; first run downloads the
  model and can take several minutes before `research>` becomes responsive.
- **Force a fresh research skill:** delete `skills/research.md`.
- **Reduce latency while testing:** lower `research.max-attempts=1` and `research.max-queries=2`.
- **Use an external model server:** set `--llama.manage-server=false` and run your own `llama-server` on
  port 8081 (same as roleflow).
