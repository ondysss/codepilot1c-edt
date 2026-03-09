package com.codepilot1c.core.evaluation.trace.tests;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Before;

import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.codepilot1c.core.evaluation.trace.ArtifactLayout;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public abstract class AbstractTraceTest {

    private String previousTraceDir;
    private String previousTraceEnabled;

    protected Path traceRoot;

    @Before
    public void setUpTraceProperties() throws IOException {
        previousTraceDir = System.getProperty(ArtifactLayout.PROP_TRACE_DIR);
        previousTraceEnabled = System.getProperty(AgentTraceSession.PROP_TRACE_ENABLED);
        traceRoot = Files.createTempDirectory("codepilot1c-trace-tests-");
        System.setProperty(ArtifactLayout.PROP_TRACE_DIR, traceRoot.toString());
        System.setProperty(AgentTraceSession.PROP_TRACE_ENABLED, Boolean.TRUE.toString());
    }

    @After
    public void restoreTraceProperties() {
        restoreProperty(ArtifactLayout.PROP_TRACE_DIR, previousTraceDir);
        restoreProperty(AgentTraceSession.PROP_TRACE_ENABLED, previousTraceEnabled);
    }

    protected JsonObject readJson(Path file) throws IOException {
        assertTrue("Expected file to exist: " + file, Files.exists(file));
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    protected List<JsonObject> readJsonLines(Path file) throws IOException {
        assertTrue("Expected file to exist: " + file, Files.exists(file));
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> JsonParser.parseString(line).getAsJsonObject())
                .toList();
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
