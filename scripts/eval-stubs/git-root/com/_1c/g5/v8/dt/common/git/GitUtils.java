package com._1c.g5.v8.dt.common.git;

import org.eclipse.core.runtime.IPath;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/** Headless fixture for an EDT/EGit mapping cached before a nested Git repository existed. */
public final class GitUtils {
    public static Repository getGitRepository(IPath ignored) {
        String root = System.getProperty("codepilot.test.cached.repo");
        if (root == null) return null;
        try {
            return new FileRepositoryBuilder().setGitDir(new java.io.File(root, ".git")).build();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
