/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReplacementShapeSafety {

    private static final Pattern CALL_OCCURRENCE = Pattern.compile(
            "([\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*)\\s*\\("); //$NON-NLS-1$
    private static final Pattern MULTILINE_CALL_START = Pattern.compile(
            "^\\s*([\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*)\\s*\\(\\s*$"); //$NON-NLS-1$
    private static final Pattern GLUED_BSL_END = Pattern.compile(
            "(?iu)(КонецПроцедурыКонецПроцедуры|КонецФункцииКонецФункции)"); //$NON-NLS-1$
    private static final Pattern GLUED_BSL_DECLARATION = Pattern.compile(
            "(?iu)(Процедура|Функция)[^\\r\\n]*\\)[ \\t]*(Процедура|Функция)"); //$NON-NLS-1$
    private static final Pattern GLUED_BSL_END_TO_DECLARATION = Pattern.compile(
            "(?iu)(КонецПроцедуры|КонецФункции)[ \\t]*(Процедура|Функция)"); //$NON-NLS-1$
    private static final Pattern GLUED_OPERATOR_AFTER_SEMICOLON = Pattern.compile(
            "\\S;\\t\\S"); //$NON-NLS-1$

    private ReplacementShapeSafety() {
    }

    public static SafetyResult evaluate(String oldSlice, String newText) {
        if (oldSlice == null || newText == null) {
            return SafetyResult.unsafe("Replacement shape safety requires non-null old and new text"); //$NON-NLS-1$
        }

        SafetyResult duplicatedCalls = rejectDuplicatedCallOnOneLine(oldSlice);
        if (!duplicatedCalls.isSafe()) {
            return duplicatedCalls;
        }

        return rejectRemovedMultilineCallArguments(oldSlice, newText);
    }

    public static SafetyResult evaluateResult(String resultFragment) {
        if (resultFragment == null) {
            return SafetyResult.unsafe("Replacement shape safety requires non-null result text"); //$NON-NLS-1$
        }

        String normalized = normalizeLineEndings(resultFragment);
        if (GLUED_BSL_END.matcher(normalized).find()) {
            return SafetyResult.unsafe(
                    "Unsafe fuzzy edit: result contains glued BSL procedure/function endings"); //$NON-NLS-1$
        }
        if (GLUED_BSL_END_TO_DECLARATION.matcher(normalized).find()) {
            return SafetyResult.unsafe(
                    "Unsafe fuzzy edit: result contains glued BSL procedure/function boundary"); //$NON-NLS-1$
        }

        String[] lines = normalized.split("\n", -1); //$NON-NLS-1$
        for (String line : lines) {
            if (GLUED_BSL_DECLARATION.matcher(line).find()) {
                return SafetyResult.unsafe(
                        "Unsafe fuzzy edit: result contains glued BSL procedure/function declarations"); //$NON-NLS-1$
            }
            if (GLUED_OPERATOR_AFTER_SEMICOLON.matcher(line).find()) {
                return SafetyResult.unsafe(
                        "Unsafe fuzzy edit: result contains glued BSL statements after semicolon"); //$NON-NLS-1$
            }
        }

        return SafetyResult.safe();
    }

    private static SafetyResult rejectDuplicatedCallOnOneLine(String text) {
        String[] lines = normalizeLineEndings(text).split("\n", -1); //$NON-NLS-1$
        for (String line : lines) {
            Map<String, Integer> calls = new HashMap<>();
            Matcher matcher = CALL_OCCURRENCE.matcher(line);
            while (matcher.find()) {
                String callName = normalizeCallName(matcher.group(1));
                int count = calls.merge(callName, Integer.valueOf(1), Integer::sum).intValue();
                if (count > 1) {
                    return SafetyResult.unsafe(
                            "Unsafe fuzzy edit: matched slice contains duplicated BSL call on one line: " //$NON-NLS-1$
                                    + matcher.group(1));
                }
            }
        }
        return SafetyResult.safe();
    }

    private static SafetyResult rejectRemovedMultilineCallArguments(String oldSlice, String newText) {
        List<CallShape> oldCalls = extractMultilineCallShapes(oldSlice);
        if (oldCalls.isEmpty()) {
            return SafetyResult.safe();
        }

        Map<String, List<CallShape>> newCallsByName = new HashMap<>();
        for (CallShape newCall : extractMultilineCallShapes(newText)) {
            newCallsByName.computeIfAbsent(normalizeCallName(newCall.name()), ignored -> new ArrayList<>())
                    .add(newCall);
        }

        for (CallShape oldCall : oldCalls) {
            List<CallShape> newCalls = newCallsByName.get(normalizeCallName(oldCall.name()));
            if (newCalls == null || newCalls.isEmpty()) {
                return SafetyResult.unsafe(
                        "Unsafe fuzzy edit: replacement removes multiline call shape for " + oldCall.name()); //$NON-NLS-1$
            }
            int bestArgumentLineCount = newCalls.stream()
                    .mapToInt(CallShape::argumentLineCount)
                    .max()
                    .orElse(0);
            if (bestArgumentLineCount < oldCall.argumentLineCount()) {
                return SafetyResult.unsafe(
                        "Unsafe fuzzy edit: replacement reduces argument lines for " + oldCall.name() //$NON-NLS-1$
                                + " from " + oldCall.argumentLineCount() //$NON-NLS-1$
                                + " to " + bestArgumentLineCount); //$NON-NLS-1$
            }
        }

        return SafetyResult.safe();
    }

    private static List<CallShape> extractMultilineCallShapes(String text) {
        String[] lines = normalizeLineEndings(text).split("\n", -1); //$NON-NLS-1$
        List<CallShape> result = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            Matcher startMatcher = MULTILINE_CALL_START.matcher(lines[index]);
            if (!startMatcher.matches()) {
                continue;
            }

            int argumentLineCount = 0;
            boolean closed = false;
            for (int inner = index + 1; inner < lines.length; inner++) {
                String trimmed = lines[inner].trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (isCallCloseLine(trimmed)) {
                    closed = true;
                    index = inner;
                    break;
                }
                argumentLineCount++;
                if (endsCall(trimmed)) {
                    closed = true;
                    index = inner;
                    break;
                }
            }

            if (closed) {
                result.add(new CallShape(startMatcher.group(1), argumentLineCount));
            }
        }
        return result;
    }

    private static boolean isCallCloseLine(String trimmedLine) {
        return trimmedLine.startsWith(")"); //$NON-NLS-1$
    }

    private static boolean endsCall(String trimmedLine) {
        return trimmedLine.endsWith(")") //$NON-NLS-1$
                || trimmedLine.endsWith(");"); //$NON-NLS-1$
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String normalizeCallName(String callName) {
        return callName.toLowerCase(Locale.ROOT);
    }

    public static final class SafetyResult {

        private final boolean safe;
        private final String reason;

        public SafetyResult(boolean safe, String reason) {
            this.safe = safe;
            this.reason = reason;
        }

        public static SafetyResult safe() {
            return new SafetyResult(true, null);
        }

        public static SafetyResult unsafe(String reason) {
            return new SafetyResult(false, reason);
        }

        public boolean isSafe() {
            return safe;
        }

        public String reason() {
            return reason;
        }
    }

    private record CallShape(String name, int argumentLineCount) {
    }
}
