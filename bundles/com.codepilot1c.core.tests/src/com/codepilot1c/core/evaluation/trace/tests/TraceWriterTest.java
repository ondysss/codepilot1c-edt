package com.codepilot1c.core.evaluation.trace.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.evaluation.trace.ArtifactLayout;
import com.codepilot1c.core.evaluation.trace.RunTraceMetadata;
import com.codepilot1c.core.evaluation.trace.TraceEvent;
import com.codepilot1c.core.evaluation.trace.TraceEventType;
import com.codepilot1c.core.evaluation.trace.TraceWriter;
import com.google.gson.JsonObject;

public class TraceWriterTest extends AbstractTraceTest {

    @Test
    public void writesCanonicalLayoutAndJsonlChannels() throws Exception {
        ArtifactLayout layout = ArtifactLayout.create("run-test-1");
        TraceWriter writer = new TraceWriter(layout);

        RunTraceMetadata metadata = new RunTraceMetadata("run-test-1", "session-test-1",
                "agent_run", Instant.parse("2026-03-06T12:00:00Z"));
        metadata.setStatus("COMPLETED");
        metadata.putAttribute("secret", "sk-test-value");
        writer.writeRunMetadata(metadata);

        writer.appendEvents(new TraceEvent("evt-1", null, "run-test-1", "session-test-1", "events",
                TraceEventType.AGENT_STARTED, Instant.parse("2026-03-06T12:00:01Z"),
                Map.of("step", Integer.valueOf(1))));
        writer.appendLlm(new TraceEvent("llm-1", "evt-1", "run-test-1", "session-test-1", "llm",
                TraceEventType.LLM_REQUEST, Instant.parse("2026-03-06T12:00:02Z"),
                Map.of("model", "gpt-test")));
        writer.appendTools(new TraceEvent("tool-1", "evt-1", "run-test-1", "session-test-1", "tools",
                TraceEventType.TOOL_CALL, Instant.parse("2026-03-06T12:00:03Z"),
                Map.of("tool_name", "demo_tool")));
        writer.appendMcp(new TraceEvent("mcp-1", null, "run-test-1", "session-test-1", "mcp",
                TraceEventType.MCP_REQUEST, Instant.parse("2026-03-06T12:00:04Z"),
                Map.of("method", "initialize")));

        JsonObject runJson = readJson(layout.getRunMetadataFile());
        assertEquals("run-test-1", runJson.get("runId").getAsString());
        assertEquals("COMPLETED", runJson.get("status").getAsString());
        assertEquals("sk-test-value",
                runJson.getAsJsonObject("attributes").get("secret").getAsString());

        List<JsonObject> agentEvents = readJsonLines(layout.getEventsFile());
        assertEquals(1, agentEvents.size());
        assertEquals("AGENT_STARTED", agentEvents.get(0).get("type").getAsString());

        List<JsonObject> llmEvents = readJsonLines(layout.getLlmFile());
        assertEquals("LLM_REQUEST", llmEvents.get(0).get("type").getAsString());

        List<JsonObject> toolEvents = readJsonLines(layout.getToolsFile());
        assertEquals("TOOL_CALL", toolEvents.get(0).get("type").getAsString());

        List<JsonObject> mcpEvents = readJsonLines(layout.getMcpFile());
        assertEquals("MCP_REQUEST", mcpEvents.get(0).get("type").getAsString());

        assertTrue(Files.isDirectory(layout.getArtifactsDirectory()));
    }
}
