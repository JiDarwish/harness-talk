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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

// Step 9: Review skill — feedback, inferential. LLM-as-judge.
// "Instructions how to review, feedback, inferential, e.g. Skills." — Böckeler
// "Separating the agent doing the work from the agent judging it proves
//  to be a strong lever." — Anthropic

static final int MAX_LINT_ITERATIONS = 5;

void main(String[] args) {
    IO.println("=== Step 9: Review Skill (Feedback, Inferential) ===");

    var agentsMd = readFile("outer-harness/AGENTS.md");
    var agent = new InnerHarness.Agent(25);
    agent.prependSystemPrompt("# Project Conventions\n\n" + agentsMd);

    agent.registerTool(LoadSkill.class, tu -> tu.input(LoadSkill.class).execute());
    agent.registerTool(RunCodemod.class, tu -> tu.input(RunCodemod.class).execute());

    // Linter + ArchUnit sensor (same as Step 8)
    var iterations = new AtomicInteger(0);
    agent.afterWriteHook = (filePath, content) -> {
        if (!filePath.endsWith(".java")) return null;
        if (iterations.get() >= MAX_LINT_ITERATIONS) return null;
        iterations.incrementAndGet();

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

        if (feedback.isEmpty()) { iterations.set(0); return null; }
        return "MANDATORY FIX REQUIRED:\n\n" + feedback
                + "You MUST immediately fix these issues. Do NOT explain the problem, do NOT ask the user. "
                + "Move or rewrite the offending code to comply, then call write_file now.";
    };

    // THE KEY: afterDoneHook runs the LLM-as-judge review
    var reviewDone = new AtomicBoolean(false);
    agent.afterDoneHook = (paramsBuilder) -> {
        if (reviewDone.get()) return false; // Only review once
        reviewDone.set(true);

        IO.println("\n[review] Running LLM-as-judge code review...");

        // Get the diff
        String diff;
        try {
            var proc = new ProcessBuilder("git", "diff").redirectErrorStream(true).start();
            diff = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
        } catch (Exception e) {
            IO.println("[review] Could not get diff: " + e.getMessage());
            return false;
        }

        if (diff.isBlank()) {
            IO.println("[review] No changes to review.");
            return false;
        }

        // Load the review skill
        var reviewPrompt = readFile("outer-harness/skills/code-review.md");

        // Make a separate API call — the sub-agent pattern
        var reviewClient = AnthropicOkHttpClient.fromEnv();
        var reviewParams = MessageCreateParams.builder()
                .model(Model.CLAUDE_HAIKU_4_5)
                .maxTokens(4096)
                .system(reviewPrompt)
                .addUserMessage("Review this diff against the bookshelf codebase conventions:\n\n```diff\n" + diff + "\n```")
                .build();

        var reviewMessage = reviewClient.beta().messages().create(reviewParams);
        var reviewText = new StringBuilder();
        for (var block : reviewMessage.content()) {
            block.text().ifPresent(t -> reviewText.append(t.text()));
        }

        var review = reviewText.toString();
        IO.println("[review] " + review);

        // If review found issues, inject them back
        if (review.toLowerCase().contains("[critical]") || review.toLowerCase().contains("[high]")) {
            IO.println("[review] Issues found — injecting feedback for agent to fix.");
            paramsBuilder.addUserMessage(
                    "A code reviewer found the following issues with your changes. Please fix them:\n\n" + review);
            return true; // Continue the agent loop
        }

        IO.println("[review] Review passed.");
        return false;
    };

    var task = args.length > 0 ? String.join(" ", args)
            : "Add a test for BookService.findAllBooks that verifies it returns both books when two are added. Write the test data inline (don't use fixtures).";
    // NOTE: Deliberately asks to inline test data, which the review skill catches.

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
