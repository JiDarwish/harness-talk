#!/usr/bin/env python3
"""Step 6: Codemod -- feedforward, computational.
'Code mods, feedforward, computational, e.g. a tool with access to
OpenRewrite recipes.' -- Bockeler"""

import subprocess
import sys

from inner_harness import Agent, string_property


def load_skill(arguments):
    name = arguments.get("name", "")
    if not name:
        return "Error: name is required."
    try:
        with open(f"outer_harness/skills/{name}.md") as f:
            return f.read()
    except FileNotFoundError:
        return f"Skill not found: {name}"


def run_codemod(arguments):
    file = arguments.get("file", "")
    method = arguments.get("method", "")
    if not file:
        return "Error: file is required."
    if not method:
        return "Error: method is required."
    try:
        result = subprocess.run(
            ["python", "outer_harness/codemods/result_type_rewrite.py", file, method],
            capture_output=True, text=True, timeout=60,
        )
        return result.stdout + result.stderr + f"\n[exit code: {result.returncode}]"
    except Exception as e:
        return f"Error running codemod: {e}"


def main():
    print("=== Step 6: Codemod Tool (Feedforward, Computational, Ollama) ===")

    # Load AGENTS.md
    try:
        with open("outer_harness/AGENTS.md") as f:
            agents_md = f.read()
    except OSError:
        agents_md = ""

    agent = Agent(max_turns=25)
    agent.prepend_system_prompt("# Project Conventions\n\n" + agents_md)

    # Register skill and codemod tools
    agent.register_tool(
        "load_skill",
        "Load a skill by name for detailed guidance. "
        "Available: how-to-write-tests, code-review, architecture-review",
        {"name": string_property("The skill name")},
        load_skill,
    )
    agent.register_tool(
        "run_codemod",
        "Run the result-type-rewrite codemod to convert a method from throwing "
        "exceptions to returning Result. Much more reliable than manual editing "
        "for this transformation.",
        {"file": string_property("Path to the Python file to modify"),
         "method": string_property("Name of the method to rewrite")},
        run_codemod,
    )

    task = (
        " ".join(sys.argv[1:])
        if len(sys.argv) > 1
        else "Convert the BookService.find_active_loan method to return "
             "Result[Loan, BookshelfError] using the codemod tool, then verify "
             "the tests pass."
    )

    agent.run(task)


if __name__ == "__main__":
    main()
