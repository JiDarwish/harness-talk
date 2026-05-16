#!/usr/bin/env python3
"""Step 7: Linter sensor -- feedback, computational. THE SIGNATURE DEMO.
After every write, the linter runs automatically. If it finds issues,
the LLM-readable error message is injected back. The agent self-corrects.
'Custom linter messages that include instructions for the self-correction
 -- a positive kind of prompt injection.' -- Bockeler"""

import subprocess
import sys

from inner_harness import Agent, string_property

MAX_LINT_ITERATIONS = 5


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
    print("=== Step 7: Linter Sensor (Feedback, Computational, Ollama) ===")

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

    # THE KEY: after_write_hook runs the linter on every written .py file
    lint_iterations = [0]  # mutable counter in closure

    def linter_hook(file_path, content):
        if not file_path.endswith(".py"):
            return None
        if lint_iterations[0] >= MAX_LINT_ITERATIONS:
            print(f"[linter] Iteration limit reached ({MAX_LINT_ITERATIONS}), skipping.")
            return None
        lint_iterations[0] += 1

        print(f"[linter] Running result_unwrap_checker on {file_path} "
              f"(iteration {lint_iterations[0]})")
        try:
            proc = subprocess.run(
                ["python", "outer_harness/linters/result_unwrap_checker.py", file_path],
                capture_output=True, text=True, timeout=30,
            )
            if proc.returncode != 0:
                output = proc.stdout + proc.stderr
                print("[linter] Issues found! Injecting feedback.")
                return (
                    "LINTER FEEDBACK -- MANDATORY FIX REQUIRED:\n\n"
                    + output
                    + "\n\nYou MUST immediately rewrite the file to fix these issues. "
                    "These are mandatory project safety rules, not suggestions. "
                    "Do NOT explain the problem, do NOT ask the user, do NOT try "
                    "running tests to verify. "
                    "Rewrite the code using match/case as shown above and call "
                    "write_file now."
                )
            else:
                print("[linter] Clean.")
                lint_iterations[0] = 0
                return None
        except Exception:
            return None

    agent.after_write_hook = linter_hook

    # NOTE: This task deliberately asks the agent to use unsafe Result access,
    # so the linter catches it.
    task = (
        " ".join(sys.argv[1:])
        if len(sys.argv) > 1
        else "Add a method get_book_summary(id) to BookService that calls find_book, "
             "gets the book's title and availability, and returns a summary string. "
             "Access the result value directly -- don't bother with match/case, just "
             "access .value on the result. Then verify it works with pytest."
    )

    agent.run(task)


if __name__ == "__main__":
    main()
