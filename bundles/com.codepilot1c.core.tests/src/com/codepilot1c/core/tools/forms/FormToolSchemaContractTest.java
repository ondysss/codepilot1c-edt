package com.codepilot1c.core.tools.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.codepilot1c.core.edt.forms.FormRecipeResult;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException;
import com.codepilot1c.core.edt.forms.HandlerStubReport;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.BmState;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.FailurePhase;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.RollbackStatus;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException.SerializedModelState;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;

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
        assertEquals("[\"project\",\"validation_token\"]", //$NON-NLS-1$
                schema.getAsJsonArray("required").toString()); //$NON-NLS-1$
    }

    @Test
    public void applyFormRecipeResultHasDeterministicStructuredStubOutcome() {
        FormRecipeResult result = new FormRecipeResult(
                "Project", //$NON-NLS-1$
                "Catalog.Products.Form.ItemForm", //$NON-NLS-1$
                1,
                2,
                3,
                4,
                List.of(),
                new HandlerStubReport(
                        List.of("FormOnOpen"), //$NON-NLS-1$
                        List.of("ItemOnChange"))); //$NON-NLS-1$

        JsonObject structured = ApplyFormRecipeTool.toStructuredResult(result);

        assertEquals(8, structured.size());
        assertEquals("Catalog.Products.Form.ItemForm", structured.get("form_fqn").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, structured.getAsJsonArray("handler_stubs_written").size()); //$NON-NLS-1$
        assertEquals(1, structured.getAsJsonArray("handler_stubs_skipped_existing").size()); //$NON-NLS-1$
        assertFalse(structured.get("complete").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void applyFormRecipePartialFailureHasDeterministicStructuredState() {
        FormRecipePartialFailureException failure = new FormRecipePartialFailureException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "Cannot resolve form event", //$NON-NLS-1$
                false,
                "ExternalReport.Report.Form.Main", //$NON-NLS-1$
                true,
                1,
                2,
                3,
                4,
                List.of("FormOnOpen"), //$NON-NLS-1$
                List.of(),
                List.of(),
                FailurePhase.RESOLVE_EVENT,
                "FormOnOpen", //$NON-NLS-1$
                RollbackStatus.NOT_ATTEMPTED,
                BmState.INITIAL_COMMIT_REMAINS,
                SerializedModelState.INITIAL_EXPORT_VERIFIED,
                null);

        JsonObject structured = ApplyFormRecipeTool.toStructuredFailure(failure);

        assertEquals(20, structured.size());
        assertFalse(structured.get("complete").getAsBoolean()); //$NON-NLS-1$
        assertTrue(structured.get("partial").getAsBoolean()); //$NON-NLS-1$
        assertTrue(structured.get("external_project").getAsBoolean()); //$NON-NLS-1$
        assertEquals("RESOLVE_EVENT", structured.get("failure_phase").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("NOT_ATTEMPTED", structured.get("rollback_status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, structured.getAsJsonArray("handler_slots_without_written_stub").size()); //$NON-NLS-1$
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
        assertTrue(text.contains("call_type")); //$NON-NLS-1$
        assertTrue(text.contains("CHANGE_AND_VALIDATE")); //$NON-NLS-1$
        assertTrue(text.contains("Defaults to BEFORE")); //$NON-NLS-1$
    }

    @Test
    public void mutateFormModelSchemaExposesCommandBarOpsAndWarnsAgainstSetFormPropsAndFakeGroups() {
        JsonObject schema = JsonParser.parseString(new MutateFormModelTool().getParameterSchema()).getAsJsonObject();
        String text = schema.toString();

        assertTrue(text.contains("set_auto_command_bar")); //$NON-NLS-1$
        assertTrue(text.contains("set_excluded_commands")); //$NON-NLS-1$
        assertTrue(text.contains("auto_fill")); //$NON-NLS-1$
        assertTrue(text.contains("excluded_commands")); //$NON-NLS-1$
        assertTrue(text.contains("commandBars")); //$NON-NLS-1$
        assertTrue(text.contains("Do NOT use set_form_props/set_item for autoCommandBar/excludedCommands")); //$NON-NLS-1$
        assertTrue(text.contains("do NOT add_group a fake CommandBar")); //$NON-NLS-1$
    }
}
