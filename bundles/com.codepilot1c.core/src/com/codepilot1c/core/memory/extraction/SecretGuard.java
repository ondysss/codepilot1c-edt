/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.extraction;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Filters credentials and sensitive data before memory persistence.
 *
 * <p>Detects common secret patterns including:</p>
 * <ul>
 *   <li>API keys and tokens</li>
 *   <li>Passwords and credentials</li>
 *   <li>1C infobase connection strings</li>
 *   <li>1C module exports with sensitive names</li>
 * </ul>
 */
public final class SecretGuard {

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            // Generic secrets
            Pattern.compile("(?i)(password|passwd|pwd|secret|token|api[_-]?key|apikey)\\s*[=:]\\s*\\S+"), //$NON-NLS-1$
            Pattern.compile("(?i)(bearer|authorization)\\s*[=:]?\\s*\\S+"), //$NON-NLS-1$
            Pattern.compile("(?i)(private[_-]?key|ssh[_-]?key|rsa)\\s*[=:]\\s*\\S+"), //$NON-NLS-1$

            // Connection strings
            Pattern.compile("(?i)(jdbc|mongodb|postgresql|mysql|redis)://\\S+"), //$NON-NLS-1$
            Pattern.compile("(?i)server\\s*=\\s*\\S+;\\s*database\\s*="), //$NON-NLS-1$

            // 1C-specific
            Pattern.compile("(?i)(Srvr|Ref)\\s*=\\s*\"[^\"]+\""), //$NON-NLS-1$
            Pattern.compile("(?i)\u041F\u0430\u0440\u043E\u043B\u044C\\s*=\\s*\\S+"), // Пароль=... //$NON-NLS-1$
            Pattern.compile("(?i)\u041A\u043B\u044E\u0447\\s*=\\s*\\S+"), // Ключ=... //$NON-NLS-1$
            Pattern.compile("(?i)\u0422\u043E\u043A\u0435\u043D\\s*=\\s*\\S+"), // Токен=... //$NON-NLS-1$

            // 1C constants and module exports with sensitive names (IMPORTANT #8 fix)
            Pattern.compile("(?i)\u041A\u043E\u043D\u0441\u0442\u0430\u043D\u0442\u044B\\." // Константы.
                    + "(?:\u041F\u0430\u0440\u043E\u043B|\u041A\u043B\u044E\u0447|\u0422\u043E\u043A\u0435\u043D|\u0421\u0435\u043A\u0440\u0435\u0442)\\S*"), //$NON-NLS-1$
            Pattern.compile("(?i)\u042D\u043A\u0441\u043F\u043E\u0440\u0442\\s+" // Экспорт (module export)
                    + "(?:\u041F\u0430\u0440\u043E\u043B|\u041A\u043B\u044E\u0447|\u0422\u043E\u043A\u0435\u043D)\\S*"), //$NON-NLS-1$
            Pattern.compile("(?i)Constants\\." // Constants.Password/Key/Token/Secret
                    + "(?:Password|Key|Token|Secret)\\S*"), //$NON-NLS-1$

            // Base64-encoded long strings (potential secrets)
            Pattern.compile("[A-Za-z0-9+/]{40,}={0,2}")  //$NON-NLS-1$
    );

    private SecretGuard() {
    }

    /**
     * Returns true if the text contains patterns that look like secrets.
     */
    public static boolean containsSecrets(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (Pattern pattern : SECRET_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filters text by replacing detected secret patterns with [REDACTED].
     */
    public static String filter(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = text;
        for (Pattern pattern : SECRET_PATTERNS) {
            result = pattern.matcher(result).replaceAll("[REDACTED]"); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Returns true if the given key name suggests it contains sensitive data.
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        return lower.contains("password") || lower.contains("secret") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.contains("token") || lower.contains("credential") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.contains("key") || lower.contains("\u043F\u0430\u0440\u043E\u043B") //$NON-NLS-1$ // пароль
                || lower.contains("\u043A\u043B\u044E\u0447") // ключ //$NON-NLS-1$
                || lower.contains("\u0442\u043E\u043A\u0435\u043D"); // токен //$NON-NLS-1$
    }
}
