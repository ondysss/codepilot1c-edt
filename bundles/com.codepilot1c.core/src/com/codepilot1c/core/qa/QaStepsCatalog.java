package com.codepilot1c.core.qa;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

public class QaStepsCatalog {

    private final Set<String> steps;

    private QaStepsCatalog(Set<String> steps) {
        this.steps = steps == null ? Set.of() : Collections.unmodifiableSet(steps);
    }

    public static QaStepsCatalog load(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("Steps catalog not found: " + (file == null ? "<null>" : file.getAbsolutePath()));
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (JsonSyntaxException e) {
            throw new IOException("Invalid steps catalog JSON: " + e.getMessage(), e);
        }
    }

    public static QaStepsCatalog loadFromResource(String resourcePath, ClassLoader classLoader) throws IOException {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IOException("Steps catalog resource not specified"); //$NON-NLS-1$
        }
        ClassLoader loader = classLoader != null ? classLoader : QaStepsCatalog.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Steps catalog resource not found: " + resourcePath); //$NON-NLS-1$
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return parse(reader);
            } catch (JsonSyntaxException e) {
                throw new IOException("Invalid steps catalog JSON: " + e.getMessage(), e); //$NON-NLS-1$
            }
        }
    }

    public static boolean resourceExists(String resourcePath, ClassLoader classLoader) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return false;
        }
        ClassLoader loader = classLoader != null ? classLoader : QaStepsCatalog.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            return stream != null;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean contains(String step) {
        if (step == null) {
            return false;
        }
        return steps.contains(step.trim());
    }

    public Set<String> getSteps() {
        return steps;
    }

    private static QaStepsCatalog parse(Reader reader) {
        JsonElement element = JsonParser.parseReader(reader);
        Set<String> items = new HashSet<>();
        if (element != null && element.isJsonArray()) {
            collectFromArray(element.getAsJsonArray(), items);
        } else if (element != null && element.isJsonObject()) {
            JsonObject root = element.getAsJsonObject();
            if (root.has("steps") && root.get("steps").isJsonArray()) {
                collectFromArray(root.getAsJsonArray("steps"), items);
            }
        }
        return new QaStepsCatalog(items);
    }

    private static void collectFromArray(JsonArray array, Set<String> target) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (element.isJsonPrimitive()) {
                String text = element.getAsString();
                if (text != null && !text.isBlank()) {
                    target.add(normalize(text));
                }
                continue;
            }
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                JsonElement textEl = obj.get("text");
                if (textEl != null && textEl.isJsonPrimitive()) {
                    String text = textEl.getAsString();
                    if (text != null && !text.isBlank()) {
                        target.add(normalize(text));
                    }
                }
            }
        }
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
