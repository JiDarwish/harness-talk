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
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Step 1: Five tools — read, write, bash, grep, glob.
// The substrate for both feedforward and feedback, later.

static final int MAX_OUTPUT = 10_000;

void main(String[] args) {
    var client = AnthropicOkHttpClient.fromEnv();
    var task = args.length > 0 ? String.join(" ", args)
            : "Find any failing tests in bookshelf/ and fix them.";

    IO.println("=== Step 1: Tool Registry ===");
    IO.println("Task: " + task);
    IO.println();

    var paramsBuilder = MessageCreateParams.builder()
//            .model(Model.CLAUDE_HAIKU_4_5)
            .model(Model.CLAUDE_SONNET_4_5)
            .maxTokens(8192)
            .addTool(ReadFile.class)
            .addTool(WriteFile.class)
            .addTool(RunBash.class)
            .addTool(GrepFiles.class)
            .addTool(GlobFiles.class)
            .addUserMessage(task);

    while (true) {
        var message = client.beta().messages().create(paramsBuilder.build());

        var toolUseBlocks = new ArrayList<BetaToolUseBlock>();
        var assistantBlocks = new ArrayList<BetaContentBlockParam>();

        for (var block : message.content()) {
            block.text().ifPresent(textBlock -> {
                IO.println(textBlock.text());
                assistantBlocks.add(BetaContentBlockParam.ofText(
                        BetaTextBlockParam.builder().text(textBlock.text()).build()));
            });
            block.toolUse().ifPresent(toolUse -> {
                IO.println("[tool] " + toolUse.name());
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

// --- Tools ---

@JsonClassDescription("Read the contents of a file at the given path")
static class ReadFile {
    @JsonPropertyDescription("The file path to read")
    public String path;

    public String execute() {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }
}

@JsonClassDescription("Write content to a file at the given path, creating directories as needed")
static class WriteFile {
    @JsonPropertyDescription("The file path to write to")
    public String path;
    @JsonPropertyDescription("The full content to write to the file")
    public String content;

    public String execute() {
        try {
            var p = Path.of(path);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, content);
            return "Wrote " + content.length() + " chars to " + path;
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }
}

@JsonClassDescription("Run a bash command and return its output. Use for builds, tests, and system commands.")
static class RunBash {
    @JsonPropertyDescription("The bash command to execute")
    public String command;

    public String execute() {
        try {
            var process = new ProcessBuilder("bash", "-c", command)
                    .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes());
            var exitCode = process.waitFor();
            var result = output.length() > MAX_OUTPUT
                    ? output.substring(0, MAX_OUTPUT) + "\n... [truncated at " + MAX_OUTPUT + " chars]"
                    : output;
            return result + "\n[exit code: " + exitCode + "]";
        } catch (Exception e) {
            return "Error running command: " + e.getMessage();
        }
    }
}

@JsonClassDescription("Search file contents for a regex pattern using grep. Returns matching lines with file and line number.")
static class GrepFiles {
    @JsonPropertyDescription("The regex pattern to search for")
    public String pattern;
    @JsonPropertyDescription("The directory or file to search in")
    public String path;

    public String execute() {
        try {
            var process = new ProcessBuilder("grep", "-rn", "--include=*.java", pattern, path)
                    .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            return output.length() > MAX_OUTPUT
                    ? output.substring(0, MAX_OUTPUT) + "\n... [truncated at " + MAX_OUTPUT + " chars]"
                    : output.isEmpty() ? "No matches found." : output;
        } catch (Exception e) {
            return "Error running grep: " + e.getMessage();
        }
    }
}

@JsonClassDescription("Find files matching a glob pattern. Returns a list of matching file paths.")
static class GlobFiles {
    @JsonPropertyDescription("The glob pattern, e.g. **/*.java")
    public String pattern;
    @JsonPropertyDescription("The base directory to search from")
    public String path;

    public String execute() {
        try {
            var basePath = Path.of(path);
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            try (var stream = Files.walk(basePath)) {
                var matches = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(basePath.relativize(p)))
                        .map(Path::toString)
                        .collect(Collectors.joining("\n"));
                return matches.isEmpty() ? "No files matched." : matches;
            }
        } catch (IOException e) {
            return "Error searching files: " + e.getMessage();
        }
    }
}
