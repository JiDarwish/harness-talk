///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//SOURCES InnerHarness.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// Step 7: Linter sensor — feedback, computational. THE SIGNATURE DEMO.
// After every write, the linter runs automatically. If it finds issues,
// the LLM-readable error message is injected back. The agent self-corrects.
// "Custom linter messages that include instructions for the self-correction
//  — a positive kind of prompt injection." — Böckeler

static final int MAX_LINT_ITERATIONS = 5;

void main(String[] args) {
    IO.println("=== Step 7: Linter Sensor (Feedback, Computational, Ollama) ===");

    var agentsMd = readFile("outer-harness/AGENTS.md");
    var agent = new InnerHarness.Agent(25);
    agent.prependSystemPrompt("# Project Conventions\n\n" + agentsMd);

    // Register tools
    agent.registerTool(LoadSkill.SPEC, tu -> tu.input(LoadSkill.class).execute());
    agent.registerTool(RunCodemod.SPEC, tu -> tu.input(RunCodemod.class).execute());

    // THE KEY: afterWriteHook runs the linter on every written .java file
    var lintIterations = new int[]{0}; // mutable counter
    agent.afterWriteHook = (filePath, content) -> {
        if (!filePath.endsWith(".java")) return null;
        if (lintIterations[0] >= MAX_LINT_ITERATIONS) {
            IO.println("[linter] Iteration limit reached (" + MAX_LINT_ITERATIONS + "), skipping.");
            return null;
        }
        lintIterations[0]++;

        IO.println("[linter] Running ResultUnwrapChecker on " + filePath + " (iteration " + lintIterations[0] + ")");
        try {
            var proc = new ProcessBuilder("jbang", "outer-harness/linters/ResultUnwrapChecker.java", filePath)
                    .redirectErrorStream(true).start();
            var output = new String(proc.getInputStream().readAllBytes());
            var exitCode = proc.waitFor();
            if (exitCode != 0) {
                IO.println("[linter] Issues found! Injecting feedback.");
                return "LINTER FEEDBACK (you MUST fix these issues):\n\n" + output;
            } else {
                IO.println("[linter] Clean.");
                lintIterations[0] = 0; // Reset for next file
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    };

    var task = args.length > 0 ? String.join(" ", args)
            : "Add a method getBookSummary(Long id) to BookService that calls findBook, gets the book's title and availability, and returns a summary string. Access the result value directly — don't bother with pattern matching, just cast to Success and call .value(). Then verify it compiles.";
    // NOTE: This task deliberately asks the agent to use unsafe Result narrowing, so the linter catches it.

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
