package com.codepilot1c.core.git;

import java.nio.file.Path;

/**
 * Resolved Git execution context derived from tool arguments and EDT workspace state.
 */
final class GitContextResolution {

    private final String projectName;
    private final Path projectPath;
    private final Path candidatePath;
    private final Path repoRoot;
    private final String resolutionSource;

    GitContextResolution(String projectName, Path projectPath, Path candidatePath, Path repoRoot, String resolutionSource) {
        this.projectName = projectName;
        this.projectPath = projectPath;
        this.candidatePath = candidatePath;
        this.repoRoot = repoRoot;
        this.resolutionSource = resolutionSource;
    }

    String projectName() {
        return projectName;
    }

    Path projectPath() {
        return projectPath;
    }

    Path candidatePath() {
        return candidatePath;
    }

    Path repoRoot() {
        return repoRoot;
    }

    String resolutionSource() {
        return resolutionSource;
    }

    GitContextResolution withRepoRoot(Path resolvedRepoRoot) {
        return new GitContextResolution(projectName, projectPath, candidatePath, resolvedRepoRoot, resolutionSource);
    }
}
