package com.codepilot1c.core.edt.ql;

/**
 * A single 1C query-language validation issue.
 *
 * <p>Line/column are 1-based; {@code -1} means the position is unavailable.</p>
 *
 * @param severity issue severity: {@code ERROR}, {@code WARNING} or {@code INFO}
 * @param message human-readable diagnostic message
 * @param line 1-based line number, or {@code -1}
 * @param column 1-based column, or {@code -1}
 * @param offset character offset in the query text, or {@code -1}
 */
public record QlIssue(String severity, String message, int line, int column, int offset) {
}
