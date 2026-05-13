# Ollama Steps

These scripts mirror `steps/` but call a local Ollama model instead of a hosted LLM API.

## Prerequisites

Start Ollama if it is not already running:

```bash
ollama serve
```

In another terminal, pull the default model:

```bash
ollama pull qwen3:8b
```

Java 25 and JBang are still required.

## Run A Step

```bash
jbang ollama_steps/step00_loop.java
jbang ollama_steps/step00_repl.java
jbang ollama_steps/step07_linter_sensor.java "Add a method that returns List<Book>"
```

## Configuration

```bash
export OLLAMA_MODEL=qwen3:8b
export OLLAMA_BASE_URL=http://localhost:11434
```

`OLLAMA_MODEL` can point to another local model with reliable tool-calling support. Review quality in steps 9 and 10 depends on the selected model.
