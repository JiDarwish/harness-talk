#!/usr/bin/env python3
"""Step 0: The minimal agent loop -- one local Ollama model, one tool."""

import json
import os
import sys

import httpx

DEFAULT_MODEL = "qwen3:8b"
DEFAULT_BASE_URL = "http://localhost:11434"


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


def read_file_tool():
    return {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read the contents of a file at the given path",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "The file path to read"},
                },
                "required": ["path"],
            },
        },
    }


def execute_tool(name, arguments):
    if name == "read_file":
        path = arguments.get("path", "")
        if not path:
            return "Error: path is required."
        try:
            with open(path) as f:
                return f.read()
        except OSError as e:
            return f"Error reading file: {e}"
    return f"Unknown tool: {name}"


def preview(text):
    if len(text) > 200:
        return text[:200] + "..."
    return text


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


def main():
    task = (
        " ".join(sys.argv[1:])
        if len(sys.argv) > 1
        else "Read the file bookshelf/pom.xml and tell me what project this is."
    )

    print("=== Step 0: Minimal Agent Loop (Ollama) ===")
    print(f"Model: {model_name()}")
    print(f"Task: {task}")
    print()

    messages = [{"role": "user", "content": task}]
    tools = [read_file_tool()]

    while True:
        response = chat(messages, tools)
        assistant = response.get("message")
        if assistant is None:
            raise SystemExit("Ollama response did not include a message.")

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
            print(f"[result] {preview(result)}")
            messages.append({"role": "tool", "content": result})


if __name__ == "__main__":
    main()
