package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.edt.validation.ValidationRequest;
import com.codepilot1c.core.edt.validation.ValidationResult;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class EdtValidateRequestToolTest {

    @Test
    public void acceptsCompositeOperationAndPreservesRequestedOperationName() {
        StubValidationService validationService = new StubValidationService();
        EdtValidateRequestTool tool = new EdtValidateRequestTool(validationService);

        ToolResult result = tool.execute(Map.of(
                "project", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "external_manage", //$NON-NLS-1$ //$NON-NLS-2$
                "payload", Map.of(
                        "command", "create_report", //$NON-NLS-1$ //$NON-NLS-2$
                        "project", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                        "external_project", "ExtReports", //$NON-NLS-1$ //$NON-NLS-2$
                        "name", "SalesReport" //$NON-NLS-1$
                ))).join();

        assertTrue(result.isSuccess());
        assertEquals(ValidationOperation.EXTERNAL_CREATE_REPORT, validationService.lastRequest.operation());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("external_manage", json.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("token-1", json.get("validationToken").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void acceptsExtensionManageAdoptPayloadAndKeepsCompositeOperationName() {
        StubValidationService validationService = new StubValidationService();
        EdtValidateRequestTool tool = new EdtValidateRequestTool(validationService);

        ToolResult result = tool.execute(Map.of(
                "project", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "extension_manage", //$NON-NLS-1$ //$NON-NLS-2$
                "payload", Map.of(
                        "command", "adopt", //$NON-NLS-1$ //$NON-NLS-2$
                        "project", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                        "base_project", "DemoConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
                        "extension_project", "ExtensionDemo", //$NON-NLS-1$ //$NON-NLS-2$
                        "source_object_fqn", "Catalog.Items" //$NON-NLS-1$ //$NON-NLS-2$
                ))).join();

        assertTrue(result.isSuccess());
        assertEquals(ValidationOperation.EXTENSION_ADOPT_OBJECT, validationService.lastRequest.operation());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("extension_manage", json.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("token-1", json.get("validationToken").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createMetadataValidationReportsEffectiveExtensionName() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        Map<String, Object> payload = service.normalizeCreatePayload(
                "ДО.Артель", //$NON-NLS-1$
                "Bot", //$NON-NLS-1$
                "аи_МастерБотАртель", //$NON-NLS-1$
                null,
                null,
                Map.of());

        assertEquals("ар_аи_МастерБотАртель", payload.get("effectiveName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Bot.ар_аи_МастерБотАртель", payload.get("effectiveFqn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, payload.get("autoPrefixed")); //$NON-NLS-1$
    }

    @Test
    public void createMetadataValidationRejectsAutoPrefixWhenDisabled() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        try {
            service.normalizeCreatePayload(
                    "ДО.Артель", //$NON-NLS-1$
                    "Bot", //$NON-NLS-1$
                    "аи_МастерБотАртель", //$NON-NLS-1$
                    null,
                    null,
                    Map.of("allow_auto_prefix", Boolean.FALSE)); //$NON-NLS-1$
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("auto-prefix")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("ар_аи_МастерБотАртель")); //$NON-NLS-1$
            return;
        }
        assertFalse("allow_auto_prefix=false must reject an unprefixed extension name", true); //$NON-NLS-1$
    }

    @Test
    public void createMetadataValidationRejectsTopLevelAutoPrefixWhenDisabled() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        try {
            service.normalizeCreatePayload(
                    "ДО.Артель", //$NON-NLS-1$
                    "Bot", //$NON-NLS-1$
                    "аи_МастерБотАртель", //$NON-NLS-1$
                    null,
                    null,
                    Map.of(),
                    Boolean.FALSE);
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("auto-prefix")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("ар_аи_МастерБотАртель")); //$NON-NLS-1$
            return;
        }
        assertFalse("top-level allow_auto_prefix=false must reject an unprefixed extension name", true); //$NON-NLS-1$
    }

    @Test
    public void updateMetadataValidationNormalizesStandardCommandGroupFullName() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        Map<String, Object> payload = service.normalizeUpdatePayload(
                "ДО.Артель", //$NON-NLS-1$
                "CommonCommand.аи_ОтправитьНаАнализИИ", //$NON-NLS-1$
                Map.of("set", Map.of("group", "StandardCommandGroup.FormCommandBarImportant"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        @SuppressWarnings("unchecked")
        Map<String, Object> changes = (Map<String, Object>) payload.get("changes"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> set = (Map<String, Object>) changes.get("set"); //$NON-NLS-1$
        assertEquals("FormCommandBarImportant", set.get("group")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void updateMetadataValidationPreservesCustomCommandGroupNames() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        Map<String, Object> payload = service.normalizeUpdatePayload(
                "ДО.Артель", //$NON-NLS-1$
                "CommonCommand.аи_ОтправитьНаАнализИИ", //$NON-NLS-1$
                Map.of("set", Map.of("group", "CommandGroup.MyCustomGroup"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        @SuppressWarnings("unchecked")
        Map<String, Object> changes = (Map<String, Object>) payload.get("changes"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> set = (Map<String, Object>) changes.get("set"); //$NON-NLS-1$
        assertEquals("CommandGroup.MyCustomGroup", set.get("group")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void updateMetadataValidationAcceptsAllPublicStandardCommandGroups() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        Map<String, Object> payload = service.normalizeUpdatePayload(
                "ДО.Артель", //$NON-NLS-1$
                "CommonCommand.аи_ОтправитьНаАнализИИ", //$NON-NLS-1$
                Map.of("set", Map.of("group", "StandardCommandGroup.NavigationPanelOrdinary"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        @SuppressWarnings("unchecked")
        Map<String, Object> changes = (Map<String, Object>) payload.get("changes"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> set = (Map<String, Object>) changes.get("set"); //$NON-NLS-1$
        assertEquals("NavigationPanelOrdinary", set.get("group")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void updateMetadataValidationRejectsUnknownStandardCommandGroup() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        try {
            service.normalizeUpdatePayload(
                    "ДО.Артель", //$NON-NLS-1$
                    "CommonCommand.аи_ОтправитьНаАнализИИ", //$NON-NLS-1$
                    Map.of("set", Map.of("group", "StandardCommandGroup.UnknownGroup"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Unknown StandardCommandGroup")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("FormCommandBarImportant")); //$NON-NLS-1$
            return;
        }
        assertFalse("Unknown StandardCommandGroup must be rejected", true); //$NON-NLS-1$
    }

    @Test
    public void createMetadataValidationRejectsInvalidTypeDescription() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        try {
            service.normalizeCreatePayload(
                    "ДО.Артель", //$NON-NLS-1$
                    "Constant", //$NON-NLS-1$
                    "ар_AuditInvalidType", //$NON-NLS-1$
                    null,
                    null,
                    Map.of("type", "NotAType")); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Invalid TypeDescription")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("NotAType")); //$NON-NLS-1$
            return;
        }
        assertFalse("Invalid TypeDescription must be rejected", true); //$NON-NLS-1$
    }

    @Test
    public void updateMetadataValidationRejectsInvalidEventSubscriptionSourceType() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        try {
            service.normalizeUpdatePayload(
                    "ДО.Артель", //$NON-NLS-1$
                    "EventSubscription.ар_аи_СобытияИзменений_Задача", //$NON-NLS-1$
                    Map.of("set", Map.of("source", "NotAType"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Invalid TypeDescription")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("changes.set.source")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("NotAType")); //$NON-NLS-1$
            return;
        }
        assertFalse("Invalid EventSubscription.source TypeDescription must be rejected before token issuance", true); //$NON-NLS-1$
    }

    @Test
    public void updateMetadataValidationNormalizesEventSubscriptionSourceChildOp() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        Map<String, Object> payload = service.normalizeUpdatePayload(
                "ДО.Артель", //$NON-NLS-1$
                "EventSubscription.ар_аи_СобытияИзменений_Задача", //$NON-NLS-1$
                Map.of("children_ops", List.of(Map.of( //$NON-NLS-1$
                        "op", "upsert", //$NON-NLS-1$ //$NON-NLS-2$
                        "name", "source", //$NON-NLS-1$ //$NON-NLS-2$
                        "kind", "TypeDescription", //$NON-NLS-1$ //$NON-NLS-2$
                        "set", Map.of("types", List.of("TaskObject.ЗадачаИсполнителя")))))); //$NON-NLS-1$ //$NON-NLS-2$

        @SuppressWarnings("unchecked")
        Map<String, Object> changes = (Map<String, Object>) payload.get("changes"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> set = (Map<String, Object>) changes.get("set"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) set.get("source"); //$NON-NLS-1$

        assertEquals(List.of("TaskObject.ЗадачаИсполнителя"), source.get("types")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(changes.containsKey("children_ops")); //$NON-NLS-1$
    }

    @Test
    public void updateMetadataValidationNormalizesEventSubscriptionSourceChildFqn() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        Map<String, Object> payload = service.normalizeUpdatePayload(
                "ДО.Артель", //$NON-NLS-1$
                "EventSubscription.ар_аи_СобытияИзменений_Задача", //$NON-NLS-1$
                Map.of("children_ops", List.of(Map.of( //$NON-NLS-1$
                        "op", "upsert", //$NON-NLS-1$ //$NON-NLS-2$
                        "child_fqn", "EventSubscription.ар_аи_СобытияИзменений_Задача.source", //$NON-NLS-1$ //$NON-NLS-2$
                        "kind", "TypeDescription", //$NON-NLS-1$ //$NON-NLS-2$
                        "set", Map.of("types", List.of("TaskObject.ЗадачаИсполнителя")))))); //$NON-NLS-1$ //$NON-NLS-2$

        @SuppressWarnings("unchecked")
        Map<String, Object> changes = (Map<String, Object>) payload.get("changes"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> set = (Map<String, Object>) changes.get("set"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) set.get("source"); //$NON-NLS-1$

        assertEquals(List.of("TaskObject.ЗадачаИсполнителя"), source.get("types")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(changes.containsKey("children_ops")); //$NON-NLS-1$
    }

    @Test
    public void updateMetadataValidationRejectsUnsupportedChildOpWithoutChildFqn() {
        MetadataRequestValidationService service = new MetadataRequestValidationService();

        try {
            service.normalizeUpdatePayload(
                    "ДО.Артель", //$NON-NLS-1$
                    "Catalog.Номенклатура", //$NON-NLS-1$
                    Map.of("children_ops", List.of(Map.of( //$NON-NLS-1$
                            "op", "update", //$NON-NLS-1$ //$NON-NLS-2$
                            "name", "Реквизит", //$NON-NLS-1$ //$NON-NLS-2$
                            "set", Map.of("synonym", "Реквизит"))))); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("children_ops item must contain child_fqn")); //$NON-NLS-1$
            return;
        }
        assertFalse("Unsupported children_ops without child_fqn must be rejected before token issuance", true); //$NON-NLS-1$
    }

    @Test
    public void acceptsMutateRoleRightsOperationAndKeepsOperationName() {
        StubValidationService validationService = new StubValidationService();
        EdtValidateRequestTool tool = new EdtValidateRequestTool(validationService);

        ToolResult result = tool.execute(Map.of(
                "project", "ДО.Артель", //$NON-NLS-1$ //$NON-NLS-2$
                "operation", "mutate_role_rights", //$NON-NLS-1$ //$NON-NLS-2$
                "payload", Map.of(
                        "project", "ДО.Артель", //$NON-NLS-1$ //$NON-NLS-2$
                        "role", "ар_ОсновнаяРоль", //$NON-NLS-1$ //$NON-NLS-2$
                        "operations", List.of(Map.of(
                                "op", "set_config_right", //$NON-NLS-1$ //$NON-NLS-2$
                                "right", "Administration", //$NON-NLS-1$ //$NON-NLS-2$
                                "value", "allow" //$NON-NLS-1$ //$NON-NLS-2$
                        ))
                ))).join();

        assertTrue(result.isSuccess());
        assertEquals(ValidationOperation.MUTATE_ROLE_RIGHTS, validationService.lastRequest.operation());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("mutate_role_rights", json.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("token-1", json.get("validationToken").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class StubValidationService extends MetadataRequestValidationService {
        private ValidationRequest lastRequest;

        @Override
        public ValidationResult validateAndIssueToken(ValidationRequest request) {
            lastRequest = request;
            return new ValidationResult(
                    true,
                    request.projectName(),
                    request.operation().getToolName(),
                    List.of("ok"), //$NON-NLS-1$
                    request.payload(),
                    "token-1", //$NON-NLS-1$
                    123L);
        }
    }
}
