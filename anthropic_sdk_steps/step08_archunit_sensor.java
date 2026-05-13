///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.anthropic:anthropic-java:2.27.0
//DEPS org.slf4j:slf4j-nop:1.7.36
//SOURCES InnerHarness.java

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

// Step 8: ArchUnit sensor — feedback, computational.
// "Structural tests, feedback, computational, e.g. a pre-commit (or coding agent) hook
//  running ArchUnit tests that check for violations of module boundaries." — Böckeler
// Böckeler names ArchUnit by name. We're using exactly the tool she points at.

static final int MAX_LINT_ITERATIONS = 5;

void main(String[] args) {
    IO.println("=== Step 8: ArchUnit Sensor (Feedback, Computational) ===");

    var agentsMd = readFile("outer-harness/AGENTS.md");
    var agent = new InnerHarness.Agent(25);
    agent.prependSystemPrompt("# Project Conventions\n\n" + agentsMd);

    agent.registerTool(LoadSkill.class, tu -> tu.input(LoadSkill.class).execute());
    agent.registerTool(RunCodemod.class, tu -> tu.input(RunCodemod.class).execute());

    // afterWriteHook: run BOTH the linter AND ArchUnit
    var iterations = new AtomicInteger(0);
    agent.afterWriteHook = (filePath, content) -> {
        if (!filePath.endsWith(".java")) return null;
        if (iterations.get() >= MAX_LINT_ITERATIONS) return null;
        iterations.incrementAndGet();

        var feedback = new StringBuilder();

        // 1. Run linter
        try {
            var proc = new ProcessBuilder("jbang", "outer-harness/linters/ResultUnwrapChecker.java", filePath)
                    .redirectErrorStream(true).start();
            var output = new String(proc.getInputStream().readAllBytes());
            if (proc.waitFor() != 0) {
                IO.println("[linter] Issues found.");
                feedback.append("LINTER FEEDBACK:\n").append(output).append("\n\n");
            } else {
                IO.println("[linter] Clean.");
            }
        } catch (Exception e) { /* skip */ }

        // 2. Run ArchUnit (only for files in bookshelf/src/main)
        if (filePath.contains("bookshelf/src/main")) {
            IO.println("[archunit] Running architecture tests...");
            try {
                var proc = new ProcessBuilder("bash", "-c", "cd bookshelf && mvn test -Dtest=BookshelfArchitectureTest -q 2>&1")
                        .redirectErrorStream(true).start();
                var output = new String(proc.getInputStream().readAllBytes());
                if (proc.waitFor() != 0) {
                    IO.println("[archunit] Architecture violation!");
                    feedback.append("ARCHUNIT FEEDBACK:\n").append(output).append("\n\n");
                } else {
                    IO.println("[archunit] All rules pass.");
                }
            } catch (Exception e) { /* skip */ }
        }

        if (feedback.isEmpty()) {
            iterations.set(0);
            return null;
        }
        return "MANDATORY FIX REQUIRED:\n\n" + feedback
                + "You MUST immediately fix these issues. Do NOT explain the problem, do NOT ask the user. "
                + "Move or rewrite the offending code to comply, then call write_file now.";
    };

    var task = args.length > 0 ? String.join(" ", args)
            : "Add a new repository interface called OverdueBookRepository in the bookshelf project. Put it in the domain package (NOT persistence). Then run the tests.";
    // NOTE: This deliberately puts the repo in the wrong package so ArchUnit catches it.

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
