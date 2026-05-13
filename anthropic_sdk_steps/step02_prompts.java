///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.anthropic:anthropic-java:2.27.0
//DEPS org.slf4j:slf4j-nop:1.7.36

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.beta.messages.*;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Step 2: System prompt + turn limit.
// The agent gets instructions. Behaviour improves visibly.

static final int MAX_OUTPUT = 10_000;
static final int MAX_TURNS = 25;

static final String SYSTEM_PROMPT = """
        You are a coding agent operating on the bookshelf/ codebase — a Spring Boot library service.
        You have tools to read files, write files, run bash commands, grep, and glob.

        Work methodically:
        1. Understand the task by reading relevant files first
        2. Plan your approach before making changes
        3. Make changes carefully, one file at a time
        4. Verify your changes compile and tests pass using: cd bookshelf && mvn test -q

        Be precise. Don't guess at file contents — read them first.
        When you're done, summarize what you changed and why.
        """;

void main(String[] args) {
    var client = AnthropicOkHttpClient.fromEnv();
    var task = args.length > 0 ? String.join(" ", args)
            : "Find any failing tests in bookshelf/ and fix them.";

    IO.println("=== Step 2: System Prompt + Turn Limit ===");
    IO.println("Task: " + task);
    IO.println();

    var paramsBuilder = MessageCreateParams.builder()
            .model(Model.CLAUDE_HAIKU_4_5)
            .maxTokens(8192)
            .system(SYSTEM_PROMPT)
            .addTool(ReadFile.class)
            .addTool(WriteFile.class)
            .addTool(RunBash.class)
            .addTool(GrepFiles.class)
            .addTool(GlobFiles.class)
            .addUserMessage(task);

    var turn = 0;
    while (turn < MAX_TURNS) {
        turn++;
        if (turn == MAX_TURNS - 2) IO.println("[warning] Approaching turn limit (" + MAX_TURNS + ")");

        var message = client.beta().messages().create(paramsBuilder.build());

        var toolUseBlocks = new ArrayList<BetaToolUseBlock>();
        var assistantBlocks = new ArrayList<BetaContentBlockParam>();
        final var currentTurn = turn;

        for (var block : message.content()) {
            block.text().ifPresent(textBlock -> {
                IO.println(textBlock.text());
                assistantBlocks.add(BetaContentBlockParam.ofText(
                        BetaTextBlockParam.builder().text(textBlock.text()).build()));
            });
            block.toolUse().ifPresent(toolUse -> {
                IO.println("[turn " + currentTurn + "] " + toolUse.name());
                toolUseBlocks.add(toolUse);
                assistantBlocks.add(BetaContentBlockParam.ofToolUse(
                        BetaToolUseBlockParam.builder()
                                .name(toolUse.name())
                                .id(toolUse.id())
                                .input(toolUse._input())
                                .build()));
            });
        }

        if (toolUseBlocks.isEmpty()) break;

        paramsBuilder.addAssistantMessageOfBetaContentBlockParams(assistantBlocks);

        var toolResults = new ArrayList<BetaContentBlockParam>();
        for (var toolUse : toolUseBlocks) {
            var result = executeTool(toolUse);
            IO.println("[result] " + truncate(result, 200));
            toolResults.add(BetaContentBlockParam.ofToolResult(
                    BetaToolResultBlockParam.builder()
                            .toolUseId(toolUse.id())
                            .contentAsJson(Map.of("output", result))
                            .build()));
        }
        paramsBuilder.addUserMessageOfBetaContentBlockParams(toolResults);
    }

    if (turn >= MAX_TURNS) IO.println("[stopped] Turn limit reached (" + MAX_TURNS + ")");
    IO.println("\n=== Done in " + turn + " turns ===");
}

String executeTool(BetaToolUseBlock toolUse) {
    return switch (toolUse.name()) {
        case "read_file" -> toolUse.input(ReadFile.class).execute();
        case "write_file" -> toolUse.input(WriteFile.class).execute();
        case "run_bash" -> toolUse.input(RunBash.class).execute();
        case "grep_files" -> toolUse.input(GrepFiles.class).execute();
        case "glob_files" -> toolUse.input(GlobFiles.class).execute();
        default -> "Unknown tool: " + toolUse.name();
    };
}

String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "... [truncated]";
}

// --- Tools (same as Step 1, with richer descriptions) ---

@JsonClassDescription("Read the contents of a file. Always read a file before modifying it.")
static class ReadFile {
    @JsonPropertyDescription("Absolute or relative file path to read")
    public String path;

    public String execute() {
        try { return Files.readString(Path.of(path)); }
        catch (IOException e) { return "Error: " + e.getMessage(); }
    }
}

@JsonClassDescription("Write content to a file. Creates parent directories if needed. Always read the file first to understand existing content.")
static class WriteFile {
    @JsonPropertyDescription("File path to write to")
    public String path;
    @JsonPropertyDescription("The complete file content to write")
    public String content;

    public String execute() {
        try {
            var p = Path.of(path);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, content);
            return "Wrote " + content.length() + " chars to " + path;
        } catch (IOException e) { return "Error: " + e.getMessage(); }
    }
}

@JsonClassDescription("Run a bash command. Use for: mvn test, mvn compile, git diff, etc. Output is capped at 10k chars.")
static class RunBash {
    @JsonPropertyDescription("The bash command to run")
    public String command;

    public String execute() {
        try {
            var proc = new ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start();
            var out = new String(proc.getInputStream().readAllBytes());
            var code = proc.waitFor();
            var result = out.length() > MAX_OUTPUT ? out.substring(0, MAX_OUTPUT) + "\n...[truncated]" : out;
            return result + "\n[exit code: " + code + "]";
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }
}

@JsonClassDescription("Search file contents with grep. Returns matching lines with file:line prefixes. Use to find usages, patterns, or definitions.")
static class GrepFiles {
    @JsonPropertyDescription("Regex pattern to search for")
    public String pattern;
    @JsonPropertyDescription("Directory or file to search in")
    public String path;

    public String execute() {
        try {
            var proc = new ProcessBuilder("grep", "-rn", "--include=*.java", pattern, path)
                    .redirectErrorStream(true).start();
            var out = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            return out.length() > MAX_OUTPUT ? out.substring(0, MAX_OUTPUT) + "\n...[truncated]"
                    : out.isEmpty() ? "No matches." : out;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }
}

@JsonClassDescription("Find files by glob pattern. Use to discover project structure, find test files, etc.")
static class GlobFiles {
    @JsonPropertyDescription("Glob pattern, e.g. **/*.java or src/**/Test*.java")
    public String pattern;
    @JsonPropertyDescription("Base directory to search from")
    public String path;

    public String execute() {
        try {
            var base = Path.of(path);
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            try (var stream = Files.walk(base)) {
                var matches = stream.filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(base.relativize(p)))
                        .map(Path::toString).collect(Collectors.joining("\n"));
                return matches.isEmpty() ? "No files matched." : matches;
            }
        } catch (IOException e) { return "Error: " + e.getMessage(); }
    }
}
