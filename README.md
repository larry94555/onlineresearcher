# Online Researcher

A console application that researches a topic on the web for you. You type a topic or question; the agent
forms search queries, gathers results from several **keyless, token-free** web services, fact-checks across
sources, and prints a sourced answer. It runs entirely against a **local `llama-server`** for the language
model — set up and used in exactly the same way as the sibling [`roleflow`](../roleflow) project — so no
cloud API keys are needed for the model either.

It also keeps **conversation memory**: each topic is researched with the context of earlier turns in the
session, and memory is auto-compacted (Claude Code style) so that **memory + your prompt always stay below
8100 tokens** — the usable budget of the model used here (`qwen2.5-3b-instruct`, the same model as roleflow).

---

## What it does (the flow)

For every prompt, the agent runs this loop:

1. **Ensure the research skill exists.** On first run it searches the web for best practices on doing
   research and fact-checking, asks the model to synthesize those into a reusable **research skill**, and
   saves it to `skills/research.md`. On later runs it just loads that skill. The skill's guidance is
   injected into the system prompt of every research step. (If the web/model is unavailable the first time,
   a sensible built-in default skill is used.)
2. **Read your prompt.** If the prompt is not a clear, researchable topic or question, the agent asks a
   **clarifying question** and waits for your answer — repeating until the topic is clear (or a small cap is
   reached).
3. **Research the web.** Using the research skill, it generates focused search queries and gathers results
   from every enabled keyless provider (below), merging and de-duplicating them by URL.
4. **Check sufficiency.** It evaluates whether the gathered information covers the basics from corroborating
   sources. If **yes**, it synthesizes a sourced answer and prints it. If **no**, it feeds the gaps back and
   **searches again** (bounded by `research.max-attempts`).
5. **Fail-to-find fallback.** If the search still can't answer the request, the agent doesn't give up: it
   asks the model **which keyless, agent-accessible sites** would have the information, queries those, and
   only if that *also* fails does it state, clearly and exactly, **"I was not able to find any information on
   that topic."** — then, if a specific clarification could plausibly help, it asks one short clarifying
   question. Every turn ends with a clear result (an answer or that exact sentence), never a vague trail-off.

## Keyless web research providers

| Provider | Default | Notes |
|----------|---------|-------|
| **Wikipedia / MediaWiki API** (JSON) | ✅ enabled | Most reliable token-free source for encyclopedic "what is X / how do X and Y relate" questions. Clean JSON, not bot-challenged. |
| **OEIS** (JSON) | ✅ enabled | Authoritative for named integer sequences (e.g. the Jacobsthal numbers, A001045). Great for math topics. |
| **DuckDuckGo Instant Answer API** (JSON) | ✅ enabled | Wikipedia-derived abstracts + related topics. This is the JSON API, **not** the bot-challenged HTML endpoint. |
| **DuckDuckGo HTML + Lite** (scrape) | ✅ enabled | Best-effort; many IPs now get an HTTP 202 anti-bot challenge (no results). Kept as an extra. |
| **SearXNG** (JSON API) | ✅ enabled | Privacy metasearch aggregating Google/Bing/DuckDuckGo/Reddit/… **Most public instances return HTTP 403 for `format=json`** — for reliable results, self-host SearXNG and point `web.search.searxng-url` at it. |
| **You.com** (keyless public search) | ⬜ off | LLM-ready structured snippets; keyless tier ~100 queries/day. Enable with `web.search.youcom-enabled=true`. |
| **Firecrawl** (keyless search/scrape) | ⬜ off | Converts pages to clean Markdown for deep reads; limited keyless credits. Enable with `web.search.firecrawl-enabled=true`. |

The agent **gathers from all enabled providers** and merges their results (de-duplicated by URL). Any provider
that is unreachable, rate-limited, or empty is skipped gracefully with a note — research continues with
whatever responded. The default trio (Wikipedia + OEIS + DuckDuckGo Instant Answer) is keyless and works out
of the box without self-hosting anything.

### Query strategy

The agent generates **plain keyword queries** (markdown/LaTeX from the model is stripped, and headers or
full-sentence "queries" are discarded). For a *relationship* question like "how do X and Y relate", it
**decomposes** the topic into the individual entities (`X`, `Y`, `X Y`) **and the shared namesake** (the
common word, e.g. `Jacobsthal`), since the answer often comes from understanding each side — and the page
that ties two same-named concepts together is usually the person/disambiguation page, not a page about the
(possibly non-existent) relationship. The Wikipedia provider returns each article's **intro paragraph**
(a real definition), not just a highlight snippet, so the model reasons over facts instead of guessing. A
well-supported **negative** answer ("they are unrelated except for sharing a name") is treated as a complete,
valid result, and long data (e.g. OEIS sequence terms) is truncated so it can't flood the model's context.

---

## Prerequisites

- **Java 17+** and **Maven**.
- **`llama-server`** (from [llama.cpp](https://github.com/ggerganov/llama.cpp)) available on your `PATH`
  (or set `llama.binary` to its full path). This is the same requirement as roleflow. On first launch the
  server downloads the model `Qwen/Qwen2.5-3B-Instruct-GGUF:Q4_K_M` via `-hf`; this can take a few minutes.

## Build & run

```bash
# from the project root: c:\users\larry\github\onlineresearcher
mvn clean package
java -jar target/onlineresearcher.jar
```

or during development:

```bash
mvn spring-boot:run
```

On startup the app launches and supervises a local `llama-server` (logs go to `llama-server.log`), then
prints:

```
Online Researcher ready. Enter a topic or question to research (type 'exit' or 'quit' to stop).
research>
```

### Example session

```
research> the health benefits of green tea
researching...
Green tea's main active compounds are catechins (notably EGCG) and a moderate amount of caffeine...
[key findings, corroborated across sources, with a Sources list of titles + URLs]

---
Sources searched via: duckduckgo, searxng
research> compare it to black tea
researching...
[uses memory of the previous turn — knows "it" = green tea — and researches the comparison]
research> apples
researching...
Could you clarify exactly what you'd like me to research about apples — nutrition, cultivation, varieties,
or something else?
your answer> nutrition
researching...
[researches apple nutrition]
research> exit
```

Type `exit` or `quit` to stop.

---

## How `llama-server` is used (same as roleflow)

- [`LlamaServerManager`](src/main/java/com/example/onlineresearcher/LlamaServerManager.java) builds the
  same command line, launches the process, polls `/health` until ready, and runs a watchdog that restarts
  the server if it becomes unhealthy.
- [`LlamaClient`](src/main/java/com/example/onlineresearcher/LlamaClient.java) talks to the OpenAI-compatible
  `/v1/chat/completions` endpoint.
- Configuration lives in [`application.properties`](src/main/resources/application.properties); the
  `llama.*` keys are identical to roleflow's (same model, port 8081, context, etc.) except for the bind
  address: `llama.host` defaults to `127.0.0.1`, because `llama-server` serves its inference API and Web UI
  without authentication. Binding it anywhere wider is refused at startup unless `llama.api-key` is also set.

## How memory works (same as roleflow)

- [`ConversationMemory`](src/main/java/com/example/onlineresearcher/ConversationMemory.java) keeps prior
  user/assistant turns and assembles `system + running-summary + turns + new prompt` for each request.
- [`TokenEstimator`](src/main/java/com/example/onlineresearcher/TokenEstimator.java) estimates size locally
  (~4 chars/token). When the assembled request plus the reserved response space would exceed
  `memory.max-tokens` (**set to 8100 here**), the oldest turns are folded into a running summary by
  [`LlmSummarizer`](src/main/java/com/example/onlineresearcher/LlmSummarizer.java). This guarantees
  **memory + your prompt < 8100 tokens** at all times.
- **Topic isolation.** Memory provides cross-turn context to the *clarity* step (so a follow-up can be
  understood in light of earlier turns), but the **final answer is synthesized statelessly** — grounded only
  in the current request and the evidence gathered for it. This prevents a new, unrelated question from being
  answered with content from a previous topic. (Trade-off: a follow-up that relies on a pronoun — "compare
  *it* to X" — may need the subject restated, since the answer step does not see prior turns.)

## How skills work

- Skills are reusable guidance injected into the model's system prompt at **every** research step (clarity
  check, query generation, sufficiency check, and synthesis). They are stored on disk as markdown under
  `skills/` by [`SkillStore`](src/main/java/com/example/onlineresearcher/SkillStore.java).
- The `research` skill is bootstrapped from the web on first use by
  [`ResearchSkillService`](src/main/java/com/example/onlineresearcher/ResearchSkillService.java) and reused
  thereafter. Its guidance includes: forming keyword queries, decomposing relationship questions,
  **identifying the authoritative/reputable sources for the topic, checking facts against them, and
  preferring them over less reliable sites**, corroborating across independent sources, and treating a
  well-supported negative answer as valid.
- The skill is **versioned** (`SKILL_VERSION`). When the built-in strategy is upgraded, a saved skill from an
  older version is rebuilt automatically on next use — you don't need to do anything. (You can still delete
  `skills/research.md` to force a rebuild.)
- A second, **`sports-score`** skill
  ([`SportsScoreSkillService`](src/main/java/com/example/onlineresearcher/SportsScoreSkillService.java)) is
  applied *in addition to* the research skill when the topic looks like a request for a match score/result
  (detected by [`SportsTopicDetector`](src/main/java/com/example/onlineresearcher/SportsTopicDetector.java)).
  It encodes the sources accessible to agents for live scores — real-time sports-data APIs (Sportradar,
  SportMonks, …), live news/RSS feeds (ESPN, The Athletic, …), search-engine score panels, and
  sportsbook/betting feeds (DraftKings, FanDuel, …) — and tells the agent to prefer them, cross-check, note
  timing/staleness, and **never fabricate a scoreline**.
  > **Note:** a skill is *guidance* injected into the prompts; it shapes how the agent queries and what it
  > recommends, but it does not by itself give the agent new network access. The bundled keyless providers
  > (Wikipedia/OEIS/DuckDuckGo/SearXNG) generally do **not** carry live scores, so for a live match the agent
  > will typically report that it couldn't retrieve the score and point you to the authoritative live sources
  > above. To actually fetch live scores, add a real data source as a `SearchProvider` (e.g. a free sports
  > API or a key for one of the services named in the skill).
- A **`failing-to-find-information`** skill
  ([`FailToFindSkillService`](src/main/java/com/example/onlineresearcher/FailToFindSkillService.java)) is
  applied on every turn. It encodes the not-found policy described in step 5 of the flow: try
  agent-accessible sources before giving up, end with a clear result (an answer or the exact sentence "I was
  not able to find any information on that topic."), and ask one clarifying question only when it could
  improve the search. The matching behavior is enforced in `ResearchService` (the skill text guides the
  model; the code runs the fallback pass and emits the clear result).

## Configuration reference

See [`application.properties`](src/main/resources/application.properties) for all keys. The most useful:

| Key | Default | Purpose |
|-----|---------|---------|
| `memory.max-tokens` | `8100` | Hard budget for memory + prompt. |
| `prompt.max-tokens` | `1024` | Tokens reserved for the model's reply. |
| `web.search.wikipedia-enabled` | `true` | Wikipedia/MediaWiki provider. |
| `web.search.oeis-enabled` | `true` | OEIS provider (integer sequences). |
| `web.search.ddg-instant-enabled` | `true` | DuckDuckGo Instant Answer (JSON) provider. |
| `web.search.searxng-url` | `https://searx.be` | SearXNG instance (blank disables it; public instances usually 403 the JSON API — self-host for reliability). |
| `web.search.youcom-enabled` | `false` | Enable You.com provider. |
| `web.search.firecrawl-enabled` | `false` | Enable Firecrawl provider. |
| `research.max-queries` | `4` | Search queries per attempt. |
| `research.max-attempts` | `3` | Re-search attempts on insufficient info. |
| `research.max-clarifications` | `3` | Clarifying questions before researching anyway. |
| `llama.host` | `127.0.0.1` | Bind address of the managed `llama-server`. Anything but loopback requires `llama.api-key`. |
| `llama.api-key` | (blank) | Required for a non-loopback bind. Passed to `llama-server` via `LLAMA_API_KEY` (not the command line) and sent by the client as a bearer token. |
| `llama.*` | (matches roleflow) | Other local model/server settings. |

Override any of them on the command line, e.g.:

```bash
java -jar target/onlineresearcher.jar --web.search.youcom-enabled=true --research.max-attempts=2
```

## Tests

```bash
mvn test
```

The unit tests run **fully offline** (no `llama-server`, no network) — see
[`TESTING.md`](TESTING.md) for the full list of covered use cases and how to exercise the live agent.
