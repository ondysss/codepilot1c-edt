package com.codepilot1c.core.qa;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

public final class QaJson {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private QaJson() {
        // Utility class.
    }

    public static JsonObject loadObject(File file) throws IOException {
        if (file == null || !file.exists()) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
            return new JsonObject();
        } catch (JsonSyntaxException e) {
            throw new IOException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    public static void writeObject(File file, JsonObject object) throws IOException {
        if (file == null) {
            throw new IOException("Output file is null");
        }
        if (object == null) {
            object = new JsonObject();
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(object, writer);
        }
    }
}
