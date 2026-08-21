/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.permissions;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.permissions.ProfilePermissionGate.GateDecision;
import com.codepilot1c.core.permissions.ProfilePermissionGate.GateResult;

public class PermissionEvaluatorResourceKeysTest {

    @Test
    public void updateMetadataIsScopedByTargetFqn() {
        assertDenied("update_metadata", Map.of( //$NON-NLS-1$
                "project", "P", //$NON-NLS-1$ //$NON-NLS-2$
                "target_fqn", "Catalog.Организации", //$NON-NLS-1$ //$NON-NLS-2$
                "changes", Map.of(), //$NON-NLS-1$
                "validation_token", "token"), "Catalog.*"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void deleteMetadataIsScopedByTargetFqn() {
        assertDenied("delete_metadata", //$NON-NLS-1$
                Map.of("target_fqn", "Document.Заказ"), "Document.*"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void addMetadataChildIsScopedByParentFqn() {
        assertDenied("add_metadata_child", //$NON-NLS-1$
                Map.of("parent_fqn", "Catalog.Номенклатура"), "Catalog.*"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void mutateFormModelIsScopedByFormFqn() {
        assertDenied("mutate_form_model", Map.of( //$NON-NLS-1$
                "form_fqn", "Catalog.Номенклатура.Form.ФормаЭлемента"), //$NON-NLS-1$ //$NON-NLS-2$
                "Catalog.*"); //$NON-NLS-1$
    }

    @Test
    public void applyFormRecipePrefersFormFqnOverOwnerFqn() {
        GateResult result = evaluate("apply_form_recipe", Map.of( //$NON-NLS-1$
                "form_fqn", "Catalog.Номенклатура.Form.ФормаЭлемента", //$NON-NLS-1$ //$NON-NLS-2$
                "owner_fqn", "Document.Заказ"), "Catalog.*"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(GateDecision.DENY, result.decision());
        assertEquals("Catalog.Номенклатура.Form.ФормаЭлемента", result.resource()); //$NON-NLS-1$
    }

    @Test
    public void createFormIsScopedByOwnerFqn() {
        assertDenied("create_form", //$NON-NLS-1$
                Map.of("owner_fqn", "Catalog.Номенклатура"), "Catalog.*"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void ensureModuleArtifactIsScopedByObjectFqn() {
        assertDenied("ensure_module_artifact", //$NON-NLS-1$
                Map.of("object_fqn", "Catalog.Номенклатура"), "Catalog.*"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void mutateRoleRightsIsScopedByRole() {
        assertDenied("mutate_role_rights", //$NON-NLS-1$
                Map.of("role", "БазаЗнанийПросмотр"), "БазаЗнаний*"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void renderTemplateIsScopedByTemplateFqn() {
        assertDenied("render_template", //$NON-NLS-1$
                Map.of("template_fqn", "CommonTemplate.Макет"), "CommonTemplate.*"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void gitMutateIsScopedByRepoPath() {
        assertDenied("git_mutate", Map.of( //$NON-NLS-1$
                "operation", "clone", "repo_path", "/tmp/repo"), "/tmp/**"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void gitMutateWithoutRepoPathStaysAnyResource() {
        GateResult result = evaluate("git_mutate", Map.of( //$NON-NLS-1$
                "operation", "commit", //$NON-NLS-1$ //$NON-NLS-2$
                "project_name", "X", //$NON-NLS-1$ //$NON-NLS-2$
                "message", "m"), "/tmp/**"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(GateDecision.NO_RULE, result.decision());
        assertEquals(PermissionEvaluator.ANY_RESOURCE, result.resource());
    }

    @Test
    public void gitOperationSelectorDoesNotReplaceRawRepositoryResource() {
        GateResult result = ProfilePermissionGate.evaluate(
                List.of(PermissionRule.deny("git_mutate") //$NON-NLS-1$
                        .forResourceRegex("operation:pull") //$NON-NLS-1$
                        .build()),
                List.of(), "git_mutate", Map.of( //$NON-NLS-1$
                        "operation", "pull", //$NON-NLS-1$ //$NON-NLS-2$
                        "repo_path", "/tmp/repo")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(GateDecision.DENY, result.decision());
        assertEquals("/tmp/repo", result.resource()); //$NON-NLS-1$
    }

    @Test
    public void createMetadataHasNoObjectScope() {
        GateResult result = evaluate("create_metadata", Map.of( //$NON-NLS-1$
                "project", "P", //$NON-NLS-1$ //$NON-NLS-2$
                "kind", "Catalog", //$NON-NLS-1$ //$NON-NLS-2$
                "name", "X", //$NON-NLS-1$ //$NON-NLS-2$
                "validation_token", "token"), "Catalog.*"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(GateDecision.NO_RULE, result.decision());
        assertEquals(PermissionEvaluator.ANY_RESOURCE, result.resource());
    }

    @Test
    public void legacyCommandKeyStillWinsForDcsManage() {
        Map<String, Object> arguments = Map.of(
                "command", "upsert_query_dataset", //$NON-NLS-1$ //$NON-NLS-2$
                "owner_fqn", "Report.X"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(GateDecision.DENY,
                evaluate("dcs_manage", arguments, "upsert_*").decision()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(GateDecision.NO_RULE,
                evaluate("dcs_manage", arguments, "Report.*").decision()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void legacyCommandKeyStillWinsForExtensionManage() {
        Map<String, Object> arguments = Map.of(
                "command", "create", //$NON-NLS-1$ //$NON-NLS-2$
                "extension_project", "Extension.X"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(GateDecision.DENY,
                evaluate("extension_manage", arguments, "create").decision()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(GateDecision.NO_RULE,
                evaluate("extension_manage", arguments, "Extension.*").decision()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void repoPathSeparatorsAreNormalizedForGate() {
        assertDenied("git_mutate", //$NON-NLS-1$
                Map.of("repo_path", "C:\\repo\\x"), "C:/repo/**"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void commandValueIsNotNormalizedWhenRepoPathPresent() {
        GateResult result = ProfilePermissionGate.evaluate(
                List.of(), List.of(), "dcs_manage", Map.of( //$NON-NLS-1$
                        "command", "a\\b", //$NON-NLS-1$ //$NON-NLS-2$
                        "repo_path", "/x")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("a\\b", result.resource()); //$NON-NLS-1$
    }

    private static void assertDenied(
            String toolName, Map<String, Object> arguments, String pattern) {
        assertEquals(GateDecision.DENY, evaluate(toolName, arguments, pattern).decision());
    }

    private static GateResult evaluate(
            String toolName, Map<String, Object> arguments, String pattern) {
        return ProfilePermissionGate.evaluate(
                List.of(PermissionRule.deny(toolName).forResourcePattern(pattern).build()),
                List.of(), toolName, arguments);
    }
}
