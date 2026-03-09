# Recording Script: KCP Before/After Agent Navigation

This is the exact narration and action script for recording a 3-minute screencast
showing how KCP improves agent navigation efficiency.

Target length: 3 minutes (1:30 without, 1:30 with).

---

## Setup (before recording)

1. Have two terminal windows ready, side by side
2. Left terminal: `without-kcp/sample-repo/` as working directory
3. Right terminal: `with-kcp/sample-repo/` with KCP MCP bridge connected
4. Clear both terminal histories
5. Prepare screen recording software (OBS, QuickTime, etc.)

---

## Part 1: Without KCP (0:00 - 1:30)

### Opening (0:00 - 0:10)

**Say:** "Let's see how an AI agent navigates a small codebase without any
knowledge manifest. This is a Node.js API with 5 files."

**Do:** Show the directory listing in the left terminal.

```
ls -la sample-repo/
ls -la sample-repo/src/
```

### Query 1: Authentication (0:10 - 0:30)

**Say:** "I'll ask: How do I authenticate with this API?"

**Do:** Type the question into the agent. Watch it:
- Read README.md (no useful info)
- List src/ directory
- Read server.js (sees `authenticate` import)
- Read auth.js (finds the answer)

**Say:** "Four file reads to find the answer. About 15 seconds."

### Query 2: Environment Variables (0:30 - 0:50)

**Say:** "Next: What environment variables does this project need?"

**Do:** Type the question. Watch the agent search through files.

**Say:** "The agent reads multiple files because environment variables could be
anywhere. It eventually finds config/settings.js."

### Query 3: Rate Limiting (0:50 - 1:10)

**Say:** "Where is the rate limiting configured?"

**Do:** Type the question. Watch the agent search.

**Say:** "Again, multiple file reads. The agent has to discover that rate limiting
lives in middleware.js, not in a config file."

### Summary (1:10 - 1:30)

**Say:** "Without KCP, the agent made roughly 12-15 file reads across 3 questions.
It found the right answers, but spent most of its time searching. Now let's see
the same questions with a knowledge.yaml manifest."

**Do:** Pause briefly. Switch focus to the right terminal.

---

## Part 2: With KCP (1:30 - 3:00)

### Transition (1:30 - 1:40)

**Say:** "Same codebase, same questions. The only difference: a knowledge.yaml
that describes each file's purpose with intent descriptions and trigger phrases."

**Do:** Briefly show the knowledge.yaml file (scroll through it for 3-4 seconds).

### Query 1: Authentication (1:40 - 1:55)

**Say:** "Same question: How do I authenticate?"

**Do:** Type the question. The agent:
- Queries KCP bridge (matches "authenticate" trigger on auth unit)
- Reads auth.js directly
- Answers immediately

**Say:** "One file read. The trigger phrase 'authenticate' pointed directly to
auth.js. Five seconds."

### Query 2: Environment Variables (1:55 - 2:10)

**Say:** "What environment variables does this project need?"

**Do:** Type the question. The agent:
- Queries KCP (matches "environment variable" trigger on config unit)
- Reads config/settings.js directly
- Answers immediately

**Say:** "One file read again. The agent didn't have to search at all."

### Query 3: Rate Limiting (2:10 - 2:25)

**Say:** "Where is the rate limiting configured?"

**Do:** Type the question. The agent:
- Queries KCP (matches "rate limit" trigger on rate-limiting unit)
- Reads middleware.js directly
- Answers with the file and config details

**Say:** "One file read. And because the knowledge.yaml declares that rate-limiting
depends on config, the agent could also mention the environment variables that
control it."

### Comparison (2:25 - 2:50)

**Say:** "Let's compare. Without KCP: 12 to 15 file reads, 45 seconds.
With KCP: 3 file reads, 15 seconds. That's a 4-5x reduction in tool calls
and a 3x speedup."

**Do:** Show a simple before/after table on screen (prepared graphic, or
type it in the terminal).

```
              Without KCP    With KCP    Improvement
File reads:   12-15          3           4-5x fewer
Time:         ~45s           ~15s        3x faster
Accuracy:     Correct        Correct     Same quality
```

### Closing (2:50 - 3:00)

**Say:** "A knowledge.yaml file takes 5 minutes to write and saves every agent
interaction from that point on. This scales: the bigger the codebase, the bigger
the savings. That's the Knowledge Context Protocol."

**Do:** End recording.

---

## Post-production notes

- Add a title card: "KCP: Before and After — Agent Navigation Benchmark"
- Add the comparison table as a graphic overlay at 2:25
- Upload to YouTube/Vimeo and link from the KCP README
- Ideal resolution: 1920x1080, 30fps
- Keep the terminal font large enough to read (16pt minimum)
