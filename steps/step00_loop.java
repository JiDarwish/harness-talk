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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

static final String DEFAULT_MODEL = "qwen3:8b";
static final String DEFAULT_BASE_URL = "http://localhost:11434";

// Step 0: The minimal agent loop -- one local Ollama model, one tool.

void main(String[] args) {
    var task = args.length > 0 ? String.join(" ", args) : "Read the file bookshelf/pom.xml and tell me what project this is.";

    IO.println("=== Step 0: Minimal Agent Loop (Ollama) ===");
    IO.println("Model: " + modelName());
    IO.println("Task: " + task);
    IO.println();

    var messages = new ArrayList<ChatMessage>();
    messages.add(ChatMessage.user(task));
    var tools = List.of(readFileTool());

    while (true) {
        var response = chat(messages, tools);
        var assistant = response.message;
        if (assistant == null) {
            throw new IllegalStateException("Ollama response did not include a message.");
        }

        if (assistant.content != null && !assistant.content.isBlank()) {
            IO.println(assistant.content);
        }

        messages.add(ChatMessage.assistant(assistant.content, assistant.tool_calls));

        if (assistant.tool_calls == null || assistant.tool_calls.isEmpty()) {
            break;
        }

        for (var toolCall : assistant.tool_calls) {
            IO.println("[tool] " + toolCall.name());
            var result = executeTool(toolCall);
            IO.println("[result] " + preview(result));
            messages.add(ChatMessage.tool(toolCall.name(), result));
        }
    }
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

Map<String, Object> readFileTool() {
    return Map.of(
            "type", "function",
            "function", Map.of(
                    "name", "read_file",
                    "description", "Read the contents of a file at the given path",
                    "parameters", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "path", Map.of(
                                            "type", "string",
                                            "description", "The file path to read")),
                            "required", List.of("path"))));
}

String executeTool(OllamaToolCall toolCall) {
    return switch (toolCall.name()) {
        case "read_file" -> toolCall.input(ReadFile.class).execute();
        default -> "Unknown tool: " + toolCall.name();
    };
}

String preview(String result) {
    return result.substring(0, Math.min(200, result.length())) + (result.length() > 200 ? "..." : "");
}

static class ChatMessage {
    public String role;
    public String content;
    public String tool_name;
    public List<OllamaToolCall> tool_calls;

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

// --- Tool: read_file ---

static class ReadFile {
    public String path;

    public String execute() {
        if (path == null || path.isBlank()) {
            return "Error: path is required.";
        }
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }
}
