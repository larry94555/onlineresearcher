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

## Keyless web research providers

| Provider | Default | Notes |
|----------|---------|-------|
| **DuckDuckGo** (HTML + Lite) | ✅ enabled | No API key, works out of the box. Most reliable token-free source. |
| **SearXNG** (JSON API) | ✅ enabled | Privacy metasearch aggregating Google/Bing/DuckDuckGo/Reddit/… Point `web.search.searxng-url` at your own or a public instance. |
| **You.com** (keyless public search) | ⬜ off | LLM-ready structured snippets; keyless tier ~100 queries/day. Enable with `web.search.youcom-enabled=true`. |
| **Firecrawl** (keyless search/scrape) | ⬜ off | Converts pages to clean Markdown for deep reads; limited keyless credits. Enable with `web.search.firecrawl-enabled=true`. |

The agent **gathers from all enabled providers** and merges their results. Any provider that is unreachable,
rate-limited, or empty is skipped gracefully with a note — research continues with whatever responded.

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
  `llama.*` keys are identical to roleflow's (same model, port 8081, context, etc.).

## How memory works (same as roleflow)

- [`ConversationMemory`](src/main/java/com/example/onlineresearcher/ConversationMemory.java) keeps prior
  user/assistant turns and assembles `system + running-summary + turns + new prompt` for each request.
- [`TokenEstimator`](src/main/java/com/example/onlineresearcher/TokenEstimator.java) estimates size locally
  (~4 chars/token). When the assembled request plus the reserved response space would exceed
  `memory.max-tokens` (**set to 8100 here**), the oldest turns are folded into a running summary by
  [`LlmSummarizer`](src/main/java/com/example/onlineresearcher/LlmSummarizer.java). This guarantees
  **memory + your prompt < 8100 tokens** at all times.

## How skills work

- Skills are reusable guidance injected into the model's system prompt. They are stored on disk as markdown
  under `skills/` by [`SkillStore`](src/main/java/com/example/onlineresearcher/SkillStore.java).
- The `research` skill is bootstrapped from the web on first use by
  [`ResearchSkillService`](src/main/java/com/example/onlineresearcher/ResearchSkillService.java) and reused
  thereafter. Delete `skills/research.md` to force it to be rebuilt.

## Configuration reference

See [`application.properties`](src/main/resources/application.properties) for all keys. The most useful:

| Key | Default | Purpose |
|-----|---------|---------|
| `memory.max-tokens` | `8100` | Hard budget for memory + prompt. |
| `prompt.max-tokens` | `1024` | Tokens reserved for the model's reply. |
| `web.search.searxng-url` | `https://searx.be` | SearXNG instance (blank disables it). |
| `web.search.youcom-enabled` | `false` | Enable You.com provider. |
| `web.search.firecrawl-enabled` | `false` | Enable Firecrawl provider. |
| `research.max-queries` | `4` | Search queries per attempt. |
| `research.max-attempts` | `3` | Re-search attempts on insufficient info. |
| `research.max-clarifications` | `3` | Clarifying questions before researching anyway. |
| `llama.*` | (matches roleflow) | Local model/server settings. |

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
