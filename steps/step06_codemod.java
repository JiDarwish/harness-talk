///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//SOURCES InnerHarness.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// Step 6: Codemod — feedforward, computational.
// "Code mods, feedforward, computational, e.g. a tool with access to OpenRewrite recipes." — Böckeler

void main(String[] args) {
    IO.println("=== Step 6: Codemod Tool (Feedforward, Computational, Ollama) ===");

    var agentsMd = readFile("outer-harness/AGENTS.md");
    var agent = new InnerHarness.Agent(25);
    agent.prependSystemPrompt("# Project Conventions\n\n" + agentsMd);

    // Register skill and codemod tools
    agent.registerTool(LoadSkill.SPEC, tu -> tu.input(LoadSkill.class).execute());
    agent.registerTool(RunCodemod.SPEC, tu -> tu.input(RunCodemod.class).execute());

    var task = args.length > 0 ? String.join(" ", args)
            : "Convert the BookService.findActiveLoan method to return Result<Loan, BookshelfError> using the codemod tool, then verify the build passes.";

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
