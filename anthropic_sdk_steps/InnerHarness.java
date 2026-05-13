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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

// InnerHarness.java — shared library for Steps 4-10.
// Extracted from Step 3. Used via //SOURCES InnerHarness.java.
// Note: declared as a proper class (not implicit/unnamed) so nested classes
// are accessible as Agent, ReadFile, etc. from step files via //SOURCES.

class InnerHarness {

    static final int MAX_OUTPUT = 10_000;

    // Self-test when run directly
    void main(String[] args) {
        var agent = new Agent(25);
        var task = args.length > 0 ? String.join(" ", args)
                : "Read bookshelf/pom.xml and tell me what project this is.";
        agent.run(task);
    }

    // --- Agent ---
    static class Agent {
        final AnthropicClient client;
        final int maxTurns;
        String systemPrompt;
        final List<Class<?>> toolClasses = new ArrayList<>();
        final Map<String, Function<BetaToolUseBlock, String>> toolExecutors = new HashMap<>();
        final List<String> traceLog = new ArrayList<>();
        long totalInputTokens = 0;
        long totalOutputTokens = 0;

        // Hook: called after write_file, returns additional feedback (or null)
        BiFunction<String, String, String> afterWriteHook = null;

        // Hook: called when agent finishes (no more tool calls), can inject more work
        Function<MessageCreateParams.Builder, Boolean> afterDoneHook = null;

        // Permission: safe bash prefixes
        List<String> safeBashPrefixes = List.of(
                "mvn ", "grep ", "find ", "cat ", "ls ", "head ", "tail ", "wc ", "diff ", "git ");

        Agent(int maxTurns) {
            this.client = AnthropicOkHttpClient.fromEnv();
            this.maxTurns = maxTurns;
            this.systemPrompt = """
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

            // Register default tools
            registerTool(ReadFile.class, tu -> tu.input(ReadFile.class).execute());
            registerTool(WriteFile.class, tu -> {
                var tool = tu.input(WriteFile.class);
                if (!checkPermission("write_file", tool.path)) return "Permission denied by user.";
                var result = tool.execute();
                // Run afterWriteHook if set
                if (afterWriteHook != null) {
                    var feedback = afterWriteHook.apply(tool.path, tool.content);
                    if (feedback != null) return result + "\n\n--- SENSOR FEEDBACK ---\n" + feedback;
                }
                return result;
            });
            registerTool(RunBash.class, tu -> {
                var tool = tu.input(RunBash.class);
                if (!checkPermission("run_bash", tool.command)) return "Permission denied by user.";
                return tool.execute();
            });
            registerTool(GrepFiles.class, tu -> tu.input(GrepFiles.class).execute());
            registerTool(GlobFiles.class, tu -> tu.input(GlobFiles.class).execute());
        }

        void registerTool(Class<?> toolClass, Function<BetaToolUseBlock, String> executor) {
            toolClasses.add(toolClass);
            // Convert class name to snake_case for dispatch
            var name = toSnakeCase(toolClass.getSimpleName());
            toolExecutors.put(name, executor);
        }

        void prependSystemPrompt(String extra) {
            systemPrompt = extra + "\n\n" + systemPrompt;
        }

        void run(String task) {
            IO.println("Task: " + task);
            IO.println();

            var paramsBuilder = MessageCreateParams.builder()
                    .model(Model.CLAUDE_HAIKU_4_5)
                    .maxTokens(8192)
                    .system(systemPrompt);

            for (var toolClass : toolClasses) {
                paramsBuilder.addTool(toolClass);
            }
            paramsBuilder.addUserMessage(task);

            var turn = 0;
            while (turn < maxTurns) {
                turn++;
                trace("Turn " + turn);
                if (turn == maxTurns - 2) IO.println("[warning] Approaching turn limit (" + maxTurns + ")");

                var message = client.beta().messages().create(paramsBuilder.build());

                // Track tokens — usage() returns BetaUsage directly (not Optional)
                try {
                    var usage = message.usage();
                    totalInputTokens += usage.inputTokens();
                    totalOutputTokens += usage.outputTokens();
                    trace("Tokens: +" + usage.inputTokens() + " in, +" + usage.outputTokens() + " out");
                } catch (Exception e) { /* usage may not be available */ }

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

                if (toolUseBlocks.isEmpty()) {
                    // Agent is done — check afterDoneHook
                    if (afterDoneHook != null && afterDoneHook.apply(paramsBuilder)) {
                        trace("afterDoneHook injected more work");
                        continue; // Hook added messages, continue the loop
                    }
                    break;
                }

                paramsBuilder.addAssistantMessageOfBetaContentBlockParams(assistantBlocks);

                var toolResults = new ArrayList<BetaContentBlockParam>();
                for (var toolUse : toolUseBlocks) {
                    var executor = toolExecutors.get(toolUse.name());
                    var result = executor != null ? executor.apply(toolUse) : "Unknown tool: " + toolUse.name();
                    IO.println("[result] " + truncate(result, 200));
                    toolResults.add(BetaContentBlockParam.ofToolResult(
                            BetaToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .contentAsJson(Map.of("output", result))
                                    .build()));
                }
                paramsBuilder.addUserMessageOfBetaContentBlockParams(toolResults);
            }

            if (turn >= maxTurns) IO.println("[stopped] Turn limit reached (" + maxTurns + ")");
            IO.println("\n=== Done in " + turn + " turns ===");
            printTraceSummary();
        }

        boolean checkPermission(String tool, String detail) {
            if ("run_bash".equals(tool)) {
                for (var prefix : safeBashPrefixes) {
                    if (detail.startsWith(prefix)) return true;
                }
            }
            IO.println("[permission] " + tool + ": " + detail);
            IO.println("  Allow? (y/n): ");
            var response = IO.readln().trim().toLowerCase();
            var allowed = response.startsWith("y");
            trace((allowed ? "ALLOWED" : "DENIED") + ": " + tool + " — " + truncate(detail, 80));
            return allowed;
        }

        void trace(String event) {
            traceLog.add("[" + java.time.Instant.now() + "] " + event);
        }

        void printTraceSummary() {
            IO.println("\n=== Trace Summary ===");
            for (var entry : traceLog) IO.println("  " + entry);
            IO.println("  Total input tokens:  " + totalInputTokens);
            IO.println("  Total output tokens: " + totalOutputTokens);
            var cost = (totalInputTokens * 3.0 + totalOutputTokens * 15.0) / 1_000_000;
            IO.println("  Estimated cost:      $" + String.format("%.4f", cost));
        }

        static String truncate(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max) + "... [truncated]";
        }

        static String toSnakeCase(String camelCase) {
            var sb = new StringBuilder();
            for (int i = 0; i < camelCase.length(); i++) {
                var c = camelCase.charAt(i);
                if (i > 0 && Character.isUpperCase(c)) {
                    var prev = camelCase.charAt(i - 1);
                    if (Character.isLowerCase(prev)) sb.append('_');
                    else if (i + 1 < camelCase.length() && Character.isLowerCase(camelCase.charAt(i + 1)))
                        sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            }
            return sb.toString();
        }
    }

    // --- Tools ---

    @JsonClassDescription("Read the contents of a file. Always read a file before modifying it.")
    static class ReadFile {
        @JsonPropertyDescription("Absolute or relative file path to read")
        public String path;

        public String execute() {
            try { return Files.readString(Path.of(path)); }
            catch (IOException e) { return "Error: " + e.getMessage(); }
        }
    }

    @JsonClassDescription("Write content to a file. Creates parent directories if needed. Always read the file first.")
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

    @JsonClassDescription("Run a bash command. Output is capped at 10k chars.")
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

    @JsonClassDescription("Search file contents with grep. Returns matching lines with file:line prefixes.")
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

    @JsonClassDescription("Find files by glob pattern.")
    static class GlobFiles {
        @JsonPropertyDescription("Glob pattern, e.g. **/*.java")
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
}
