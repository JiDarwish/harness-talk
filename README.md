# Harness Engineering for Agentic Coding

**Live-coding companion for a conference talk on harness engineering** -- the discipline of layering guides, sensors, permissions, and feedback loops around AI coding agents.

Built with Java 25, JBang, and a local Ollama model. No API key required.

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
| **Computational** | Codemod (step 6) | Linter (step 7) + ArchUnit (step 8) |
| **Inferential** | AGENTS.md (step 4) + Skills (step 5) | LLM-as-judge review (step 9) |

Framework: Birgitta Boeckeler, [Harness engineering for coding agent users](https://martinfowler.com/articles/harness-engineering.html) (April 2025).

## Structure

```
steps/                  # 11 JBang-runnable step files + shared InnerHarness.java (Ollama)
anthropic_sdk_steps/    # Same steps using the Anthropic Claude API instead of Ollama
bookshelf/              # Spring Boot demo codebase the agent operates on
outer-harness/          # Guides and sensors: AGENTS.md, skills, ArchUnit, linter, codemod
presentation.html       # Slide deck (open in any browser)
```

## The steps

Each step is a standalone JBang script that builds on the previous one:

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
| 8 | ArchUnit sensor | Feedback, Computational |
| 9 | LLM-as-judge review | Feedback, Inferential |
| 10 | Behaviour gap (honest frontier) | Open |

## Prerequisites

### Install SDKMAN

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

### Install Java 25

```bash
sdk install java 25.0.3-tem
```

### Install JBang

```bash
sdk install jbang
```

### Install Maven

```bash
brew install maven
# or: sdk install maven
```

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
# Build the bookshelf app
cd bookshelf
mvn compile
mvn test    # 25 tests pass, 1 skipped (deliberate bug)
cd ..

# Run a step
jbang steps/step00_loop.java

# Run with a custom task
jbang steps/step07_linter_sensor.java "Add a method that returns List<Book>"
```

### Run via JBang catalog

```bash
jbang step00
jbang step07 "Add a method that returns List<Book>"
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
jbang outer-harness/linters/ResultUnwrapChecker.java --self-test
jbang outer-harness/linters/ResultUnwrapChecker.java bookshelf/src/main/java/com/example/bookshelf/service/BookService.java

# Codemod
jbang outer-harness/codemods/ResultTypeRewrite.java --self-test
```

## Anthropic SDK steps

The `anthropic_sdk_steps/` directory contains the same progression using the hosted Anthropic Claude API instead of a local Ollama model. These require an API key:

```bash
export ANTHROPIC_API_KEY=sk-ant-api03-...
jbang anthropic_sdk_steps/step00_loop.java
```

## The behaviour gap

All sensors can pass and the feature can still be wrong. Green checks can encode the wrong product decision. Step 10 demonstrates this honest frontier -- the point where deterministic checks end and human judgment begins.

## Lineage

This is what comes after Max Andersen's [nanocode](https://github.com/maxandersen/nanocode) -- a 260-line Java agent. We start where he ended and build the harness around it.

## License

MIT
