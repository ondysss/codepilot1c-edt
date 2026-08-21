/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.regex.Pattern;

/** Lexical release-artifact policy shared by Ship permissions and execution. */
public final class GsdShipPathPolicy {

    public static final String RELEASE_ARTIFACT_PATH_REGEX =
            "(?:\\./)?(?:CHANGELOG\\.md|RELEASE_NOTES\\.md|release-notes\\.md|" //$NON-NLS-1$
                    + "(?:docs/release-notes|release-notes)/" //$NON-NLS-1$
                    + "[A-Za-z0-9][A-Za-z0-9._-]*\\.(?:md|txt|json))"; //$NON-NLS-1$
    public static final String NON_RELEASE_ARTIFACT_PATH_REGEX =
            "(?!(?:" + RELEASE_ARTIFACT_PATH_REGEX + ")$).*"; //$NON-NLS-1$ //$NON-NLS-2$

    private static final Pattern RELEASE_PATH =
            Pattern.compile(RELEASE_ARTIFACT_PATH_REGEX);

    private GsdShipPathPolicy() {
    }

    /** Rejects absolute, traversal, alias-component and non-release paths. */
    public static boolean isReleaseArtifactPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank() || rawPath.indexOf('\0') >= 0) {
            return false;
        }
        String path = rawPath.replace('\\', '/');
        if (path.startsWith("/") || path.startsWith("//") //$NON-NLS-1$ //$NON-NLS-2$
                || path.matches("^[A-Za-z]:/.*")) { //$NON-NLS-1$
            return false;
        }
        String withoutLeadingDot = path.startsWith("./") ? path.substring(2) : path; //$NON-NLS-1$
        if (withoutLeadingDot.isBlank()) {
            return false;
        }
        for (String segment : withoutLeadingDot.split("/", -1)) { //$NON-NLS-1$
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) { //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }
        return RELEASE_PATH.matcher(path).matches();
    }
}
