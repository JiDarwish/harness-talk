///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.anthropic:anthropic-java:2.27.0
//DEPS org.slf4j:slf4j-nop:1.7.36
//SOURCES InnerHarness.java

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.beta.messages.*;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// Step 10: The behaviour gap — the honest frontier.
// All four cells of the 2×2 are lit. All sensors pass.
// And the agent still ships wrong code.
//
// "This is the elephant in the room — how do we guide and sense if the
//  application functionally behaves the way we need it to? At the moment...
//  puts a lot of faith into the AI-generated tests, that's not good enough yet."
//  — Böckeler

static final int MAX_LINT_ITERATIONS = 5;

void main(String[] args) {
    IO.println("=== Step 10: The Behaviour Gap ===");
    IO.println("Every sensor passes. The agent still ships the wrong thing.");
    IO.println();

    var agentsMd = readFile("outer-harness/AGENTS.md");
    var agent = new InnerHarness.Agent(25);
    agent.prependSystemPrompt("# Project Conventions\n\n" + agentsMd);

    agent.registerTool(LoadSkill.class, tu -> tu.input(LoadSkill.class).execute());
    agent.registerTool(RunCodemod.class, tu -> tu.input(RunCodemod.class).execute());

    // All sensors from Step 9 (linter + ArchUnit)
    var iterations = new int[]{0};
    agent.afterWriteHook = (filePath, content) -> {
        if (!filePath.endsWith(".java")) return null;
        if (iterations[0] >= MAX_LINT_ITERATIONS) return null;
        iterations[0]++;

        var feedback = new StringBuilder();

        try {
            var proc = new ProcessBuilder("jbang", "outer-harness/linters/ResultUnwrapChecker.java", filePath)
                    .redirectErrorStream(true).start();
            var output = new String(proc.getInputStream().readAllBytes());
            if (proc.waitFor() != 0) feedback.append("LINTER FEEDBACK:\n").append(output).append("\n\n");
        } catch (Exception e) { /* skip */ }

        if (filePath.contains("bookshelf/src/main")) {
            try {
                var proc = new ProcessBuilder("bash", "-c", "cd bookshelf && mvn test -Dtest=BookshelfArchitectureTest -q 2>&1")
                        .redirectErrorStream(true).start();
                var output = new String(proc.getInputStream().readAllBytes());
                if (proc.waitFor() != 0) feedback.append("ARCHUNIT FEEDBACK:\n").append(output).append("\n\n");
            } catch (Exception e) { /* skip */ }
        }

        if (feedback.isEmpty()) { iterations[0] = 0; return null; }
        return feedback.toString();
    };

    // Architecture review (uses architecture-review.md — not code-review.md)
    var reviewDone = new boolean[]{false};
    agent.afterDoneHook = (paramsBuilder) -> {
        if (reviewDone[0]) return false;
        reviewDone[0] = true;

        IO.println("\n[review] Running architecture review...");

        String diff;
        try {
            var proc = new ProcessBuilder("git", "diff").redirectErrorStream(true).start();
            diff = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
        } catch (Exception e) { return false; }

        if (diff.isBlank()) return false;

        var reviewPrompt = readFile("outer-harness/skills/architecture-review.md");
        var reviewClient = AnthropicOkHttpClient.fromEnv();
        var reviewParams = MessageCreateParams.builder()
                .model(Model.CLAUDE_HAIKU_4_5)
                .maxTokens(4096)
                .system(reviewPrompt)
                .addUserMessage("Review this diff:\n\n```diff\n" + diff + "\n```")
                .build();

        var reviewMessage = reviewClient.beta().messages().create(reviewParams);
        var reviewText = new StringBuilder();
        for (var block : reviewMessage.content()) {
            block.text().ifPresent(t -> reviewText.append(t.text()));
        }

        IO.println("[review] " + reviewText);

        if (reviewText.toString().toLowerCase().contains("[critical]")) {
            paramsBuilder.addUserMessage("Architecture review found issues:\n\n" + reviewText);
            return true;
        }

        IO.println("[review] Architecture review passed.");
        IO.println();
        IO.println("=== All sensors passed. But is the code correct? ===");
        IO.println("The due-date calculation is wrong. No sensor caught it.");
        IO.println("This is the behaviour gap — the elephant in the room.");
        return false;
    };

    // A task that's structurally correct but behaviourally wrong:
    // the agent will almost certainly implement 7 calendar days, not 7 business days.
    // All sensors pass because the code is structurally correct.
    // But the behaviour is wrong.
    var task = args.length > 0 ? String.join(" ", args)
            : "Add a loan extension feature: POST /api/loans/{id}/extend should extend the due date by 7 days. But the business rule is: the extension should be 7 BUSINESS days (excluding weekends), not 7 calendar days. Implement it in LoanService, add the controller endpoint, and write a test.";

    agent.run(task);
}

@JsonClassDescription("Run the result-type-rewrite codemod to convert a method from throwing exceptions to returning Result<T, BookshelfError>. Much more reliable than manual editing for this transformation.")
static class RunCodemod {
    @JsonPropertyDescription("Path to the Java file to modify")
    public String file;
    @JsonPropertyDescription("Name of the method to rewrite")
    public String method;

    public String execute() {
        try {
            var proc = new ProcessBuilder("jbang", "outer-harness/codemods/ResultTypeRewrite.java", file, method)
                    .redirectErrorStream(true).start();
            var output = new String(proc.getInputStream().readAllBytes());
            var exitCode = proc.waitFor();
            return output + "\n[exit code: " + exitCode + "]";
        } catch (Exception e) {
            return "Error running codemod: " + e.getMessage();
        }
    }
}

@JsonClassDescription("Load a skill by name for detailed guidance. Available: how-to-write-tests, code-review, architecture-review")
static class LoadSkill {
    @JsonPropertyDescription("The skill name")
    public String name;

    public String execute() {
        try { return Files.readString(Path.of("outer-harness/skills/" + name + ".md")); }
        catch (IOException e) { return "Skill not found: " + name; }
    }
}

String readFile(String path) {
    try { return Files.readString(Path.of(path)); }
    catch (IOException e) { return ""; }
}
