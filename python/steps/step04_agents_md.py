#!/usr/bin/env python3
"""Step 4: AGENTS.md -- feedforward, inferential.
The agent reads the team's conventions and follows them.
'Coding conventions, feedforward, inferential, e.g. AGENTS.md, Skills.' -- Bockeler"""

import sys

from inner_harness import Agent


def main():
    print("=== Step 4: AGENTS.md (Feedforward, Inferential, Ollama) ===")

    # Load AGENTS.md and prepend to system prompt
    try:
        with open("outer_harness/AGENTS.md") as f:
            agents_md = f.read()
    except OSError as e:
        print(f"[warning] Could not read outer_harness/AGENTS.md: {e}")
        agents_md = ""

    agent = Agent(max_turns=25)
    agent.prepend_system_prompt("# Project Conventions\n\n" + agents_md)

    task = (
        " ".join(sys.argv[1:])
        if len(sys.argv) > 1
        else "Add a new endpoint POST /api/books/{book_id}/extend-loan that extends "
             "an active loan by one week. Include the service method, router endpoint, "
             "and a test."
    )

    agent.run(task)


if __name__ == "__main__":
    main()
