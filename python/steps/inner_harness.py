"""Inner harness -- shared library for Ollama-based local coding agents.

Provides the Agent class with built-in tools, permission system, hooks,
and usage tracking. Steps 4-7 import from this module.
"""

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


# --- Config ---

def model_name():
    return (os.environ.get("OLLAMA_MODEL") or DEFAULT_MODEL).strip()


def base_url():
    return (os.environ.get("OLLAMA_BASE_URL") or DEFAULT_BASE_URL).strip().rstrip("/")


def setup_hint():
    return (
        f"\nOllama setup:\n"
        f"  ollama serve\n"
        f"  ollama pull {model_name()}\n"
    )


# --- Schema helpers ---

def string_property(description):
    return {"type": "string", "description": description}


def object_schema(properties, required):
    return {
        "type": "object",
        "properties": properties,
        "required": required,
    }


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


# --- Utility ---

def truncate(text, max_len=200):
    if not text:
        return ""
    if len(text) <= max_len:
        return text
    return text[:max_len] + "... [truncated]"


def format_nanos(nanos):
    if nanos <= 0:
        return "n/a"
    return f"{nanos / 1_000_000_000:.3f}s"


# --- Agent ---

class Agent:
    """Ollama-based coding agent with tools, hooks, and usage tracking."""

    SAFE_BASH_PREFIXES = [
        "pytest ", "grep ", "find ", "cat ", "ls ",
        "head ", "tail ", "wc ", "diff ", "git ",
    ]

    DEFAULT_SYSTEM_PROMPT = """\
You are a coding agent operating on the bookshelf/ codebase -- a FastAPI library service.
You have tools to read files, write files, run bash commands, grep, and glob.

Work methodically:
1. Understand the task by reading relevant files first
2. Plan your approach before making changes
3. Make changes carefully, one file at a time
4. Verify your changes by running: cd bookshelf && python -m pytest -q

Be precise. Don't guess at file contents -- read them first.
When you're done, summarize what you changed and why."""

    def __init__(self, max_turns=25):
        self.max_turns = max_turns
        self.system_prompt = self.DEFAULT_SYSTEM_PROMPT
        self.messages: list[dict] = []
        self.tools: dict[str, dict] = {}  # name -> {"schema": ..., "handler": ...}
        self.pending_messages: list[dict] = []
        self.trace_log: list[str] = []
        self.total_prompt_eval_count = 0
        self.total_eval_count = 0
        self.total_duration_ns = 0
        self.total_prompt_eval_duration_ns = 0
        self.total_eval_duration_ns = 0

        # Hooks (default None)
        self.after_write_hook = None   # (file_path, content) -> str | None
        self.after_done_hook = None    # (agent) -> bool

        # Register default tools
        self._register_default_tools()

    def _register_default_tools(self):
        self.register_tool(
            "read_file",
            "Read the contents of a file. Always read a file before modifying it.",
            {"path": string_property("Absolute or relative file path to read")},
            self._tool_read_file,
        )
        self.register_tool(
            "write_file",
            "Write content to a file. Creates parent directories if needed. "
            "Always read the file first to understand existing content.",
            {"path": string_property("File path to write to"),
             "content": string_property("The complete file content to write")},
            self._tool_write_file,
        )
        self.register_tool(
            "run_bash",
            "Run a bash command. Use for: pytest, pip, git diff, etc. "
            "Output is capped at 10k chars.",
            {"command": string_property("The bash command to run")},
            self._tool_run_bash,
        )
        self.register_tool(
            "grep_files",
            "Search file contents with grep. Returns matching lines with file:line prefixes.",
            {"pattern": string_property("Regex pattern to search for"),
             "path": string_property("Directory or file to search in")},
            self._tool_grep_files,
        )
        self.register_tool(
            "glob_files",
            "Find files by glob pattern.",
            {"pattern": string_property("Glob pattern, e.g. **/*.py"),
             "path": string_property("Base directory to search from")},
            self._tool_glob_files,
        )

    def register_tool(self, name, description, parameters, handler):
        """Register a tool with its schema and handler function.

        parameters: dict of property_name -> property_schema
        handler: callable(arguments_dict) -> str
        """
        schema = {
            "type": "function",
            "function": {
                "name": name,
                "description": description,
                "parameters": object_schema(
                    parameters,
                    list(parameters.keys()),
                ),
            },
        }
        self.tools[name] = {"schema": schema, "handler": handler}

    def prepend_system_prompt(self, text):
        self.system_prompt = text + "\n\n" + self.system_prompt

    def add_user_message(self, text):
        self.pending_messages.append({"role": "user", "content": text})

    def complete(self, system_prompt, user_message):
        """One-shot completion (no tools)."""
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_message},
        ]
        response = chat(messages, [])
        self._record_usage(response)
        msg = response.get("message")
        return (msg.get("content") or "") if msg else ""

    def run(self, task):
        """Main agent loop: add system + user messages, loop with tool calls."""
        print(f"Task: {task}")
        print(f"Model: {model_name()}")
        print()

        messages = [
            {"role": "system", "content": self.system_prompt},
            {"role": "user", "content": task},
        ]

        turn = 0
        while turn < self.max_turns:
            turn += 1
            self._trace(f"Turn {turn}")
            if turn == self.max_turns - 2:
                print(f"[warning] Approaching turn limit ({self.max_turns})")

            # Apply any pending messages
            self._apply_pending(messages)

            tool_schemas = [t["schema"] for t in self.tools.values()]
            response = chat(messages, tool_schemas)
            self._record_usage(response)

            assistant = response.get("message")
            if assistant is None:
                raise SystemExit(
                    f"Ollama response did not include a message.{setup_hint()}"
                )

            content = (assistant.get("content") or "").strip()
            if content:
                print(content)

            tool_calls = assistant.get("tool_calls") or []
            messages.append({
                "role": "assistant",
                "content": assistant.get("content"),
                "tool_calls": tool_calls if tool_calls else None,
            })

            if not tool_calls:
                if self.after_done_hook is not None and self.after_done_hook(self):
                    self._trace("after_done_hook queued more work")
                    continue
                break

            for tc in tool_calls:
                fn = tc.get("function", {})
                name = fn.get("name", "")
                arguments = fn.get("arguments", {})
                if isinstance(arguments, str):
                    arguments = json.loads(arguments)
                print(f"[turn {turn}] {name}")
                result = self._execute_tool(name, arguments)
                print(f"[result] {truncate(result)}")
                messages.append({"role": "tool", "content": result})

        if turn >= self.max_turns:
            print(f"[stopped] Turn limit reached ({self.max_turns})")
        print(f"\n=== Done in {turn} turns ===")
        self._print_trace_summary()

    # --- Internal ---

    def _apply_pending(self, messages):
        if not self.pending_messages:
            return
        messages.extend(self.pending_messages)
        self._trace(f"Applied {len(self.pending_messages)} pending user message(s)")
        self.pending_messages.clear()

    def _execute_tool(self, name, arguments):
        tool = self.tools.get(name)
        if tool is None:
            return f"Unknown tool: {name}"
        try:
            return tool["handler"](arguments)
        except Exception as e:
            return f"Tool failed: {e}"

    def _check_permission(self, tool, detail):
        if tool == "run_bash":
            for prefix in self.SAFE_BASH_PREFIXES:
                if detail.startswith(prefix):
                    return True
        print(f"[permission] {tool}: {detail}")
        answer = input("  Allow? (y/n): ").strip().lower()
        allowed = answer.startswith("y")
        self._trace(f"{'ALLOWED' if allowed else 'DENIED'}: {tool} -- {truncate(detail, 80)}")
        return allowed

    def _record_usage(self, response):
        self.total_prompt_eval_count += response.get("prompt_eval_count", 0)
        self.total_eval_count += response.get("eval_count", 0)
        self.total_duration_ns += response.get("total_duration", 0)
        self.total_prompt_eval_duration_ns += response.get("prompt_eval_duration", 0)
        self.total_eval_duration_ns += response.get("eval_duration", 0)
        self._trace(
            f"Usage: +{response.get('prompt_eval_count', 0)} prompt eval, "
            f"+{response.get('eval_count', 0)} eval tokens"
        )

    def _trace(self, event):
        timestamp = datetime.now(timezone.utc).isoformat()
        self.trace_log.append(f"[{timestamp}] {event}")

    def _print_trace_summary(self):
        print("\n=== Trace Summary ===")
        for entry in self.trace_log:
            print(f"  {entry}")
        print(f"  Total prompt eval count:       {self.total_prompt_eval_count}")
        print(f"  Total eval count:              {self.total_eval_count}")
        print(f"  Total duration:                {format_nanos(self.total_duration_ns)}")
        print(f"  Total prompt eval duration:    {format_nanos(self.total_prompt_eval_duration_ns)}")
        print(f"  Total eval duration:           {format_nanos(self.total_eval_duration_ns)}")
        print("  Estimated API cost: $0.0000 (local Ollama)")

    # --- Default tool implementations ---

    def _tool_read_file(self, arguments):
        path = arguments.get("path", "")
        if not path:
            return "Error: path is required."
        try:
            with open(path) as f:
                return f.read()
        except OSError as e:
            return f"Error: {e}"

    def _tool_write_file(self, arguments):
        path = arguments.get("path", "")
        content = arguments.get("content")
        if not path:
            return "Error: path is required."
        if content is None:
            return "Error: content is required."
        if not self._check_permission("write_file", path):
            self._trace(f"DENIED: write_file -- {path}")
            return "Permission denied by user."
        self._trace(f"ALLOWED: write_file -- {path}")
        try:
            p = Path(path)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content)
            result = f"Wrote {len(content)} chars to {path}"
        except OSError as e:
            return f"Error: {e}"

        # After-write hook
        if self.after_write_hook is not None:
            feedback = self.after_write_hook(path, content)
            if feedback is not None:
                return result + "\n\n--- SENSOR FEEDBACK ---\n" + feedback
        return result

    def _tool_run_bash(self, arguments):
        command = arguments.get("command", "")
        if not command:
            return "Error: command is required."
        if not self._check_permission("run_bash", command):
            self._trace(f"DENIED: run_bash -- {command}")
            return "Permission denied by user."
        self._trace(f"ALLOWED: run_bash -- {command}")
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

    def _tool_grep_files(self, arguments):
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

    def _tool_glob_files(self, arguments):
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


# --- Self-test when run directly ---

if __name__ == "__main__":
    task = (
        " ".join(sys.argv[1:])
        if len(sys.argv) > 1
        else "Read bookshelf/pyproject.toml and tell me what project this is."
    )
    agent = Agent(max_turns=25)
    agent.run(task)
