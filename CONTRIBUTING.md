# Contributing to KCP

There are three ways to contribute:

1. **Improve the spec** — propose changes via issues or RFCs
2. **Add a real-world implementation** — manifest + benchmark results for a public repo
3. **Improve tooling or guides** — parsers, validation, documentation

---

## 1. Contributing to the spec

### Issues first

Open an issue before writing an RFC. Most proposals benefit from early discussion — someone
may have encountered the same problem, or there may be an existing mechanism you missed.
Label your issue `spec` if it proposes a change, `question` if you need clarification.

### RFC process

Significant spec changes go through the RFC process:

1. Open an issue describing the problem, not the solution
2. Discuss until the problem statement is stable
3. Write an RFC — copy the structure of an existing one (e.g. `RFC-0001-KCP-Extended.md`)
4. Open a PR with the RFC file and link the original issue
5. RFC is merged when reviewers reach rough consensus; it does not mean the feature ships

The RFC number is sequential. Check the highest existing RFC and increment by one.
Do not rename files after they are merged — RFC numbers are permanent identifiers.

### Spec PRs

Minor fixes (typos, ambiguous wording, broken examples) do not need an RFC. Open a PR
directly against `SPEC.md`. Keep the change small and describe the ambiguity you are
resolving in the PR description.

---

## 2. Contributing a real-world implementation

The most valuable contributions are working `knowledge.yaml` manifests with benchmark
evidence for public repositories. These show KCP working in practice and provide
reference implementations for others to learn from.

### What to submit

A complete implementation includes:

```
knowledge.yaml          ← the manifest (v0.6, in the target repo, not here)
docs/.../tldr-*.md      ← TL;DR summary files (in the target repo)
BENCHMARK.md            ← benchmark results (in the target repo)
```

And a summary in this repo at `examples/<repo-name>/`:

```
examples/
  crewAI/
    README.md           ← what was done, results table, link to PR
  smolagents/
    README.md
  autogen/
    README.md
```

### Benchmark methodology

Use this methodology to produce results that are comparable across repos and over time.

**Model:** `claude-haiku-4-5-20251001` (or the latest Haiku model — document the exact
model ID in your results).

**Tool count source:** The Anthropic API `usage.tool_uses` field from each response
object. This counts every `tool_use` block across all turns. It is authoritative — do
not use agent self-reports or manual counts.

**Baseline condition:** Agent instructed to explore the repository freely to answer the
query. System prompt:
```
You are a helpful assistant answering questions about [REPO NAME].
The repository is at [LOCAL PATH].
Use the available tools to read files and find the answer.
Start by exploring the repository structure to understand where to find information.
```

**KCP condition:** Agent instructed to read `knowledge.yaml` first, match triggers, and
prefer TL;DR summary units. System prompt:
```
You are a helpful assistant answering questions about [REPO NAME].
The repository is at [LOCAL PATH].
IMPORTANT: First read [LOCAL PATH]/knowledge.yaml to understand the repository structure.
Match the question to the triggers in knowledge.yaml and read only the files pointed to
by matching units. If a unit has summary_available: true, read the summary_unit file
first — it is much smaller.
```

**Queries:** 7–10 representative questions covering the most common topics in the repo.
Choose queries that reflect what real users actually ask — not questions designed to
favour KCP. A query like "which LLM models are supported?" is fair; one that directly
quotes a trigger phrase is not.

**Max turns:** 20 per query. If an agent hits the limit, record the count at that point.

**File truncation:** Truncate files at 8,000 characters. Document in your methodology
if you change this.

**Runner script:** A minimal benchmark script looks like this:

```python
import anthropic, os, glob as glob_module, subprocess
from pathlib import Path

client = anthropic.Anthropic()

REPO_ROOT = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT_REAL = Path(os.path.realpath(REPO_ROOT))


def _within_repo(path: str) -> bool:
    try:
        Path(os.path.realpath(path)).relative_to(_REPO_ROOT_REAL)
        return True
    except (ValueError, OSError):
        return False

TOOLS = [
    {
        "name": "read_file",
        "description": "Read the content of a file",
        "input_schema": {
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"]
        }
    },
    {
        "name": "glob_files",
        "description": "Find files matching a glob pattern",
        "input_schema": {
            "type": "object",
            "properties": {
                "pattern": {"type": "string"},
                "base_dir": {"type": "string"}
            },
            "required": ["pattern"]
        }
    },
    {
        "name": "grep_content",
        "description": "Search for text in files",
        "input_schema": {
            "type": "object",
            "properties": {
                "pattern": {"type": "string"},
                "path": {"type": "string"}
            },
            "required": ["pattern", "path"]
        }
    }
]

def execute_tool(name, inp):
    if name == "read_file":
        path = inp["path"]
        if not _within_repo(path):
            return "Error: access denied — path is outside the repository"
        try:
            content = open(path, encoding="utf-8", errors="replace").read()
            return content[:8000] + "\n...[truncated]" if len(content) > 8000 else content
        except Exception as e:
            return f"Error: {e}"
    elif name == "glob_files":
        pattern = inp["pattern"]
        base = inp.get("base_dir", REPO_ROOT)
        if not _within_repo(base):
            base = REPO_ROOT
        if not pattern.startswith("/"):
            pattern = os.path.join(base, pattern)
        matches = [m for m in glob_module.glob(pattern, recursive=True) if _within_repo(m)]
        return "\n".join(matches[:20]) or "No files found"
    elif name == "grep_content":
        path = inp["path"]
        if not _within_repo(path):
            return "Error: access denied — path is outside the repository"
        r = subprocess.run(["grep", "-r", "-l", "-m", "5", "-e", inp["pattern"], path],
                           capture_output=True, text=True, timeout=10)
        return r.stdout[:2000] or "No matches"
    return "Unknown tool"

def run_agent(system_prompt, query, max_turns=20):
    messages = [{"role": "user", "content": query}]
    tool_count = 0
    for _ in range(max_turns):
        response = client.messages.create(
            model="claude-haiku-4-5-20251001",
            max_tokens=2048,
            system=system_prompt,
            tools=TOOLS,
            messages=messages
        )
        uses = [b for b in response.content if b.type == "tool_use"]
        tool_count += len(uses)
        if response.stop_reason == "end_turn" or not uses:
            return tool_count
        messages.append({"role": "assistant", "content": response.content})
        messages.append({"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": u.id, "content": execute_tool(u.name, u.input)}
            for u in uses
        ]})
    return tool_count
```

### Results to date

| Repository | Stars | Type | Baseline | KCP | Reduction |
|------------|-------|------|----------|-----|-----------|
| [penpot-wizard](https://github.com/totto/penpot-wizard) | — | application code | 119 | 31 | 74% |
| [infrastructure-agents-guide](https://github.com/totto/infrastructure-agents-guide) | — | pure documentation | 53 | 25 | 53% |
| [smolagents](https://github.com/huggingface/smolagents/pull/2026) | 25K | Python framework | 121 | 33 | 73% |
| [AutoGen](https://github.com/microsoft/autogen/pull/7329) | 55K | Python framework | 168 | 33 | 80% |
| [CrewAI](https://github.com/crewAIInc/crewAI/pull/4658) | 44K | Python framework | 123 | 30 | 76% |

The pattern across these five repos: larger navigation spaces benefit more. A flat
documentation repo (53%) costs less to explore even without KCP; a large multi-section
framework with dozens of notebooks or pages (74–80%) has much more to gain.

### Submitting your implementation

1. Open a PR to the target repository with `knowledge.yaml`, TL;DR files, and `BENCHMARK.md`
2. Open a PR to this repo adding `examples/<repo-name>/README.md` with:
   - Link to the target repo PR
   - Results table (same format as above)
   - 2–3 sentences on what drove the biggest gains or where KCP had the least effect
   - Exact model ID and benchmark date

---

## 3. Improving tooling and guides

### Parsers

Parsers live in `parsers/`. Each parser directory has its own README with build and test
instructions. The Python and Java parsers are the reference implementations — changes to
validation logic should match the rules in `SPEC.md §7`.

If you are adding a parser in a new language, copy the structure of an existing one.
All parsers must implement the same validation rules; differences in strictness are bugs.

### Guides

Guides live in `guides/`. They are practical — focus on what someone needs to do, not
on what the spec says. If you notice a guide is wrong or missing a common case, open a
PR directly.

The `guides/adopting-kcp-in-existing-projects.md` guide covers the step-by-step
adoption path. The `guides/` directory can grow — a guide on "writing effective triggers"
or "when to create TL;DR files" would be useful additions based on the benchmark results.

### TL;DR files — what makes them effective

From the five benchmarks above, the pattern is consistent: TL;DR files are most valuable
when they answer a clearly scoped question in under 600 tokens and explicitly point to the
full source for depth.

Effective TL;DR files:
- Answer 1–2 common questions completely (not partially)
- Include a short working code example (~10 lines)
- List the key options or parameters as a table or bullet list
- End with "See [full file] for complete reference"

Ineffective TL;DR files:
- Summarise a file without answering a specific question
- Are longer than the original (defeats the purpose)
- Omit code examples (agents need concrete syntax, not prose)

---

## Code of conduct

This project follows the [Contributor Covenant](https://www.contributor-covenant.org/).
Be direct. Be specific. Disagree about ideas, not people.
