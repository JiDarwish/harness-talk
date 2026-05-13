///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//SOURCES InnerHarness.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    IO.println("=== Step 10: The Behaviour Gap (Ollama) ===");
    IO.println("Every sensor passes. The agent still ships the wrong thing.");
    IO.println();

    var agentsMd = readFile("outer-harness/AGENTS.md");
    var agent = new InnerHarness.Agent(25);
    agent.prependSystemPrompt("# Project Conventions\n\n" + agentsMd);

    agent.registerTool(LoadSkill.SPEC, tu -> tu.input(LoadSkill.class).execute());
    agent.registerTool(RunCodemod.SPEC, tu -> tu.input(RunCodemod.class).execute());

    // All sensors from Step 9 (linter + ArchUnit)
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

    // Architecture review (uses architecture-review.md — not code-review.md)
    var reviewDone = new AtomicBoolean(false);
    agent.afterDoneHook = (runningAgent) -> {
        if (reviewDone.get()) return false;
        reviewDone.set(true);

        IO.println("\n[review] Running local Ollama architecture review...");

        String diff;
        try {
            var proc = new ProcessBuilder("git", "diff").redirectErrorStream(true).start();
            diff = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
        } catch (Exception e) {
            IO.println("[review] Could not get diff: " + e.getMessage());
            return false;
        }

        if (diff.isBlank()) return false;

        var reviewPrompt = readFile("outer-harness/skills/architecture-review.md");
        var review = runningAgent.complete(
                reviewPrompt,
                "Review this diff:\n\n```diff\n" + diff + "\n```");

        IO.println("[review] " + review);

        if (review.toLowerCase().contains("[critical]")) {
            runningAgent.addUserMessage("Architecture review found issues:\n\n" + review);
            return true;
        }

        IO.println("[review] Architecture review passed.");
        IO.println();
        IO.println("=== All sensors passed. But is the code correct? ===");
        IO.println("The agent used LocalDateTime without timezone awareness.");
        IO.println("The library opens at 9 AM Eastern, but the server runs in UTC.");
        IO.println("A 9:30 AM local return shows as 1:30 PM UTC and gets charged.");
        IO.println("The agent-written tests share the same assumption, so they pass too.");
        IO.println("This is the behaviour gap -- the elephant in the room.");
        return false;
    };

    // A task that's structurally correct but behaviourally wrong:
    // The agent will almost certainly use LocalDate/LocalDateTime without timezone
    // awareness. The library is in US/Eastern, but the server runs in UTC.
    // A member returning a book at 9:30 AM local time gets charged a late fee
    // because the server sees 1:30 PM UTC -- past the waiver window.
    // The agent-written tests will use the same wrong timezone assumption, so they pass.
    var task = args.length > 0 ? String.join(" ", args)
            : "Add a late fee waiver feature to LoanService: if a member returns a book within 1 hour of the library opening time (9:00 AM), waive the late fee for that day. The library is in the US/Eastern timezone. Add a POST /api/loans/{id}/return-with-waiver endpoint to LoanController and write a test.";

    agent.run(task);
}

static class RunCodemod {
    static final InnerHarness.ToolSpec SPEC = new InnerHarness.ToolSpec(
            "run_codemod",
            "Run the result-type-rewrite codemod to convert a method from throwing exceptions to returning Result<T, BookshelfError>. Much more reliable than manual editing for this transformation.",
            InnerHarness.objectSchema(Map.of(
                    "file", InnerHarness.stringProperty("Path to the Java file to modify"),
                    "method", InnerHarness.stringProperty("Name of the method to rewrite")),
                    List.of("file", "method")));

    public String file;
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

static class LoadSkill {
    static final InnerHarness.ToolSpec SPEC = new InnerHarness.ToolSpec(
            "load_skill",
            "Load a skill by name for detailed guidance. Available: how-to-write-tests, code-review, architecture-review",
            InnerHarness.objectSchema(Map.of(
                    "name", InnerHarness.stringProperty("The skill name")),
                    List.of("name")));

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
