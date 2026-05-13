///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//SOURCES InnerHarness.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// Step 5: Skills — feedforward, inferential, progressive disclosure.
// Same 2×2 cell as Step 4, different mechanism.
// "Skills for progressive disclosure of knowledge." — HumanLayer

void main(String[] args) {
    IO.println("=== Step 5: Skills (Feedforward, Inferential, Ollama) ===");

    // Load AGENTS.md
    var agentsMd = readFile("outer-harness/AGENTS.md");
    var agent = new InnerHarness.Agent(25);
    agent.prependSystemPrompt("# Project Conventions\n\n" + agentsMd);

    // Register the load_skill tool
    agent.registerTool(LoadSkill.SPEC, tu -> tu.input(LoadSkill.class).execute());

    var task = args.length > 0 ? String.join(" ", args)
            : "Write a test for LoanService.returnBook that verifies returning an overdue book still works correctly. Use the /how-to-write-tests skill for conventions.";

    agent.run(task);
}

static class LoadSkill {
    static final InnerHarness.ToolSpec SPEC = new InnerHarness.ToolSpec(
            "load_skill",
            "Load a skill by name. Skills provide detailed conventions and instructions. Available skills: how-to-write-tests, code-review, architecture-review. Call this when you need detailed guidance on a topic.",
            InnerHarness.objectSchema(Map.of(
                    "name", InnerHarness.stringProperty("The skill name, e.g. 'how-to-write-tests'")),
                    List.of("name")));

    public String name;

    public String execute() {
        var skillPath = Path.of("outer-harness/skills/" + name + ".md");
        try {
            var content = Files.readString(skillPath);
            return "=== Skill: " + name + " ===\n\n" + content;
        } catch (IOException e) {
            return "Skill not found: " + name + ". Available: how-to-write-tests, code-review, architecture-review";
        }
    }
}

String readFile(String path) {
    try { return Files.readString(Path.of(path)); }
    catch (IOException e) { return ""; }
}
