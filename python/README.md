# Harness Engineering for Agentic Coding (Python)

**Live-coding companion for a conference talk on harness engineering** - the discipline of layering guides, sensors, permissions, and feedback loops around AI coding agents.

Built with Python 3.12+, httpx, and a local Ollama model. No API key required.

## The problem

AI coding adoption is real:

- **Stripe** merges over 1,300 AI-generated pull requests every week
- **Google** says 75% of all new code is AI-generated and engineer-approved
- **Meta** targets 75%+ AI-assisted committed code by mid-2026

But adoption is not the same as engineering:

- **Gartner:** 40% of AI agent deployments will fail due to inadequate engineering foundations
- **DryRun Security:** 26 of 30 AI-agent PRs introduced at least one vulnerability

The question is not *which model will finally make agents reliable*, but *what system do we build around the model so the agent can be steered, checked, and improved?*

## Agent = model + harness

If you are not training the model, the harness is the part you can engineer:

- Tools it can call
- System prompt and project context
- Permissions and boundaries
- Feedback loops and sensors
- Guides that steer before action

The model thinks. The harness decides what is allowed.

## The 2x2

|   | **Feedforward (Guide)** | **Feedback (Sensor)** |
|---|---|---|
| **Computational** | Codemod (step 6) | Linter (step 7) + Import checker (step 8) |
| **Inferential** | AGENTS.md (step 4) + Skills (step 5) | LLM-as-judge review (step 9) |

Framework: Birgitta Boeckeler, [Harness engineering for coding agent users](https://martinfowler.com/articles/harness-engineering.html) (April 2025).

## Structure

```
python/
  steps/           # 11 step files + shared inner_harness.py (Ollama)
  bookshelf/       # FastAPI demo codebase the agent operates on
  outer_harness/   # Guides and sensors: AGENTS.md, skills, linters, codemods
```

## The steps

Each step is a standalone Python script that builds on the previous one:

| Step | What | 2x2 cell |
|------|------|----------|
| 0 | Minimal agent loop | Inner harness |
| 1 | Five tools (read, write, bash, grep, glob) | Inner harness |
| 2 | System prompt + turn limit | Inner harness |
| 3 | Permissions + cost tracking | Inner harness |
| 4 | AGENTS.md conventions | Feedforward, Inferential |
| 5 | Skill loading | Feedforward, Inferential |
| 6 | Codemod tool | Feedforward, Computational |
| 7 | Linter sensor | Feedback, Computational |
| 8 | Import checker sensor | Feedback, Computational |
| 9 | LLM-as-judge review | Feedback, Inferential |
| 10 | Behaviour gap (honest frontier) | Open |

## Prerequisites

### Python 3.12+

Python 3.12 or later is required for the `type` statement and `match/case` syntax used in the steps.

### Install and start Ollama

```bash
ollama serve
```

In another terminal, pull the default model:

```bash
ollama pull qwen3:8b
```

## Quick start

```bash
cd python
pip install -r requirements.txt

# Run bookshelf tests
cd bookshelf && pytest
cd ..

# Run a step
python steps/step00_loop.py

# Run with a custom task
python steps/step07_linter_sensor.py "Add a method that returns a book summary"
```

### Ollama configuration

```bash
export OLLAMA_MODEL=qwen3:8b          # default
export OLLAMA_BASE_URL=http://localhost:11434
```

`OLLAMA_MODEL` can point to another local model with reliable tool-calling support. Review quality in steps 9 and 10 depends on the selected model.

### Run the outer-harness tools directly

```bash
# Linter
python outer_harness/linters/result_unwrap_checker.py --self-test
python outer_harness/linters/result_unwrap_checker.py bookshelf/service/book_service.py

# Codemod
python outer_harness/codemods/result_type_rewrite.py --self-test

# Import checker
python outer_harness/arch/import_checker.py bookshelf
```

## The behaviour gap

All sensors can pass and the feature can still be wrong. Green checks can encode the wrong product decision. Step 10 demonstrates this honest frontier - the point where deterministic checks end and human judgment begins.

## Lineage

This is the Python variant of the Java harness engineering talk. Same concepts, same progression, Python tooling.

## License

MIT
