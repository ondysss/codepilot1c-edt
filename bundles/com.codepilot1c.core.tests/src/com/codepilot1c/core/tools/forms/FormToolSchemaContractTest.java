package com.codepilot1c.core.tools.forms;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class FormToolSchemaContractTest {

    @Test
    public void applyFormRecipeSchemaGuidesAttributeCreationBeforeBindingFields() {
        JsonObject schema = JsonParser.parseString(new ApplyFormRecipeTool().getParameterSchema()).getAsJsonObject();
        String text = schema.toString();

        assertTrue(text.contains("Form attributes are data-bearing form реквизиты")); //$NON-NLS-1$
        assertTrue(text.contains("create/update/upsert/remove")); //$NON-NLS-1$
        assertTrue(text.contains("data_path")); //$NON-NLS-1$
        assertTrue(text.contains("inspect_form_layout")); //$NON-NLS-1$
        assertTrue(text.contains("validation_token")); //$NON-NLS-1$
    }

    @Test
    public void mutateFormModelSchemaSeparatesVisualItemsFromFormAttributes() {
        JsonObject schema = JsonParser.parseString(new MutateFormModelTool().getParameterSchema()).getAsJsonObject();
        String text = schema.toString();

        assertTrue(text.contains("Visual form items only")); //$NON-NLS-1$
        assertTrue(text.contains("data_path must reference an existing form attribute")); //$NON-NLS-1$
        assertTrue(text.contains("inspect_form_layout")); //$NON-NLS-1$
        assertTrue(text.contains("Do not guess SpreadsheetDocument")); //$NON-NLS-1$
        assertTrue(text.contains("\"required\":[\"project\",\"form_fqn\",\"operations\",\"validation_token\"]")); //$NON-NLS-1$
    }

    @Test
    public void mutateFormModelSchemaExposesEventHandlerOps() {
        JsonObject schema = JsonParser.parseString(new MutateFormModelTool().getParameterSchema()).getAsJsonObject();
        String text = schema.toString();

        assertTrue(text.contains("add_event_handler")); //$NON-NLS-1$
        assertTrue(text.contains("set_event_handler")); //$NON-NLS-1$
        assertTrue(text.contains("remove_event_handler")); //$NON-NLS-1$
        assertTrue(text.contains("handler_name")); //$NON-NLS-1$
        assertFalse(text.contains("call_type")); //$NON-NLS-1$
    }
}
