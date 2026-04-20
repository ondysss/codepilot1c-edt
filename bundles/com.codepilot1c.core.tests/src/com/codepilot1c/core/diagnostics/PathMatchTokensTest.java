/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link PathMatchTokens}.
 *
 * <p>Regression: get_diagnostics path filter used to leak diagnostics from
 * unrelated objects because structural path segments (DataProcessors,
 * ManagerModule) were not filtered as generic and the 2-of-N threshold
 * matched any sibling data processor's manager module.</p>
 */
public class PathMatchTokensTest {

    @Test
    public void dataProcessorManagerModuleReducesToSingleUniqueToken() {
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("DataProcessors/BankStatementsLoader_v2/ManagerModule.bsl")); //$NON-NLS-1$
        assertEquals(List.of("bankstatementsloader_v2"), tokens); //$NON-NLS-1$
        assertEquals(1, PathMatchTokens.computeTokenThreshold(tokens));
    }

    @Test
    public void differentDataProcessorsDoNotShareUniqueTokens() {
        // This is the BF of the regression: two paths must produce disjoint
        // unique-token sets so the matcher cannot cross-match them.
        List<String> a = PathMatchTokens.buildMatchTokens(
                List.of("DataProcessors/BankStatementsLoader_v2/ManagerModule.bsl")); //$NON-NLS-1$
        List<String> b = PathMatchTokens.buildMatchTokens(
                List.of("DataProcessors/CasinoCashFlowTransactionsLoading/ManagerModule.bsl")); //$NON-NLS-1$
        assertFalse("Distinct data processors must not share tokens", //$NON-NLS-1$
                a.stream().anyMatch(b::contains));
    }

    @Test
    public void formPathKeepsObjectAndFormNames() {
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("DataProcessors/BankStatementsLoader_v2/Forms/LoaderForm/Form.bsl")); //$NON-NLS-1$
        assertEquals(List.of("bankstatementsloader_v2", "loaderform"), tokens); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Both tokens must match for a form-scoped path", //$NON-NLS-1$
                2, PathMatchTokens.computeTokenThreshold(tokens));
    }

    @Test
    public void commonModulePathReducesToModuleName() {
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("CommonModules/UsersSettings/Module.bsl")); //$NON-NLS-1$
        assertEquals(List.of("userssettings"), tokens); //$NON-NLS-1$
    }

    @Test
    public void informationRegisterPathKept() {
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("InformationRegisters/UserSessions/RecordSetModule.bsl")); //$NON-NLS-1$
        assertEquals(List.of("usersessions"), tokens); //$NON-NLS-1$
    }

    @Test
    public void catalogObjectModuleKept() {
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("Catalogs/Users/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals(List.of("users"), tokens); //$NON-NLS-1$
    }

    @Test
    public void plainModuleBslWithoutContextYieldsNoTokens() {
        // Only a filename like "Module.bsl" has no distinguishing signal.
        List<String> tokens = PathMatchTokens.buildMatchTokens(List.of("Module.bsl")); //$NON-NLS-1$
        assertTrue(tokens.isEmpty());
        assertEquals(0, PathMatchTokens.computeTokenThreshold(tokens));
    }

    @Test
    public void mdoDescriptorIsTokenizedLikeBsl() {
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("DataProcessors/BankStatementsLoader_v2/BankStatementsLoader_v2.mdo")); //$NON-NLS-1$
        assertEquals(List.of("bankstatementsloader_v2"), tokens); //$NON-NLS-1$
    }

    @Test
    public void genericSegmentsStrippedFromRussianCatalog() {
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("src/Конфигурация/Catalogs/Контрагенты/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals(List.of("контрагенты"), tokens); //$NON-NLS-1$
    }

    @Test
    public void srcPrefixStripped() {
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("src/Configuration/CommonModules/Auth/Module.bsl")); //$NON-NLS-1$
        assertEquals(List.of("auth"), tokens); //$NON-NLS-1$
    }

    @Test
    public void thresholdRequiresAllTokens() {
        // With two distinct tokens, threshold must require both — protects
        // against partial matches where a marker happens to contain only one.
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("Reports/SalesSummary/Forms/MainForm/Form.bsl")); //$NON-NLS-1$
        assertEquals(List.of("salessummary", "mainform"), tokens); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, PathMatchTokens.computeTokenThreshold(tokens));
    }

    @Test
    public void nullInputHandled() {
        assertTrue(PathMatchTokens.buildMatchTokens(null).isEmpty());
        assertEquals(0, PathMatchTokens.computeTokenThreshold(null));
        assertEquals(0, PathMatchTokens.computeTokenThreshold(List.of()));
    }

    @Test
    public void duplicatesCollapsedAcrossCandidates() {
        // When resolver produces multiple candidate forms for the same path,
        // tokens must be deduped (order-preserving).
        List<String> tokens = PathMatchTokens.buildMatchTokens(List.of(
                "Catalogs/Users/ObjectModule.bsl", //$NON-NLS-1$
                "src/Configuration/Catalogs/Users/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals(List.of("users"), tokens); //$NON-NLS-1$
    }

    @Test
    public void shortSegmentsDropped() {
        // Segments under 3 chars cannot identify a metadata object.
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("Catalogs/A/ObjectModule.bsl")); //$NON-NLS-1$
        assertTrue(tokens.isEmpty());
    }
}
