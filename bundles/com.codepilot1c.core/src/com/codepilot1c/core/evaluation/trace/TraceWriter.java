package com.codepilot1c.core.evaluation.trace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;

/**
 * Writes run metadata and JSONL events to the canonical trace layout.
 */
public class TraceWriter {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(TraceWriter.class);

    private static final OpenOption[] APPEND_OPTIONS = new OpenOption[] {
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
    };

    private final ArtifactLayout layout;
    private final Gson lineGson;
    private final Gson prettyGson;
    private final Object lock = new Object();

    public TraceWriter(ArtifactLayout layout) {
        this.layout = layout;
        this.lineGson = createGson(false);
        this.prettyGson = createGson(true);
    }

    private Gson createGson(boolean pretty) {
        GsonBuilder builder = new GsonBuilder()
                .disableHtmlEscaping()
                .registerTypeAdapter(Instant.class,
                        (JsonSerializer<Instant>) (src, typeOfSrc, context) -> new JsonPrimitive(src.toString()));
        if (pretty) {
            builder.setPrettyPrinting();
        }
        return builder.create();
    }

    public ArtifactLayout getLayout() {
        return layout;
    }

    public void writeRunMetadata(RunTraceMetadata metadata) {
        if (metadata == null) {
            return;
        }
        writeJson(layout.getRunMetadataFile(), sanitizeValue(metadata), prettyGson, false);
    }

    public void appendEvents(TraceEvent event) {
        appendLine(layout.getEventsFile(), event);
    }

    public void appendLlm(TraceEvent event) {
        appendLine(layout.getLlmFile(), event);
    }

    public void appendTools(TraceEvent event) {
        appendLine(layout.getToolsFile(), event);
    }

    public void appendMcp(TraceEvent event) {
        appendLine(layout.getMcpFile(), event);
    }

    private void appendLine(Path path, TraceEvent event) {
        if (event == null) {
            return;
        }
        writeJson(path, sanitizeValue(event), lineGson, true);
    }

    private void writeJson(Path path, Object payload, Gson gson, boolean append) {
        synchronized (lock) {
            try {
                Files.createDirectories(path.getParent());
                String json = gson.toJson(payload);
                if (append) {
                    Files.writeString(path, json + System.lineSeparator(), StandardCharsets.UTF_8, APPEND_OPTIONS);
                } else {
                    Files.writeString(path, json + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE);
                }
            } catch (IOException e) {
                LOG.error("Failed to write trace file %s", e, path); //$NON-NLS-1$
            }
        }
    }

    private Object sanitizeValue(Object value) {
        if (value != null
                && !(value instanceof String)
                && !(value instanceof Number)
                && !(value instanceof Boolean)
                && !(value instanceof Character)
                && !(value instanceof Map<?, ?>)
                && !(value instanceof Iterable<?>)) {
            return sanitizeValue(lineGson.fromJson(lineGson.toJsonTree(value), new TypeToken<Object>() { }.getType()));
        }
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return LogSanitizer.redactSecrets(text);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                sanitized.put(String.valueOf(entry.getKey()), sanitizeValue(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : iterable) {
                sanitized.add(sanitizeValue(item));
            }
            return sanitized;
        }
        return value;
    }
}
