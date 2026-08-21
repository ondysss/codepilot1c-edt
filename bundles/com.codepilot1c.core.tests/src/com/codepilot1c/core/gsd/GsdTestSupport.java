/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assume;

import com.codepilot1c.core.filesystem.SecureDirectoryMutation;

/** Explicit provider precondition and secure-directory provisioning for mutation tests. */
public final class GsdTestSupport {

    private GsdTestSupport() {
    }

    public static Path secureProject(Path project) throws IOException {
        Assume.assumeTrue("GSD mutation test requires a real SecureDirectoryStream provider", //$NON-NLS-1$
                SecureDirectoryMutation.supportsSecureDirectoryStreams(project));
        Files.createDirectories(project.resolve(GsdStateStore.GSD_DIR_NAME));
        return project;
    }
}
