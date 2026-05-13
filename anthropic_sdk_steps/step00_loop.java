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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Step 0: The minimal agent loop — one tool, ~30 lines of loop logic.
// "The agent loop is small. Watch it appear."

void main(String[] args) {
    var client = AnthropicOkHttpClient.fromEnv();
    var task = args.length > 0 ? String.join(" ", args) : "Read the file bookshelf/pom.xml and tell me what project this is.";

    IO.println("=== Step 0: Minimal Agent Loop ===");
    IO.println("Task: " + task);
    IO.println();

    var paramsBuilder = MessageCreateParams.builder()
            .model(Model.CLAUDE_HAIKU_4_5)
            .maxTokens(4096)
            .addTool(ReadFile.class)
            .addUserMessage(task);

    // The agent loop — this is it.
    while (true) {
        var message = client.beta().messages().create(paramsBuilder.build());

        // Collect tool uses, build assistant replay
        var toolUseBlocks = new ArrayList<BetaToolUseBlock>();
        var assistantBlocks = new ArrayList<BetaContentBlockParam>();

        for (var block : message.content()) {
            block.text().ifPresent(textBlock -> {
                IO.println(textBlock.text());
                assistantBlocks.add(BetaContentBlockParam.ofText(
                        BetaTextBlockParam.builder().text(textBlock.text()).build()));
            });
            block.toolUse().ifPresent(toolUse -> {
                IO.println("[tool] " + toolUse.name() + "(" + toolUse.id() + ")");
                toolUseBlocks.add(toolUse);
                assistantBlocks.add(BetaContentBlockParam.ofToolUse(
                        BetaToolUseBlockParam.builder()
                                .name(toolUse.name())
                                .id(toolUse.id())
                                .input(toolUse._input())
                                .build()));
            });
        }

        // No tool calls? We're done.
        if (toolUseBlocks.isEmpty()) break;

        // Add assistant message to conversation
        paramsBuilder.addAssistantMessageOfBetaContentBlockParams(assistantBlocks);

        // Execute tools and add results
        var toolResults = new ArrayList<BetaContentBlockParam>();
        for (var toolUse : toolUseBlocks) {
            var result = executeTool(toolUse);
            IO.println("[result] " + result.substring(0, Math.min(200, result.length())) + (result.length() > 200 ? "..." : ""));
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
        default -> "Unknown tool: " + toolUse.name();
    };
}

// --- Tool: read_file ---

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
