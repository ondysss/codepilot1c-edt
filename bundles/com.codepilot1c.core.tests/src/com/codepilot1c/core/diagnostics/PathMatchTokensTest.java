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
 * <p>Key invariant: named module-kind stems ({@code managermodule},
 * {@code objectmodule}, …) are NOT in {@link PathMatchTokens#GENERIC}.
 * With ALL-tokens threshold this prevents sibling-object leaks: a marker
 * for {@code Catalog.InvoiceSettings} (missing {@code managermodule}) will
 * not match a query for {@code Catalogs/InvoiceSettings/ManagerModule.bsl}.</p>
 */
public class PathMatchTokensTest {

    // --- token building ----------------------------------------------------

    @Test
    public void dataProcessorManagerModuleKeepsModuleTypeToken() {
        // managermodule is NOT generic; it stays in the token list so the
        // ALL-tokens threshold can distinguish the manager module from sibling
        // objects that share the same object-name prefix.
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("DataProcessors/BankStatementsLoader_v2/ManagerModule.bsl")); //$NON-NLS-1$
        assertEquals(List.of("bankstatementsloader_v2", "managermodule"), tokens); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, PathMatchTokens.computeTokenThreshold(tokens));
    }

    @Test
    public void differentDataProcessorsDoNotCrossMatchWithAllTokensThreshold() {
        // Even though both paths produce "managermodule" as a shared token,
        // the unique object-name token prevents cross-DP matches because
        // ALL tokens must be present in the marker haystack.
        List<String> tokensA = PathMatchTokens.buildMatchTokens(
                List.of("DataProcessors/BankStatementsLoader_v2/ManagerModule.bsl")); //$NON-NLS-1$
        List<String> tokensB = PathMatchTokens.buildMatchTokens(
                List.of("DataProcessors/CasinoCashFlowTransactionsLoading/ManagerModule.bsl")); //$NON-NLS-1$

        // A marker for DP-B must not satisfy all tokens of A (and vice versa).
        String haystackB = "dataprocessor.casinocashflowtransactionsloading.managermodule"; //$NON-NLS-1$
        assertFalse("DP-B marker must not match DP-A tokens", //$NON-NLS-1$
                allTokensMatch(haystackB, tokensA));

        String haystackA = "dataprocessor.bankstatementsloader_v2.managermodule"; //$NON-NLS-1$
        assertFalse("DP-A marker must not match DP-B tokens", //$NON-NLS-1$
                allTokensMatch(haystackA, tokensB));

        // Each DP's own marker matches its own tokens.
        assertTrue(allTokensMatch(haystackA, tokensA));
        assertTrue(allTokensMatch(haystackB, tokensB));
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
        // bare "module" (Module.bsl) is still generic — it cannot distinguish
        // between the many objects that use a plain Module.bsl file.
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("CommonModules/UsersSettings/Module.bsl")); //$NON-NLS-1$
        assertEquals(List.of("userssettings"), tokens); //$NON-NLS-1$
    }

    @Test
    public void informationRegisterRecordSetModuleKeepsModuleTypeToken() {
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("InformationRegisters/UserSessions/RecordSetModule.bsl")); //$NON-NLS-1$
        assertEquals(List.of("usersessions", "recordsetmodule"), tokens); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, PathMatchTokens.computeTokenThreshold(tokens));
    }

    @Test
    public void catalogObjectModuleKeepsModuleTypeToken() {
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("Catalogs/Users/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals(List.of("users", "objectmodule"), tokens); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, PathMatchTokens.computeTokenThreshold(tokens));
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
        assertEquals(List.of("контрагенты", "objectmodule"), tokens); //$NON-NLS-1$ //$NON-NLS-2$
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
        assertEquals(List.of("users", "objectmodule"), tokens); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void shortObjectNameDroppedButModuleTypeKept() {
        // Segments under 3 chars cannot identify a metadata object, so the
        // short object name "A" is dropped. The module-kind stem (objectmodule)
        // is NOT generic and survives as the sole token.
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("Catalogs/A/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals(List.of("objectmodule"), tokens); //$NON-NLS-1$
        assertEquals(1, PathMatchTokens.computeTokenThreshold(tokens));
    }


// --- sibling-object isolation (core regression guard) ------------------

    @Test
    public void catalogManagerModuleExcludesSiblingObjectMarkers() {
        // Regression guard for the sibling-object leak:
        // get_diagnostics scope=file for InvoiceSettings/ManagerModule.bsl
        // must NOT return markers from Catalog.InvoiceSettings (SU192),
        // or from any InvoiceSettings form (SU101/SU102/SU110).
        List<String> tokens = PathMatchTokens.buildMatchTokens(
                List.of("Catalogs/InvoiceSettings/ManagerModule.bsl")); //$NON-NLS-1$
        assertEquals(List.of("invoicesettings", "managermodule"), tokens); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, PathMatchTokens.computeTokenThreshold(tokens));

        // Sibling objects that used to leak — their haystacks lack "managermodule"
        assertFalse(allTokensMatch("catalog.invoicesettings", tokens)); //$NON-NLS-1$
        assertFalse(allTokensMatch("catalog.invoicesettings.form.listform.form", tokens)); //$NON-NLS-1$
        assertFalse(allTokensMatch("catalog.invoicesettings.form.itemform.form", tokens)); //$NON-NLS-1$

        // The actual ManagerModule marker must still match
        assertTrue(allTokensMatch("catalog.invoicesettings.managermodule", tokens)); //$NON-NLS-1$
    }

    @Test
    public void catalogManagerModuleDistinctFromObjectModule() {
        // ManagerModule and ObjectModule of the same catalog are now distinguished.
        List<String> mgr = PathMatchTokens.buildMatchTokens(
                List.of("Catalogs/InvoiceSettings/ManagerModule.bsl")); //$NON-NLS-1$
        List<String> obj = PathMatchTokens.buildMatchTokens(
                List.of("Catalogs/InvoiceSettings/ObjectModule.bsl")); //$NON-NLS-1$

        // Manager module query must not match an ObjectModule marker
        assertFalse(allTokensMatch("catalog.invoicesettings.objectmodule", mgr)); //$NON-NLS-1$
        // Object module query must not match a ManagerModule marker
        assertFalse(allTokensMatch("catalog.invoicesettings.managermodule", obj)); //$NON-NLS-1$
    }

    // --- matchesAsWord: regression from 2026-04-21 ------------------------

    @Test
    public void matchesAsWord_rejectsSubstringMatchInsideLongerIdentifier() {
        // Token "alerts" must NOT match inside "alertstypes" or
        // "paymentsystemstransactionsalertssettings" — this was the root
        // cause of scope=file leaking diagnostics of every "*alert*" object.
        assertFalse(PathMatchTokens.matchesAsWord("catalog.alertstypes", "alerts")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(PathMatchTokens.matchesAsWord(
                "informationregister.paymentsystemstransactionsalertssettings", "alerts")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(PathMatchTokens.matchesAsWord(
                "informationregister.integrationalerts", "alerts")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(PathMatchTokens.matchesAsWord(
                "informationregister.dataloadsalertstoprocess", "alerts")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void matchesAsWord_acceptsTokenSurroundedByPunctuation() {
        // The haystack for InformationRegister.Alerts should still match.
        assertTrue(PathMatchTokens.matchesAsWord("informationregister.alerts", "alerts")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(PathMatchTokens.matchesAsWord(
                "informationregister.alerts.managermodule", "alerts")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(PathMatchTokens.matchesAsWord(
                "informationregisters/alerts/managermodule.bsl", "alerts")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void matchesAsWord_tokenAtStringStartOrEnd() {
        assertTrue(PathMatchTokens.matchesAsWord("alerts.managermodule", "alerts")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(PathMatchTokens.matchesAsWord("catalog.alerts", "alerts")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(PathMatchTokens.matchesAsWord("alerts", "alerts")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void matchesAsWord_multipleOccurrences() {
        // Even if the first occurrence is a substring embed, later whole-word
        // occurrence must still register.
        assertTrue(PathMatchTokens.matchesAsWord(
                "alertstypes catalog.alerts objectmodule", "alerts")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void matchesAsWord_nullAndEmpty() {
        assertFalse(PathMatchTokens.matchesAsWord(null, "x")); //$NON-NLS-1$
        assertFalse(PathMatchTokens.matchesAsWord("haystack", null)); //$NON-NLS-1$
        assertFalse(PathMatchTokens.matchesAsWord("haystack", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void matchesAsWord_underscoreIsWordChar() {
        // "users" must NOT match inside "users_backup" (underscore is wordy).
        assertFalse(PathMatchTokens.matchesAsWord("catalog.users_backup", "users")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void matchesAsWord_cyrillicLowercaseIsWordChar() {
        // "справочник" inside a longer Cyrillic identifier must not match.
        assertFalse(PathMatchTokens.matchesAsWord(
                "справочники", //$NON-NLS-1$
                "справочник")); //$NON-NLS-1$
        // But the exact word should match when delimited.
        assertTrue(PathMatchTokens.matchesAsWord(
                "справочник.users", //$NON-NLS-1$
                "справочник")); //$NON-NLS-1$
    }

    // --- helper ------------------------------------------------------------

    private static boolean allTokensMatch(String haystack, List<String> tokens) {
        return tokens.stream().allMatch(t -> PathMatchTokens.matchesAsWord(haystack, t));
    }
}
