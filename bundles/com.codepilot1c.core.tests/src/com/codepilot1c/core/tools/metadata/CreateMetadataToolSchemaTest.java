package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringReader;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * The {@code create_metadata} schema must parse, or MCP clients lose every argument of the tool.
 *
 * <p>Inside a Java text block {@code \"} is a Java escape and yields a bare quote. Written that way, the schedule
 * example in the {@code properties} description ended the JSON string, and the host published
 * {@code "Schema parse error: ... Unterminated object at line 76 column 193 path
 * $.properties.properties.description"} instead of the schema. A JSON quote inside a text block is written
 * {@code \\"}.
 */
public class CreateMetadataToolSchemaTest {

    @Test
    public void schemaParsesAndPublishesTopLevelArguments() throws IOException {
        JsonObject properties = schema().getAsJsonObject("properties"); //$NON-NLS-1$
        for (String argument : new String[] { "project", "kind", "name", "synonym", "comment", "properties", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "validation_token" }) { //$NON-NLS-1$
            assertTrue("create_metadata schema does not publish " + argument, properties.has(argument)); //$NON-NLS-1$
        }
    }

    @Test
    public void propertiesDescriptionKeepsScheduleExampleAsJson() throws IOException {
        String description = schema().getAsJsonObject("properties").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
                .get("description").getAsString(); //$NON-NLS-1$
        assertTrue(description, description.contains("{\"repeatPeriodInDay\":300,\"daysRepeatPeriod\":1}")); //$NON-NLS-1$
    }

    private static JsonObject schema() throws IOException {
        // Strict reader: JsonParser.parseString would switch to lenient mode.
        JsonReader reader = new JsonReader(new StringReader(new CreateMetadataTool().getParameterSchema()));
        reader.setLenient(false);
        JsonElement root = new Gson().getAdapter(JsonElement.class).read(reader);
        assertEquals(JsonToken.END_DOCUMENT, reader.peek());
        return root.getAsJsonObject();
    }
}
