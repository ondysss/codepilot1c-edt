/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.diagnostics;

/**
 * Represents a single EDT diagnostic (error, warning, info).
 */
public record EdtDiagnostic(
        String filePath,
        int lineNumber,
        int charStart,
        int charEnd,
        String message,
        Severity severity,
        String markerType,
        String source,
        String codeSnippet,
        String checkId,
        String checkTitle,
        String checkDescription,
        String issueType,
        String issueSeverity,
        String objectPresentation,
        String locationText) {

    /**
     * Diagnostic severity level.
     */
    public enum Severity {
        ERROR(2),
        WARNING(1),
        INFO(0);

        private final int level;

        Severity(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        /**
         * Converts Eclipse IMarker severity to enum.
         */
        public static Severity fromMarkerSeverity(int severity) {
            return switch (severity) {
                case 2 -> ERROR;   // IMarker.SEVERITY_ERROR
                case 1 -> WARNING; // IMarker.SEVERITY_WARNING
                default -> INFO;   // IMarker.SEVERITY_INFO
            };
        }
    }

    /**
     * Creates a diagnostic from marker data.
     */
    public static EdtDiagnostic fromMarker(
            String filePath,
            int lineNumber,
            int charStart,
            int charEnd,
            String message,
            int markerSeverity,
            String markerType,
            String codeSnippet) {
        return new EdtDiagnostic(
                filePath,
                lineNumber,
                charStart,
                charEnd,
                message,
                Severity.fromMarkerSeverity(markerSeverity),
                markerType,
                "marker", //$NON-NLS-1$
                codeSnippet,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Creates a diagnostic from annotation data.
     */
    public static EdtDiagnostic fromAnnotation(
            String filePath,
            int lineNumber,
            int charStart,
            int charEnd,
            String message,
            Severity severity,
            String annotationType,
            String codeSnippet) {
        return new EdtDiagnostic(
                filePath,
                lineNumber,
                charStart,
                charEnd,
                message,
                severity,
                annotationType,
                "annotation", //$NON-NLS-1$
                codeSnippet,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Creates a diagnostic from EDT runtime marker manager data.
     */
    public static EdtDiagnostic fromRuntimeMarker(
            String filePath,
            String message,
            Severity severity,
            String markerType,
            String checkId,
            String checkTitle,
            String checkDescription,
            String issueType,
            String issueSeverity,
            String objectPresentation,
            String locationText) {
        return new EdtDiagnostic(
                filePath,
                -1,
                -1,
                -1,
                message,
                severity,
                markerType,
                "marker_manager", //$NON-NLS-1$
                null,
                checkId,
                checkTitle,
                checkDescription,
                issueType,
                issueSeverity,
                objectPresentation,
                locationText);
    }

    /**
     * Formats diagnostic for LLM output.
     */
    public String formatForLlm() {
        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(severity.name()).append("** строка ").append(lineNumber); //$NON-NLS-1$ //$NON-NLS-2$
        if (lineNumber < 0) {
            sb.setLength(0);
            sb.append("- **").append(severity.name()).append("**"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (charStart >= 0 && charEnd >= 0 && lineNumber >= 0) {
            sb.append(" (позиция ").append(charStart).append("-").append(charEnd).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        sb.append(": ").append(message); //$NON-NLS-1$
        if (checkId != null && !checkId.isBlank()) {
            sb.append("\n  check_id: ").append(checkId); //$NON-NLS-1$
        }
        if (checkTitle != null && !checkTitle.isBlank()) {
            sb.append("\n  check: ").append(checkTitle); //$NON-NLS-1$
        }
        if (issueType != null && !issueType.isBlank()) {
            sb.append("\n  issue_type: ").append(issueType); //$NON-NLS-1$
        }
        if (issueSeverity != null && !issueSeverity.isBlank()) {
            sb.append("\n  issue_severity: ").append(issueSeverity); //$NON-NLS-1$
        }
        if (locationText != null && !locationText.isBlank()) {
            sb.append("\n  location: ").append(locationText); //$NON-NLS-1$
        }
        if (objectPresentation != null && !objectPresentation.isBlank()) {
            sb.append("\n  object: ").append(objectPresentation); //$NON-NLS-1$
        }
        if (checkDescription != null && !checkDescription.isBlank()) {
            sb.append("\n  details: ").append(checkDescription); //$NON-NLS-1$
        }
        if (codeSnippet != null && !codeSnippet.isBlank()) {
            sb.append("\n  ```\n  ").append(codeSnippet.trim()).append("\n  ```"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }
}
