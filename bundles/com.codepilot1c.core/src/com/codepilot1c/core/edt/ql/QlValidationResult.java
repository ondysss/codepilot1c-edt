package com.codepilot1c.core.edt.ql;

import java.util.List;

/**
 * Result of validating a 1C query-language text.
 *
 * @param valid {@code true} when there are zero ERROR-severity issues
 * @param dcsMode whether DCS validation mode was requested
 * @param errorCount number of ERROR-severity issues
 * @param warningCount number of WARNING-severity issues
 * @param infoCount number of INFO-severity issues
 * @param issues the collected issues (syntax + semantic)
 */
public record QlValidationResult(
        boolean valid,
        boolean dcsMode,
        int errorCount,
        int warningCount,
        int infoCount,
        List<QlIssue> issues) {
}
