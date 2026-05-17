#!/usr/bin/env python3
"""Step 1: Five tools -- read, write, bash, grep, glob."""

import json
import os
import subprocess
import sys
from pathlib import Path

import httpx

DEFAULT_MODEL = "qwen3:8b"
DEFAULT_BASE_URL = "http://localhost:11434"
MAX_OUTPUT = 10_000


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
        tool_schema("read_file", "Read the contents of a file at the given path",
                     {"path": string_prop("The file path to read")}, ["path"]),
        tool_schema("write_file",
                     "Write content to a file at the given path, creating directories as needed",
                     {"path": string_prop("The file path to write to"),
                      "content": string_prop("The full content to write to the file")},
                     ["path", "content"]),
        tool_schema("run_bash",
                     "Run a bash command and return its output. Use for builds, tests, and system commands.",
                     {"command": string_prop("The bash command to execute")}, ["command"]),
        tool_schema("grep_files",
                     "Search file contents for a regex pattern using grep. Returns matching lines with file and line number.",
                     {"pattern": string_prop("The regex pattern to search for"),
                      "path": string_prop("The directory or file to search in")},
                     ["pattern", "path"]),
        tool_schema("glob_files",
                     "Find files matching a glob pattern. Returns a list of matching file paths.",
                     {"pattern": string_prop("The glob pattern, e.g. **/*.py"),
                      "path": string_prop("The base directory to search from")},
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
        return f"Error reading file: {e}"


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
        return f"Error writing file: {e}"


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
            output = output[:MAX_OUTPUT] + f"\n... [truncated at {MAX_OUTPUT} chars]"
        return output + f"\n[exit code: {proc.returncode}]"
    except Exception as e:
        return f"Error running command: {e}"


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
            return output[:MAX_OUTPUT] + f"\n... [truncated at {MAX_OUTPUT} chars]"
        return output if output else "No matches found."
    except Exception as e:
        return f"Error running grep: {e}"


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
        return f"Error searching files: {e}"


def execute_tool(name, arguments):
    dispatch = {
        "read_file": tool_read_file,
        "write_file": tool_write_file,
        "run_bash": tool_run_bash,
        "grep_files": tool_grep_files,
        "glob_files": tool_glob_files,
    }
    handler = dispatch.get(name)
    if handler is None:
        return f"Unknown tool: {name}"
    return handler(arguments)


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

    print("=== Step 1: Tool Registry (Ollama) ===")
    print(f"Model: {model_name()}")
    print(f"Task: {task}")
    print()

    messages = [{"role": "user", "content": task}]
    tools = default_tools()

    while True:
        response = chat(messages, tools)
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
            print(f"[tool] {name}")
            result = execute_tool(name, arguments)
            print(f"[result] {truncate(result)}")
            messages.append({"role": "tool", "content": result})


if __name__ == "__main__":
    main()
