#!/usr/bin/env python3
"""Step 10: The behaviour gap -- the honest frontier.
All four cells of the 2x2 are lit. All sensors pass.
And the agent still ships wrong code.

'This is the elephant in the room -- how do we guide and sense if the
 application functionally behaves the way we need it to? At the moment...
 puts a lot of faith into the AI-generated tests, that's not good enough yet.'
 -- Bockeler"""

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
    print("=== Step 10: The Behaviour Gap (Ollama) ===")
    print("Every sensor passes. The agent still ships the wrong thing.")
    print()

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

    # All sensors from Step 9 (linter + import checker)
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

    # Architecture review (uses architecture-review.md)
    review_done = [False]

    def review_hook(running_agent):
        if review_done[0]:
            return False
        review_done[0] = True

        print("\n[review] Running local Ollama architecture review...")

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
            return False

        # Read review skill
        try:
            with open("outer_harness/skills/architecture-review.md") as f:
                review_prompt = f.read()
        except OSError:
            review_prompt = "Review the following diff for architecture issues."

        review = running_agent.complete(
            review_prompt,
            "Review this diff:\n\n```diff\n" + diff + "\n```",
        )

        print(f"[review] {review}")

        if "[critical]" in review.lower():
            running_agent.add_user_message(
                "Architecture review found issues:\n\n" + review
            )
            return True

        print("[review] Architecture review passed.")
        print()
        print("=== All sensors passed. But is the code correct? ===")
        print("The agent used datetime.now() without timezone awareness.")
        print("The library opens at 9 AM Eastern, but the server runs in UTC.")
        print("A 9:30 AM local return shows as 1:30 PM UTC and gets charged.")
        print("The agent-written tests share the same assumption, so they pass too.")
        print("This is the behaviour gap -- the elephant in the room.")
        return False

    agent.after_done_hook = review_hook

    # A task that's structurally correct but behaviourally wrong:
    # The agent will almost certainly use datetime.now() without timezone
    # awareness. The library is in US/Eastern, but the server runs in UTC.
    # A member returning a book at 9:30 AM local time gets charged a late fee
    # because the server sees 1:30 PM UTC -- past the waiver window.
    # The agent-written tests will use the same wrong timezone assumption,
    # so they pass.
    task = (
        " ".join(sys.argv[1:])
        if len(sys.argv) > 1
        else "Add a late fee waiver feature to LoanService: if a member returns a "
             "book within 1 hour of the library opening time (9:00 AM), waive the "
             "late fee for that day. The library is in the US/Eastern timezone. "
             "Add a POST /api/loans/{id}/return-with-waiver endpoint to loan_routes "
             "and write a test."
    )

    agent.run(task)


if __name__ == "__main__":
    main()
