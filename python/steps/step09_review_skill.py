#!/usr/bin/env python3
"""Step 9: Review skill -- feedback, inferential. LLM-as-judge.
'Instructions how to review, feedback, inferential, e.g. Skills.' -- Bockeler"""

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
    print("=== Step 9: Review Skill (Feedback, Inferential, Ollama) ===")

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

    # Linter + import checker sensor (same as Step 8)
    lint_iterations = [0]

    def combined_sensor_hook(file_path, content):
        if not file_path.endswith(".py"):
            return None
        if lint_iterations[0] >= MAX_LINT_ITERATIONS:
            return None
        lint_iterations[0] += 1

        feedback = []

        try:
            proc = subprocess.run(
                ["python", "outer_harness/linters/result_unwrap_checker.py", file_path],
                capture_output=True, text=True, timeout=30,
            )
            if proc.returncode != 0:
                feedback.append("LINTER FEEDBACK:\n" + proc.stdout + proc.stderr)
        except Exception:
            pass

        if "bookshelf/" in file_path and "bookshelf/tests/" not in file_path:
            try:
                proc = subprocess.run(
                    ["python", "outer_harness/arch/import_checker.py", "bookshelf"],
                    capture_output=True, text=True, timeout=30,
                )
                if proc.returncode != 0:
                    feedback.append("IMPORT CHECK FEEDBACK:\n" + proc.stdout + proc.stderr)
            except Exception:
                pass

        if not feedback:
            lint_iterations[0] = 0
            return None

        return (
            "MANDATORY FIX REQUIRED:\n\n"
            + "\n\n".join(feedback)
            + "\n\nYou MUST immediately fix these issues. Do NOT explain the problem, "
            "do NOT ask the user. "
            "Move or rewrite the offending code to comply, then call write_file now."
        )

    agent.after_write_hook = combined_sensor_hook

    # THE KEY: afterDoneHook runs the LLM-as-judge review
    review_done = [False]  # mutable flag in closure

    def review_hook(running_agent):
        if review_done[0]:
            return False
        review_done[0] = True

        print("\n[review] Running local Ollama code review...")

        # Get git diff
        try:
            proc = subprocess.run(
                ["git", "diff"],
                capture_output=True, text=True, timeout=30,
            )
            diff = proc.stdout
        except Exception as e:
            print(f"[review] Could not get diff: {e}")
            return False

        if not diff.strip():
            print("[review] No changes to review.")
            return False

        # Read review skill
        try:
            with open("outer_harness/skills/architecture-review.md") as f:
                review_prompt = f.read()
        except OSError:
            review_prompt = "Review the following diff for code quality issues."

        review = running_agent.complete(
            review_prompt,
            "Review this diff:\n\n```diff\n" + diff + "\n```",
        )

        print(f"[review] {review}")

        if "[critical]" in review.lower() or "[high]" in review.lower():
            print("[review] Issues found - injecting feedback for agent to fix.")
            running_agent.add_user_message(
                "A code reviewer found the following issues with your changes. "
                "Please fix them:\n\n" + review
            )
            return True

        print("[review] Architecture review passed.")
        return False

    agent.after_done_hook = review_hook

    task = (
        " ".join(sys.argv[1:])
        if len(sys.argv) > 1
        else "Add a test for BookService.find_all_books that verifies it returns both "
             "books when two are added. Write the test data inline (don't use fixtures)."
    )

    agent.run(task)


if __name__ == "__main__":
    main()
