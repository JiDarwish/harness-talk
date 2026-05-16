#!/usr/bin/env python3
"""Step 5: Skills -- feedforward, inferential, progressive disclosure.
Same 2x2 cell as Step 4, different mechanism.
'Skills for progressive disclosure of knowledge.' -- HumanLayer"""

import sys

from inner_harness import Agent, string_property


def load_skill(arguments):
    name = arguments.get("name", "")
    if not name:
        return "Error: name is required."
    try:
        with open(f"outer_harness/skills/{name}.md") as f:
            content = f.read()
        return f"=== Skill: {name} ===\n\n" + content
    except FileNotFoundError:
        return (
            f"Skill not found: {name}. "
            "Available: how-to-write-tests, code-review, architecture-review"
        )


def main():
    print("=== Step 5: Skills (Feedforward, Inferential, Ollama) ===")

    # Load AGENTS.md
    try:
        with open("outer_harness/AGENTS.md") as f:
            agents_md = f.read()
    except OSError:
        agents_md = ""

    agent = Agent(max_turns=25)
    agent.prepend_system_prompt("# Project Conventions\n\n" + agents_md)

    # Register the load_skill tool
    agent.register_tool(
        "load_skill",
        "Load a skill by name. Skills provide detailed conventions and instructions. "
        "Available skills: how-to-write-tests, code-review, architecture-review. "
        "Call this when you need detailed guidance on a topic.",
        {"name": string_property("The skill name, e.g. 'how-to-write-tests'")},
        load_skill,
    )

    task = (
        " ".join(sys.argv[1:])
        if len(sys.argv) > 1
        else "Write a test for LoanService.return_book that verifies returning "
             "an overdue book still works correctly. Use the /how-to-write-tests "
             "skill for conventions."
    )

    agent.run(task)


if __name__ == "__main__":
    main()
