/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.diagnostics;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tokenizes workspace-relative BSL/mdo paths for fuzzy matching against
 * EDT runtime marker haystacks.
 *
 * <p>Extracted as a pure Java utility so it can be unit-tested without the
 * Eclipse runtime.</p>
 *
 * <p>Design invariant: every surviving token must be discriminating, and the
 * ALL-tokens threshold requires every token to appear in a marker's haystack.
 * Named module-kind stems ({@code managermodule}, {@code objectmodule}, …)
 * are intentionally kept out of {@link #GENERIC} so that a path like
 * {@code Catalogs/InvoiceSettings/ManagerModule.bsl} produces tokens
 * {@code ["invoicesettings", "managermodule"]} with threshold 2.  This
 * excludes sibling-object markers — {@code Catalog.InvoiceSettings},
 * {@code Catalog.InvoiceSettings.Form.ListForm.Form}, etc. — that share
 * the object-name token but lack the module-kind token.</p>
 */
public final class PathMatchTokens {

    /**
     * Path segments that are too structural/common to identify a specific
     * metadata object. Container kinds ({@code dataprocessors}, etc.) and
     * truly structureless names ({@code module}) are filtered out; named
     * module-kind stems such as {@code managermodule} or {@code objectmodule}
     * are <em>not</em> generic — they are essential discriminators that
     * prevent the ALL-tokens matcher from matching sibling objects that share
     * the same object-name prefix but differ in module kind.
     */
    static final Set<String> GENERIC = Set.of(
            // workspace / project-level containers
            "src", //$NON-NLS-1$
            "bin", //$NON-NLS-1$
            "configuration", //$NON-NLS-1$
            "конфигурация", //$NON-NLS-1$
            "extension", //$NON-NLS-1$
            "external", //$NON-NLS-1$
            // top-level EDT metadata kind folders (plural) — EN
            "catalogs", //$NON-NLS-1$
            "documents", //$NON-NLS-1$
            "documentjournals", //$NON-NLS-1$
            "documentnumerators", //$NON-NLS-1$
            "enums", //$NON-NLS-1$
            "reports", //$NON-NLS-1$
            "dataprocessors", //$NON-NLS-1$
            "informationregisters", //$NON-NLS-1$
            "accumulationregisters", //$NON-NLS-1$
            "accountingregisters", //$NON-NLS-1$
            "calculationregisters", //$NON-NLS-1$
            "chartsofcharacteristictypes", //$NON-NLS-1$
            "chartsofaccounts", //$NON-NLS-1$
            "chartsofcalculationtypes", //$NON-NLS-1$
            "businessprocesses", //$NON-NLS-1$
            "tasks", //$NON-NLS-1$
            "sequences", //$NON-NLS-1$
            "exchangeplans", //$NON-NLS-1$
            "commonmodules", //$NON-NLS-1$
            "commonforms", //$NON-NLS-1$
            "commoncommands", //$NON-NLS-1$
            "commandgroups", //$NON-NLS-1$
            "commontemplates", //$NON-NLS-1$
            "commonpictures", //$NON-NLS-1$
            "commonattributes", //$NON-NLS-1$
            "constants", //$NON-NLS-1$
            "roles", //$NON-NLS-1$
            "subsystems", //$NON-NLS-1$
            "sessionparameters", //$NON-NLS-1$
            "functionaloptions", //$NON-NLS-1$
            "functionaloptionsparameters", //$NON-NLS-1$
            "settingsstorages", //$NON-NLS-1$
            "externaldatasources", //$NON-NLS-1$
            "eventsubscriptions", //$NON-NLS-1$
            "scheduledjobs", //$NON-NLS-1$
            "filtercriteria", //$NON-NLS-1$
            "definedtypes", //$NON-NLS-1$
            "xdtopackages", //$NON-NLS-1$
            "webservices", //$NON-NLS-1$
            "httpservices", //$NON-NLS-1$
            "wsreferences", //$NON-NLS-1$
            "styles", //$NON-NLS-1$
            "styleitems", //$NON-NLS-1$
            "languages", //$NON-NLS-1$
            "interfaces", //$NON-NLS-1$
            "bots", //$NON-NLS-1$
            "integrationservices", //$NON-NLS-1$
            // form / command / template subfolders
            "forms", //$NON-NLS-1$
            "form", //$NON-NLS-1$
            "commands", //$NON-NLS-1$
            "command", //$NON-NLS-1$
            "templates", //$NON-NLS-1$
            "template", //$NON-NLS-1$
            "rights", //$NON-NLS-1$
            "schemas", //$NON-NLS-1$
            "schema", //$NON-NLS-1$
            "layout", //$NON-NLS-1$
            // module filename stems — only truly structureless names are generic here.
            // Named module types (managermodule, objectmodule, …) are deliberately kept
            // OUT of this set so they remain as tokens: with ALL-tokens threshold they
            // distinguish the target module from sibling objects (forms, the catalog/
            // document itself, etc.) that share the same object-name prefix but differ
            // in module kind.  Bare "module" (used for CommonModule / form Module.bsl)
            // is still generic because it appears in many unrelated object types.
            "module", //$NON-NLS-1$
            // file-format noise
            "mdo", //$NON-NLS-1$
            "module.bsl", //$NON-NLS-1$
            "xml"); //$NON-NLS-1$

    private PathMatchTokens() {
    }

    /**
     * Builds the deduplicated, lowercased set of distinguishing tokens from a
     * list of workspace-relative path candidates. Generic structural segments
     * and segments shorter than 3 chars are dropped.
     *
     * @param relativeCandidates workspace-relative candidate paths
     * @return tokens that uniquely identify the path (order-preserved)
     */
    public static List<String> buildMatchTokens(List<String> relativeCandidates) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (relativeCandidates == null) {
            return List.of();
        }
        for (String candidate : relativeCandidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            for (String rawSegment : candidate.split("/")) { //$NON-NLS-1$
                if (rawSegment == null || rawSegment.isBlank()) {
                    continue;
                }
                String segment = rawSegment.toLowerCase(Locale.ROOT);
                if (segment.endsWith(".bsl") || segment.endsWith(".mdo") //$NON-NLS-1$ //$NON-NLS-2$
                        || segment.endsWith(".xml") || segment.endsWith(".os")) { //$NON-NLS-1$ //$NON-NLS-2$
                    int dot = segment.lastIndexOf('.');
                    if (dot > 0) {
                        segment = segment.substring(0, dot);
                    }
                }
                if (segment.length() < 3 || GENERIC.contains(segment)) {
                    continue;
                }
                tokens.add(segment);
            }
        }
        return List.copyOf(tokens);
    }

    /**
     * Returns the number of token matches required for a marker haystack to
     * be considered a match for the path. Requires <b>all</b> surviving
     * tokens to be present — strict filter, since every remaining token is
     * already known to be distinguishing (not generic).
     *
     * @param tokens output of {@link #buildMatchTokens(List)}
     * @return the threshold (0 when no tokens survived)
     */
    public static int computeTokenThreshold(List<String> tokens) {
        return tokens == null ? 0 : tokens.size();
    }

    /**
     * Word-boundary match of {@code token} in {@code haystack}. A plain
     * {@code haystack.contains(token)} incorrectly matches {@code "alerts"}
     * inside {@code "alertstypes"} or {@code "integrationalerts"} — the
     * surrounding chars must be non-alphanumeric (or string boundary) for
     * the token to count as a distinguishing identifier match.
     *
     * <p>Assumes both arguments are already lowercased.</p>
     */
    public static boolean matchesAsWord(String haystack, String token) {
        if (haystack == null || token == null || token.isEmpty()) {
            return false;
        }
        int tokenLen = token.length();
        int from = 0;
        while (from <= haystack.length() - tokenLen) {
            int idx = haystack.indexOf(token, from);
            if (idx < 0) {
                return false;
            }
            boolean leftOk = idx == 0 || !isWordChar(haystack.charAt(idx - 1));
            int afterIdx = idx + tokenLen;
            boolean rightOk = afterIdx == haystack.length() || !isWordChar(haystack.charAt(afterIdx));
            if (leftOk && rightOk) {
                return true;
            }
            from = idx + 1;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_'
                // Cyrillic lowercase (after toLowerCase ROOT, these stay lowercase).
                || (c >= '\u0430' && c <= '\u044F')
                || c == '\u0451'; // ё
    }
}
