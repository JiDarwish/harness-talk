///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.anthropic:anthropic-java:2.27.0
//DEPS org.slf4j:slf4j-nop:1.7.36
//SOURCES InnerHarness.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// Step 4: AGENTS.md — feedforward, inferential.
// The agent reads the team's conventions and follows them.
// "Coding conventions, feedforward, inferential, e.g. AGENTS.md, Skills." — Böckeler

void main(String[] args) {
    IO.println("=== Step 4: AGENTS.md (Feedforward, Inferential) ===");

    // Load AGENTS.md and prepend to system prompt
    var agentsMd = readFile("outer-harness/AGENTS.md");
    var agent = new InnerHarness.Agent(25);
    agent.prependSystemPrompt("# Project Conventions\n\n" + agentsMd);

    var task = args.length > 0 ? String.join(" ", args)
            : "Add a new endpoint POST /api/books/{bookId}/extend-loan that extends an active loan by one week. Include the service method, controller endpoint, and a test.";

    agent.run(task);
}

String readFile(String path) {
    try {
        return Files.readString(Path.of(path));
    } catch (IOException e) {
        IO.println("[warning] Could not read " + path + ": " + e.getMessage());
        return "";
    }
}
