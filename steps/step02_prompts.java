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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

static final String DEFAULT_MODEL = "qwen3:8b";
static final String DEFAULT_BASE_URL = "http://localhost:11434";
static final int MAX_OUTPUT = 10_000;
static final int MAX_TURNS = 25;

// Step 2: System prompt + turn limit.
// The agent gets instructions. Behaviour improves visibly.

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
    var task = args.length > 0 ? String.join(" ", args)
            : "Find any failing tests in bookshelf/ and fix them.";

    IO.println("=== Step 2: System Prompt + Turn Limit ===");
    IO.println("Model: " + modelName());
    IO.println("Task: " + task);
    IO.println();

    var messages = new ArrayList<ChatMessage>();
    messages.add(ChatMessage.system(SYSTEM_PROMPT));
    messages.add(ChatMessage.user(task));
    var tools = defaultTools();

    var turn = 0;
    while (turn < MAX_TURNS) {
        turn++;
        if (turn == MAX_TURNS - 2) IO.println("[warning] Approaching turn limit (" + MAX_TURNS + ")");

        var response = chat(messages, tools);
        var assistant = response.message;
        if (assistant == null) {
            throw new IllegalStateException("Ollama response did not include a message." + setupHint());
        }

        if (assistant.content != null && !assistant.content.isBlank()) {
            IO.println(assistant.content);
        }

        messages.add(ChatMessage.assistant(assistant.content, assistant.tool_calls));

        if (assistant.tool_calls == null || assistant.tool_calls.isEmpty()) {
            break;
        }

        final var currentTurn = turn;
        for (var toolCall : assistant.tool_calls) {
            IO.println("[turn " + currentTurn + "] " + toolCall.name());
            var result = executeTool(toolCall);
            IO.println("[result] " + truncate(result, 200));
            messages.add(ChatMessage.tool(toolCall.name(), result));
        }
    }

    if (turn >= MAX_TURNS) IO.println("[stopped] Turn limit reached (" + MAX_TURNS + ")");
    IO.println("\n=== Done in " + turn + " turns ===");
}

String modelName() {
    var value = System.getenv("OLLAMA_MODEL");
    return value == null || value.isBlank() ? DEFAULT_MODEL : value.trim();
}

String baseUrl() {
    var value = System.getenv("OLLAMA_BASE_URL");
    var url = value == null || value.isBlank() ? DEFAULT_BASE_URL : value.trim();
    while (url.endsWith("/")) {
        url = url.substring(0, url.length() - 1);
    }
    return url;
}

String setupHint() {
    return """

Ollama setup:
  ollama serve
  ollama pull %s
""".formatted(modelName());
}

ChatResponse chat(List<ChatMessage> messages, List<Map<String, Object>> tools) {
    var body = new LinkedHashMap<String, Object>();
    body.put("model", modelName());
    body.put("stream", false);
    body.put("messages", messages);
    body.put("tools", tools);

    try {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/chat"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();

        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Ollama chat failed with HTTP " + response.statusCode()
                    + ": " + response.body() + setupHint());
        }

        var chatResponse = JSON.readValue(response.body(), ChatResponse.class);
        if (chatResponse.error != null && !chatResponse.error.isBlank()) {
            throw new IllegalStateException("Ollama chat failed: " + chatResponse.error + setupHint());
        }
        return chatResponse;
    } catch (ConnectException e) {
        throw new IllegalStateException("Could not connect to Ollama at " + baseUrl() + "." + setupHint(), e);
    } catch (IOException e) {
        throw new IllegalStateException("Could not call Ollama at " + baseUrl() + ": " + e.getMessage() + setupHint(), e);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while calling Ollama.", e);
    }
}

Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
    var schema = new LinkedHashMap<String, Object>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", required);
    return schema;
}

Map<String, Object> stringProperty(String description) {
    return Map.of("type", "string", "description", description);
}

Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
    return Map.of(
            "type", "function",
            "function", Map.of(
                    "name", name,
                    "description", description,
                    "parameters", parameters));
}

List<Map<String, Object>> defaultTools() {
    return List.of(
            tool("read_file", "Read the contents of a file. Always read a file before modifying it.",
                    objectSchema(Map.of("path", stringProperty("Absolute or relative file path to read")), List.of("path"))),
            tool("write_file", "Write content to a file. Creates parent directories if needed. Always read the file first to understand existing content.",
                    objectSchema(Map.of(
                            "path", stringProperty("File path to write to"),
                            "content", stringProperty("The complete file content to write")),
                            List.of("path", "content"))),
            tool("run_bash", "Run a bash command. Use for: mvn test, mvn compile, git diff, etc. Output is capped at 10k chars.",
                    objectSchema(Map.of("command", stringProperty("The bash command to run")), List.of("command"))),
            tool("grep_files", "Search file contents with grep. Returns matching lines with file:line prefixes. Use to find usages, patterns, or definitions.",
                    objectSchema(Map.of(
                            "pattern", stringProperty("Regex pattern to search for"),
                            "path", stringProperty("Directory or file to search in")),
                            List.of("pattern", "path"))),
            tool("glob_files", "Find files by glob pattern. Use to discover project structure, find test files, etc.",
                    objectSchema(Map.of(
                            "pattern", stringProperty("Glob pattern, e.g. **/*.java or src/**/Test*.java"),
                            "path", stringProperty("Base directory to search from")),
                            List.of("pattern", "path"))));
}

String executeTool(OllamaToolCall toolCall) {
    return switch (toolCall.name()) {
        case "read_file" -> toolCall.input(ReadFile.class).execute();
        case "write_file" -> toolCall.input(WriteFile.class).execute();
        case "run_bash" -> toolCall.input(RunBash.class).execute();
        case "grep_files" -> toolCall.input(GrepFiles.class).execute();
        case "glob_files" -> toolCall.input(GlobFiles.class).execute();
        default -> "Unknown tool: " + toolCall.name();
    };
}

String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "... [truncated]";
}

boolean missing(String value) {
    return value == null || value.isBlank();
}

static class ChatMessage {
    public String role;
    public String content;
    public String tool_name;
    public List<OllamaToolCall> tool_calls;

    static ChatMessage system(String content) {
        var message = new ChatMessage();
        message.role = "system";
        message.content = content;
        return message;
    }

    static ChatMessage user(String content) {
        var message = new ChatMessage();
        message.role = "user";
        message.content = content;
        return message;
    }

    static ChatMessage assistant(String content, List<OllamaToolCall> toolCalls) {
        var message = new ChatMessage();
        message.role = "assistant";
        message.content = content;
        message.tool_calls = toolCalls;
        return message;
    }

    static ChatMessage tool(String toolName, String content) {
        var message = new ChatMessage();
        message.role = "tool";
        message.tool_name = toolName;
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

    String name() {
        return function == null ? "" : function.name;
    }

    <T> T input(Class<T> type) {
        try {
            if (function == null || function.arguments == null || function.arguments.isNull()) {
                return type.getDeclaredConstructor().newInstance();
            }
            if (function.arguments.isTextual()) {
                return JSON.readValue(function.arguments.asText(), type);
            }
            return JSON.treeToValue(function.arguments, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid tool arguments for " + name() + ": " + e.getMessage(), e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Could not create tool input for " + name(), e);
        }
    }
}

static class ChatResponse {
    public ChatMessage message;
    public String error;
}

// --- Tools (same as Step 1, with richer descriptions) ---

static class ReadFile {
    public String path;

    public String execute() {
        if (path == null || path.isBlank()) return "Error: path is required.";
        try { return Files.readString(Path.of(path)); }
        catch (IOException e) { return "Error: " + e.getMessage(); }
    }
}

static class WriteFile {
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
