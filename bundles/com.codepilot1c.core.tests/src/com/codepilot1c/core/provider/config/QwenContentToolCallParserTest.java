package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.ToolCall;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class QwenContentToolCallParserTest {

    @Test
    public void xmlFallbackPreservesJsonArrayParameter() {
        String content = """
                <tool_call>
                <function=render_template>
                <parameter=project>DemoConfiguration</parameter>
                <parameter=sections>[{"name":"Шапка","rows":[["Поступление товаров"]]}]</parameter>
                </function>
                </tool_call>
                """; //$NON-NLS-1$

        List<ToolCall> calls = QwenContentToolCallParser.extractFromContent(content);

        assertEquals(1, calls.size());
        assertEquals("render_template", calls.get(0).getName()); //$NON-NLS-1$
        JsonObject arguments = JsonParser.parseString(calls.get(0).getArguments()).getAsJsonObject();
        assertEquals("DemoConfiguration", arguments.get("project").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(arguments.get("sections").isJsonArray()); //$NON-NLS-1$
        JsonObject section = arguments.getAsJsonArray("sections").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("Шапка", section.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void xmlFallbackKeepsJsonLookingStringParametersAsStrings() {
        String jsonText = "{\"scripts\":{\"test\":\"mvn test\"}}"; //$NON-NLS-1$
        String content = """
                <tool_call>
                <function=write_file>
                <parameter=path>package.json</parameter>
                <parameter=content>{"scripts":{"test":"mvn test"}}</parameter>
                </function>
                </tool_call>
                """; //$NON-NLS-1$

        List<ToolCall> calls = QwenContentToolCallParser.extractFromContent(content);

        assertEquals(1, calls.size());
        assertEquals("write_file", calls.get(0).getName()); //$NON-NLS-1$
        JsonObject arguments = JsonParser.parseString(calls.get(0).getArguments()).getAsJsonObject();
        assertTrue(arguments.get("content").isJsonPrimitive()); //$NON-NLS-1$
        assertEquals(jsonText, arguments.get("content").getAsString()); //$NON-NLS-1$
    }
}
