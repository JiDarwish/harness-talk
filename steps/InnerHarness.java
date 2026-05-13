///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

// InnerHarness.java — shared library for Ollama-based local coding agents.
// Used via //SOURCES InnerHarness.java.

class InnerHarness {

    static final int MAX_OUTPUT = 10_000;
    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    static final String DEFAULT_MODEL = "qwen3:8b";
    static final String DEFAULT_BASE_URL = "http://localhost:11434";

    static String modelName() {
        return System.getenv().getOrDefault("OLLAMA_MODEL", DEFAULT_MODEL);
    }

    static String baseUrl() {
        var value = System.getenv().getOrDefault("OLLAMA_BASE_URL", DEFAULT_BASE_URL);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    static String setupHint() {
        var model = modelName();
        return "Start Ollama and pull the default model:\n"
                + "  ollama serve\n"
                + "  ollama pull " + model;
    }

    // Self-test when run directly
    void main(String[] args) {
        var agent = new Agent(25);
        var task = args.length > 0 ? String.join(" ", args)
                : "Read bookshelf/pom.xml and tell me what project this is.";
        agent.run(task);
    }

    static class ChatMessage {
        public String role;
        public String content;
        public String tool_name;
        public List<OllamaToolCall> tool_calls;

        static ChatMessage system(String content) { return of("system", content); }
        static ChatMessage user(String content) { return of("user", content); }
        static ChatMessage assistant(String content, List<OllamaToolCall> toolCalls) {
            var message = of("assistant", content == null ? "" : content);
            message.tool_calls = toolCalls == null || toolCalls.isEmpty() ? null : toolCalls;
            return message;
        }
        static ChatMessage tool(String toolName, String content) {
            var message = new ChatMessage();
            message.role = "tool";
            message.content = content;
            message.tool_name = toolName;
            return message;
        }
        static ChatMessage of(String role, String content) {
            var message = new ChatMessage();
            message.role = role;
            message.content = content;
            return message;
        }
    }

    static class OllamaFunctionCall {
        public String name;
        public JsonNode arguments;
    }

    static class OllamaToolCall {
        public OllamaFunctionCall function;
        String name() { return function == null ? "" : function.name; }
        JsonNode arguments() {
            return function == null || function.arguments == null ? JSON.createObjectNode() : function.arguments;
        }
        <T> T input(Class<T> type) {
            try { return JSON.treeToValue(arguments(), type); }
            catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid tool arguments for " + name() + ": " + e.getMessage(), e);
            }
        }
    }

    static class ChatResponse {
        public ChatMessage message;
        public String error;
        public Long total_duration;
        public Long load_duration;
        public Long prompt_eval_count;
        public Long prompt_eval_duration;
        public Long eval_count;
        public Long eval_duration;
    }

    record ToolSpec(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> asOllamaTool() {
            return Map.of("type", "function", "function", Map.of(
                    "name", name,
                    "description", description,
                    "parameters", parameters));
        }
    }

    static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    static class OllamaClient {
        final HttpClient httpClient;

        OllamaClient() {
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }

        ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            var body = new LinkedHashMap<String, Object>();
            body.put("model", modelName());
            body.put("stream", false);
            body.put("messages", messages);
            if (tools != null && !tools.isEmpty()) {
                body.put("tools", tools.stream().map(ToolSpec::asOllamaTool).toList());
            }

            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/api/chat"))
                        .timeout(Duration.ofMinutes(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Ollama chat request failed with HTTP "
                            + response.statusCode() + ": " + errorText(response.body()) + "\n\n" + setupHint());
                }

                var chatResponse = JSON.readValue(response.body(), ChatResponse.class);
                if (chatResponse.error != null && !chatResponse.error.isBlank()) {
                    throw new IllegalStateException("Ollama returned an error: " + chatResponse.error
                            + "\n\n" + setupHint());
                }
                if (chatResponse.message == null) {
                    throw new IllegalStateException("Ollama response did not include a message.\n\n" + setupHint());
                }
                return chatResponse;
            } catch (ConnectException e) {
                throw new IllegalStateException("Could not connect to Ollama at " + baseUrl() + ".\n\n" + setupHint(), e);
            } catch (IOException e) {
                if (isConnectException(e)) {
                    throw new IllegalStateException("Could not connect to Ollama at " + baseUrl() + ".\n\n" + setupHint(), e);
                }
                throw new IllegalStateException("Ollama chat request failed: " + e.getMessage()
                        + "\n\n" + setupHint(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Ollama.\n\n" + setupHint(), e);
            }
        }

        static boolean isConnectException(Throwable throwable) {
            while (throwable != null) {
                if (throwable instanceof ConnectException) return true;
                throwable = throwable.getCause();
            }
            return false;
        }

        static String errorText(String body) {
            if (body == null || body.isBlank()) return "<empty response body>";
            try {
                var node = JSON.readTree(body);
                if (node.hasNonNull("error")) return node.get("error").asText();
            } catch (JsonProcessingException ignored) {
                // Return raw body below.
            }
            return body.length() <= 1_000 ? body : body.substring(0, 1_000) + "... [truncated]";
        }
    }

    // --- Agent ---
    static class Agent {
        final OllamaClient client;
        final int maxTurns;
        String systemPrompt;
        final List<ToolSpec> toolSpecs = new ArrayList<>();
        final Map<String, Function<OllamaToolCall, String>> toolExecutors = new HashMap<>();
        final List<ChatMessage> pendingMessages = new ArrayList<>();
        final List<String> traceLog = new ArrayList<>();
        long totalPromptEvalCount = 0;
        long totalEvalCount = 0;
        long totalDurationNanos = 0;
        long totalPromptEvalDurationNanos = 0;
        long totalEvalDurationNanos = 0;

        // Hook: called after write_file, returns additional feedback (or null)
        BiFunction<String, String, String> afterWriteHook = null;

        // Hook: called when agent finishes (no more tool calls), can queue more work
        Function<Agent, Boolean> afterDoneHook = null;

        // Permission: safe bash prefixes
        List<String> safeBashPrefixes = List.of(
                "mvn ", "grep ", "find ", "cat ", "ls ", "head ", "tail ", "wc ", "diff ", "git ");

        Agent(int maxTurns) {
            this.client = new OllamaClient();
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
            registerTool(ReadFile.SPEC, tu -> tu.input(ReadFile.class).execute());
            registerTool(WriteFile.SPEC, tu -> {
                var tool = tu.input(WriteFile.class);
                if (tool.path == null || tool.path.isBlank()) return "Error: path is required.";
                if (tool.content == null) return "Error: content is required.";
                if (!checkPermission("write_file", tool.path)) return "Permission denied by user.";
                var result = tool.execute();
                if (afterWriteHook != null) {
                    var feedback = afterWriteHook.apply(tool.path, tool.content);
                    if (feedback != null) return result + "\n\n--- SENSOR FEEDBACK ---\n" + feedback;
                }
                return result;
            });
            registerTool(RunBash.SPEC, tu -> {
                var tool = tu.input(RunBash.class);
                if (tool.command == null || tool.command.isBlank()) return "Error: command is required.";
                if (!checkPermission("run_bash", tool.command)) return "Permission denied by user.";
                return tool.execute();
            });
            registerTool(GrepFiles.SPEC, tu -> tu.input(GrepFiles.class).execute());
            registerTool(GlobFiles.SPEC, tu -> tu.input(GlobFiles.class).execute());
        }

        void registerTool(ToolSpec toolSpec, Function<OllamaToolCall, String> executor) {
            toolSpecs.add(toolSpec);
            toolExecutors.put(toolSpec.name(), executor);
        }

        void prependSystemPrompt(String extra) {
            systemPrompt = extra + "\n\n" + systemPrompt;
        }

        void addUserMessage(String content) {
            pendingMessages.add(ChatMessage.user(content));
        }

        void run(String task) {
            IO.println("Task: " + task);
            IO.println("Model: " + modelName());
            IO.println();

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system(systemPrompt));
            messages.add(ChatMessage.user(task));

            var turn = 0;
            while (turn < maxTurns) {
                turn++;
                trace("Turn " + turn);
                if (turn == maxTurns - 2) IO.println("[warning] Approaching turn limit (" + maxTurns + ")");
                applyPendingMessages(messages);

                var response = client.chat(messages, toolSpecs);
                recordUsage(response);

                var message = response.message;
                var assistantContent = message.content == null ? "" : message.content;
                if (!assistantContent.isBlank()) IO.println(assistantContent);

                var toolCalls = message.tool_calls == null ? List.<OllamaToolCall>of() : message.tool_calls;
                messages.add(ChatMessage.assistant(assistantContent, toolCalls));

                if (toolCalls.isEmpty()) {
                    if (afterDoneHook != null && afterDoneHook.apply(this)) {
                        trace("afterDoneHook queued more work");
                        continue;
                    }
                    break;
                }

                for (var toolCall : toolCalls) {
                    var toolName = toolCall.name();
                    IO.println("[turn " + turn + "] " + toolName);
                    var result = executeToolCall(toolCall);
                    IO.println("[result] " + truncate(result, 200));
                    messages.add(ChatMessage.tool(toolName, result));
                }
            }

            if (turn >= maxTurns) IO.println("[stopped] Turn limit reached (" + maxTurns + ")");
            IO.println("\n=== Done in " + turn + " turns ===");
            printTraceSummary();
        }

        String complete(String systemPrompt, String userMessage) {
            var messages = List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userMessage));
            var response = client.chat(messages, List.of());
            recordUsage(response);
            return response.message.content == null ? "" : response.message.content;
        }

        void applyPendingMessages(List<ChatMessage> messages) {
            if (pendingMessages.isEmpty()) return;
            messages.addAll(pendingMessages);
            trace("Applied " + pendingMessages.size() + " pending user message(s)");
            pendingMessages.clear();
        }

        String executeToolCall(OllamaToolCall toolCall) {
            var executor = toolExecutors.get(toolCall.name());
            if (executor == null) return "Unknown tool: " + toolCall.name();
            try {
                return executor.apply(toolCall);
            } catch (IllegalArgumentException e) {
                return "Invalid tool arguments: " + e.getMessage();
            } catch (Exception e) {
                return "Tool failed: " + e.getMessage();
            }
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

        void recordUsage(ChatResponse response) {
            if (response.prompt_eval_count != null) totalPromptEvalCount += response.prompt_eval_count;
            if (response.eval_count != null) totalEvalCount += response.eval_count;
            if (response.total_duration != null) totalDurationNanos += response.total_duration;
            if (response.prompt_eval_duration != null) totalPromptEvalDurationNanos += response.prompt_eval_duration;
            if (response.eval_duration != null) totalEvalDurationNanos += response.eval_duration;
            trace("Local usage: +" + valueOrZero(response.prompt_eval_count) + " prompt eval, +"
                    + valueOrZero(response.eval_count) + " eval");
        }

        void trace(String event) {
            traceLog.add("[" + java.time.Instant.now() + "] " + event);
        }

        void printTraceSummary() {
            IO.println("\n=== Trace Summary ===");
            for (var entry : traceLog) IO.println("  " + entry);
            IO.println("  Total prompt eval count: " + totalPromptEvalCount);
            IO.println("  Total eval count:        " + totalEvalCount);
            IO.println("  Total duration:          " + formatDuration(totalDurationNanos));
            IO.println("  Prompt eval duration:    " + formatDuration(totalPromptEvalDurationNanos));
            IO.println("  Eval duration:           " + formatDuration(totalEvalDurationNanos));
            IO.println("  Estimated API cost: $0.0000 (local Ollama)");
        }

        static long valueOrZero(Long value) {
            return value == null ? 0 : value;
        }

        static String formatDuration(long nanos) {
            if (nanos <= 0) return "n/a";
            return String.format("%.3fs", nanos / 1_000_000_000.0);
        }

        static String truncate(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max) + "... [truncated]";
        }
    }

    // --- Tools ---

    static class ReadFile {
        static final ToolSpec SPEC = new ToolSpec(
                "read_file",
                "Read the contents of a file. Always read a file before modifying it.",
                objectSchema(Map.of("path", stringProperty("Absolute or relative file path to read")), List.of("path")));

        public String path;

        public String execute() {
            if (path == null || path.isBlank()) return "Error: path is required.";
            try { return Files.readString(Path.of(path)); }
            catch (IOException e) { return "Error: " + e.getMessage(); }
        }
    }

    static class WriteFile {
        static final ToolSpec SPEC = new ToolSpec(
                "write_file",
                "Write content to a file. Creates parent directories if needed. Always read the file first.",
                objectSchema(Map.of(
                        "path", stringProperty("File path to write to"),
                        "content", stringProperty("The complete file content to write")),
                        List.of("path", "content")));

        public String path;
        public String content;

        public String execute() {
            if (path == null || path.isBlank()) return "Error: path is required.";
            if (content == null) return "Error: content is required.";
            try {
                var p = Path.of(path);
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.writeString(p, content);
                return "Wrote " + content.length() + " chars to " + path;
            } catch (IOException e) { return "Error: " + e.getMessage(); }
        }
    }

    static class RunBash {
        static final ToolSpec SPEC = new ToolSpec(
                "run_bash",
                "Run a bash command. Output is capped at 10k chars.",
                objectSchema(Map.of("command", stringProperty("The bash command to run")), List.of("command")));

        public String command;

        public String execute() {
            if (command == null || command.isBlank()) return "Error: command is required.";
            try {
                var proc = new ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start();
                var out = new String(proc.getInputStream().readAllBytes());
                var code = proc.waitFor();
                var result = out.length() > MAX_OUTPUT ? out.substring(0, MAX_OUTPUT) + "\n...[truncated]" : out;
                return result + "\n[exit code: " + code + "]";
            } catch (Exception e) { return "Error: " + e.getMessage(); }
        }
    }

    static class GrepFiles {
        static final ToolSpec SPEC = new ToolSpec(
                "grep_files",
                "Search file contents with grep. Returns matching lines with file:line prefixes.",
                objectSchema(Map.of(
                        "pattern", stringProperty("Regex pattern to search for"),
                        "path", stringProperty("Directory or file to search in")),
                        List.of("pattern", "path")));

        public String pattern;
        public String path;

        public String execute() {
            if (pattern == null || pattern.isBlank()) return "Error: pattern is required.";
            if (path == null || path.isBlank()) return "Error: path is required.";
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

    static class GlobFiles {
        static final ToolSpec SPEC = new ToolSpec(
                "glob_files",
                "Find files by glob pattern.",
                objectSchema(Map.of(
                        "pattern", stringProperty("Glob pattern, e.g. **/*.java"),
                        "path", stringProperty("Base directory to search from")),
                        List.of("pattern", "path")));

        public String pattern;
        public String path;

        public String execute() {
            if (pattern == null || pattern.isBlank()) return "Error: pattern is required.";
            if (path == null || path.isBlank()) return "Error: path is required.";
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
