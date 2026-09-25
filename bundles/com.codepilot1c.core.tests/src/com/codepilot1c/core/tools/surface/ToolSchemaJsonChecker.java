package com.codepilot1c.core.tools.surface;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;

/**
 * Strict reader for tool parameter schemas, shared by the schema contract tests.
 *
 * <p>No runtime layer reports a broken schema. The MCP host parses {@code inputSchema} with lenient
 * {@code JsonParser.parseString} and, when that throws, publishes
 * {@code {"type":"object","description":"Schema parse error: ..."}}: the tool stays listed, but clients lose every
 * argument. {@code ToolSurfaceSchemaNormalizer} returns the raw text on the same failure. The checker is stricter
 * than the host on purpose:
 * <ul>
 * <li>RFC 8259 syntax only: lenient Gson reads {@code ["a",]} as {@code ["a",null]} and a bare word as a string, so a
 * typo can survive as a different schema;</li>
 * <li>the whole text is consumed;</li>
 * <li>member names are unique within an object: Gson silently keeps the last duplicate;</li>
 * <li>the root is an object with {@code "type": "object"}, as MCP requires for {@code inputSchema}.</li>
 * </ul>
 */
public final class ToolSchemaJsonChecker {

    private ToolSchemaJsonChecker() {
    }

    /**
     * Checks one parameter schema.
     *
     * @param schema schema text as a tool or a tool definition returns it
     * @return {@code null} when the schema is acceptable, otherwise the reason; a blank schema is acceptable because
     *         the host substitutes an empty object schema for it
     */
    public static String violation(String schema) {
        if (schema == null || schema.isBlank()) {
            return null;
        }
        JsonElement root;
        try {
            root = parse(schema);
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            return e.getMessage();
        }
        if (!root.isJsonObject()) {
            return "root is not a JSON object"; //$NON-NLS-1$
        }
        JsonElement type = root.getAsJsonObject().get("type"); //$NON-NLS-1$
        if (type == null || !type.isJsonPrimitive() || !"object".equals(type.getAsString())) { //$NON-NLS-1$
            return "root \"type\" must be \"object\", got " + type; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Parses a schema strictly.
     *
     * @param json schema text
     * @return the parsed schema
     * @throws IOException when the text is not strict JSON, has trailing content or repeats a member name
     */
    public static JsonElement parse(String json) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(false);
        JsonElement root = read(reader);
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new MalformedJsonException("Trailing content at " + reader.getPath()); //$NON-NLS-1$
        }
        return root;
    }

    private static JsonElement read(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) {
                        throw new MalformedJsonException("Duplicate member name at " + reader.getPath()); //$NON-NLS-1$
                    }
                    object.add(name, read(reader));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(read(reader));
                }
                reader.endArray();
                return array;
            }
            case STRING -> {
                return new JsonPrimitive(reader.nextString());
            }
            case NUMBER -> {
                return new JsonPrimitive(new BigDecimal(reader.nextString()));
            }
            case BOOLEAN -> {
                return new JsonPrimitive(reader.nextBoolean());
            }
            case NULL -> {
                reader.nextNull();
                return JsonNull.INSTANCE;
            }
            default -> throw new MalformedJsonException("Unexpected " + token + " at " + reader.getPath()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
