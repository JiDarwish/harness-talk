#!/usr/bin/env python3
"""Step 3: Permissions + observability. The inner harness is complete.
'Permissions are a feedforward computational guide on the agent's actions.'"""

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import httpx

DEFAULT_MODEL = "qwen3:8b"
DEFAULT_BASE_URL = "http://localhost:11434"
MAX_OUTPUT = 10_000
MAX_TURNS = 25

# Safe bash commands that don't need permission
SAFE_BASH_PREFIXES = [
    "pytest ", "grep ", "find ", "cat ", "ls ",
    "head ", "tail ", "wc ", "diff ", "git ",
]

SYSTEM_PROMPT = """\
You are a coding agent operating on the bookshelf/ codebase -- a FastAPI library service.
You have tools to read files, write files, run bash commands, grep, and glob.

Work methodically:
1. Understand the task by reading relevant files first
2. Plan your approach before making changes
3. Make changes carefully, one file at a time
4. Verify your changes by running: cd bookshelf && python -m pytest -q

Be precise. Don't guess at file contents -- read them first.
When you're done, summarize what you changed and why."""


# --- Trace: structured logging + local Ollama usage tracking ---

trace_log: list[str] = []
total_prompt_eval_count = 0
total_eval_count = 0
total_duration_ns = 0
total_prompt_eval_duration_ns = 0
total_eval_duration_ns = 0


def trace(event):
    timestamp = datetime.now(timezone.utc).isoformat()
    trace_log.append(f"[{timestamp}] {event}")


def record_usage(response):
    global total_prompt_eval_count, total_eval_count
    global total_duration_ns, total_prompt_eval_duration_ns, total_eval_duration_ns
    total_prompt_eval_count += response.get("prompt_eval_count", 0)
    total_eval_count += response.get("eval_count", 0)
    total_duration_ns += response.get("total_duration", 0)
    total_prompt_eval_duration_ns += response.get("prompt_eval_duration", 0)
    total_eval_duration_ns += response.get("eval_duration", 0)
    trace(
        f"Usage: +{response.get('prompt_eval_count', 0)} prompt eval, "
        f"+{response.get('eval_count', 0)} eval tokens"
    )


def format_nanos(nanos):
    return f"{nanos / 1_000_000_000:.3f}s"


def print_trace_summary():
    print("\n=== Trace Summary ===")
    for entry in trace_log:
        print(f"  {entry}")
    print(f"  Total prompt eval count:       {total_prompt_eval_count}")
    print(f"  Total eval count:              {total_eval_count}")
    print(f"  Total duration:                {format_nanos(total_duration_ns)}")
    print(f"  Total prompt eval duration:    {format_nanos(total_prompt_eval_duration_ns)}")
    print(f"  Total eval duration:           {format_nanos(total_eval_duration_ns)}")
    print("  Estimated API cost: $0.0000 (local Ollama)")


# --- Permissions ---

def check_permission(tool, detail):
    if tool == "run_bash":
        for prefix in SAFE_BASH_PREFIXES:
            if detail.startswith(prefix):
                return True
    print(f"[permission] {tool}: {detail}")
    answer = input("  Allow? (y/n): ").strip().lower()
    return answer.startswith("y")


# --- Config ---

def model_name():
    return (os.environ.get("OLLAMA_MODEL") or DEFAULT_MODEL).strip()


def base_url():
    return (os.environ.get("OLLAMA_BASE_URL") or DEFAULT_BASE_URL).strip().rstrip("/")


def setup_hint():
    return f"""
Ollama setup:
  ollama serve
  ollama pull {model_name()}
"""


# --- Tool schemas ---

def string_prop(description):
    return {"type": "string", "description": description}


def tool_schema(name, description, properties, required):
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": {
                "type": "object",
                "properties": properties,
                "required": required,
            },
        },
    }


def default_tools():
    return [
        tool_schema("read_file",
                     "Read the contents of a file. Always read a file before modifying it.",
                     {"path": string_prop("Absolute or relative file path to read")},
                     ["path"]),
        tool_schema("write_file",
                     "Write content to a file. Creates parent directories if needed. "
                     "Always read the file first to understand existing content.",
                     {"path": string_prop("File path to write to"),
                      "content": string_prop("The complete file content to write")},
                     ["path", "content"]),
        tool_schema("run_bash",
                     "Run a bash command. Use for: pytest, pip, git diff, etc. "
                     "Output is capped at 10k chars.",
                     {"command": string_prop("The bash command to run")},
                     ["command"]),
        tool_schema("grep_files",
                     "Search file contents with grep. Returns matching lines with file:line prefixes.",
                     {"pattern": string_prop("Regex pattern to search for"),
                      "path": string_prop("Directory or file to search in")},
                     ["pattern", "path"]),
        tool_schema("glob_files",
                     "Find files by glob pattern.",
                     {"pattern": string_prop("Glob pattern, e.g. **/*.py"),
                      "path": string_prop("Base directory to search from")},
                     ["pattern", "path"]),
    ]


# --- Tool implementations ---

def tool_read_file(arguments):
    path = arguments.get("path", "")
    if not path:
        return "Error: path is required."
    try:
        with open(path) as f:
            return f.read()
    except OSError as e:
        return f"Error: {e}"


def tool_write_file(arguments):
    path = arguments.get("path", "")
    content = arguments.get("content")
    if not path:
        return "Error: path is required."
    if content is None:
        return "Error: content is required."
    try:
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)
        return f"Wrote {len(content)} chars to {path}"
    except OSError as e:
        return f"Error: {e}"


def tool_run_bash(arguments):
    command = arguments.get("command", "")
    if not command:
        return "Error: command is required."
    try:
        proc = subprocess.run(
            ["bash", "-c", command],
            capture_output=True, text=True, timeout=120,
        )
        output = proc.stdout + proc.stderr
        if len(output) > MAX_OUTPUT:
            output = output[:MAX_OUTPUT] + "\n...[truncated]"
        return output + f"\n[exit code: {proc.returncode}]"
    except Exception as e:
        return f"Error: {e}"


def tool_grep_files(arguments):
    pattern = arguments.get("pattern", "")
    path = arguments.get("path", "")
    if not pattern:
        return "Error: pattern is required."
    if not path:
        return "Error: path is required."
    try:
        proc = subprocess.run(
            ["grep", "-rn", "--include=*.py", pattern, path],
            capture_output=True, text=True, timeout=30,
        )
        output = proc.stdout + proc.stderr
        if len(output) > MAX_OUTPUT:
            return output[:MAX_OUTPUT] + "\n...[truncated]"
        return output if output else "No matches."
    except Exception as e:
        return f"Error: {e}"


def tool_glob_files(arguments):
    pattern = arguments.get("pattern", "")
    path = arguments.get("path", "")
    if not pattern:
        return "Error: pattern is required."
    if not path:
        return "Error: path is required."
    try:
        matches = sorted(str(p) for p in Path(path).glob(pattern) if p.is_file())
        return "\n".join(matches) if matches else "No files matched."
    except Exception as e:
        return f"Error: {e}"


def execute_tool(name, arguments):
    # read_file and glob_files/grep_files don't need permission
    if name == "read_file":
        return tool_read_file(arguments)
    if name == "grep_files":
        return tool_grep_files(arguments)
    if name == "glob_files":
        return tool_glob_files(arguments)

    # write_file needs permission
    if name == "write_file":
        path = arguments.get("path", "")
        if not check_permission("write_file", path):
            trace(f"DENIED: write_file -- {path}")
            return "Permission denied by user."
        trace(f"ALLOWED: write_file -- {path}")
        return tool_write_file(arguments)

    # run_bash needs permission (auto-allowed for safe prefixes)
    if name == "run_bash":
        command = arguments.get("command", "")
        if not check_permission("run_bash", command):
            trace(f"DENIED: run_bash -- {command}")
            return "Permission denied by user."
        trace(f"ALLOWED: run_bash -- {command}")
        return tool_run_bash(arguments)

    return f"Unknown tool: {name}"


def truncate(text, max_len=200):
    if len(text) <= max_len:
        return text
    return text[:max_len] + "... [truncated]"


# --- Ollama chat ---

def chat(messages, tools):
    body = {
        "model": model_name(),
        "stream": False,
        "messages": messages,
        "tools": tools,
    }
    try:
        with httpx.Client(timeout=300) as client:
            resp = client.post(f"{base_url()}/api/chat", json=body)
    except httpx.ConnectError:
        raise SystemExit(f"Could not connect to Ollama at {base_url()}.{setup_hint()}")
    except httpx.HTTPError as e:
        raise SystemExit(f"Could not call Ollama at {base_url()}: {e}{setup_hint()}")

    if resp.status_code < 200 or resp.status_code >= 300:
        raise SystemExit(
            f"Ollama chat failed with HTTP {resp.status_code}: {resp.text}{setup_hint()}"
        )

    data = resp.json()
    if data.get("error"):
        raise SystemExit(f"Ollama chat failed: {data['error']}{setup_hint()}")
    return data


# --- Main ---

def main():
    task = (
        " ".join(sys.argv[1:])
        if len(sys.argv) > 1
        else "Find any failing tests in bookshelf/ and fix them."
    )

    print("=== Step 3: Inner Harness Complete ===")
    print(f"Model: {model_name()}")
    print(f"Task: {task}")
    print()

    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": task},
    ]
    tools = default_tools()

    turn = 0
    while turn < MAX_TURNS:
        turn += 1
        trace(f"Turn {turn}")
        if turn == MAX_TURNS - 2:
            print(f"[warning] Approaching turn limit ({MAX_TURNS})")

        response = chat(messages, tools)
        record_usage(response)
        assistant = response.get("message")
        if assistant is None:
            raise SystemExit(
                f"Ollama response did not include a message.{setup_hint()}"
            )

        content = (assistant.get("content") or "").strip()
        if content:
            print(content)

        messages.append({"role": "assistant", "content": assistant.get("content"),
                         "tool_calls": assistant.get("tool_calls")})

        tool_calls = assistant.get("tool_calls") or []
        if not tool_calls:
            break

        for tc in tool_calls:
            fn = tc.get("function", {})
            name = fn.get("name", "")
            arguments = fn.get("arguments", {})
            if isinstance(arguments, str):
                arguments = json.loads(arguments)
            print(f"[turn {turn}] {name}")
            result = execute_tool(name, arguments)
            print(f"[result] {truncate(result)}")
            messages.append({"role": "tool", "content": result})

    if turn >= MAX_TURNS:
        print(f"[stopped] Turn limit reached ({MAX_TURNS})")
    print(f"\n=== Done in {turn} turns ===")
    print_trace_summary()


if __name__ == "__main__":
    main()
