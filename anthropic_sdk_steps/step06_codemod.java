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

// Step 6: Codemod — feedforward, computational.
// "Code mods, feedforward, computational, e.g. a tool with access to OpenRewrite recipes." — Böckeler

void main(String[] args) {
    IO.println("=== Step 6: Codemod Tool (Feedforward, Computational) ===");

    var agentsMd = readFile("outer-harness/AGENTS.md");
    var agent = new InnerHarness.Agent(25);
    agent.prependSystemPrompt("# Project Conventions\n\n" + agentsMd);

    // Register skill and codemod tools
    agent.registerTool(LoadSkill.class, tu -> tu.input(LoadSkill.class).execute());
    agent.registerTool(RunCodemod.class, tu -> tu.input(RunCodemod.class).execute());

    var task = args.length > 0 ? String.join(" ", args)
            : "Convert the BookService.findActiveLoan method to return Result<Loan, BookshelfError> using the codemod tool, then verify the build passes.";

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
