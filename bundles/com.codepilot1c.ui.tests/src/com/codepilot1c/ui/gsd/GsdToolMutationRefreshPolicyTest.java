/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.tools.ToolResult;

/** Tests the post-tool mutation refresh decision used by ChatView. */
public class GsdToolMutationRefreshPolicyTest {

    @Test
    public void successfulGsdMutationRefreshes() {
        ToolCall call = call("c1", "gsd_update_task"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GsdToolMutationRefreshPolicy.shouldRefresh(
                List.of(call), Map.of("c1", ToolResult.success("ok")), Set.of("c1"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void shippingMutationsRefreshPhaseState() {
        for (String name : List.of(
                "gsd_record_verification_outcome", "gsd_record_shipment")) { //$NON-NLS-1$ //$NON-NLS-2$
            ToolCall call = call("c1", name); //$NON-NLS-1$
            assertTrue(name, GsdToolMutationRefreshPolicy.shouldRefresh(
                    List.of(call), Map.of("c1", ToolResult.success("ok")), //$NON-NLS-1$ //$NON-NLS-2$
                    Set.of("c1"))); //$NON-NLS-1$
        }
    }

    @Test
    public void failedOrDeniedMutationDoesNotRefresh() {
        ToolCall call = call("c1", "gsd_transition"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(GsdToolMutationRefreshPolicy.shouldRefresh(
                List.of(call), Map.of("c1", ToolResult.failure("denied")), Set.of("c1"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void successfulReadOnlyGsdCallDoesNotRefresh() {
        ToolCall call = call("c1", "gsd_get_state"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(GsdToolMutationRefreshPolicy.shouldRefresh(
                List.of(call), Map.of("c1", ToolResult.success("state")), Set.of("c1"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void successfulNonGsdToolDoesNotRefresh() {
        ToolCall call = call("c1", "write_file"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(GsdToolMutationRefreshPolicy.shouldRefresh(
                List.of(call), Map.of("c1", ToolResult.success("ok")), Set.of("c1"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void mixedBatchRefreshesWhenAnyMutationSucceeds() {
        ToolCall read = call("c1", "gsd_get_state"); //$NON-NLS-1$ //$NON-NLS-2$
        ToolCall mutate = call("c2", "gsd_record_evidence"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GsdToolMutationRefreshPolicy.shouldRefresh(
                List.of(read, mutate),
                Map.of("c1", ToolResult.success("state"), //$NON-NLS-1$ //$NON-NLS-2$
                        "c2", ToolResult.success("recorded")), //$NON-NLS-1$ //$NON-NLS-2$
                Set.of("c1", "c2"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void skippedConfirmationSuccessDoesNotRefreshWithoutExecution() {
        ToolCall call = call("c1", "gsd_update_task"); //$NON-NLS-1$ //$NON-NLS-2$
        ToolResult skipped = ToolResult.success(
                "skipped", ToolResult.ToolResultType.CONFIRMATION); //$NON-NLS-1$

        assertFalse(GsdToolMutationRefreshPolicy.shouldRefresh(
                List.of(call), Map.of("c1", skipped), Set.of())); //$NON-NLS-1$
    }

    private ToolCall call(String id, String name) {
        return new ToolCall(id, name, "{}"); //$NON-NLS-1$
    }
}
