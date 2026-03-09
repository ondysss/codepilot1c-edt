package com.codepilot1c.core.evaluation.trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.IPath;

import com.codepilot1c.core.internal.VibeCorePlugin;

/**
 * Canonical filesystem layout for a single evaluation trace run.
 */
public class ArtifactLayout {

    public static final String PROP_TRACE_DIR = "codepilot1c.agent.trace.dir"; //$NON-NLS-1$

    private static final String ROOT_DIR = "agent-runs"; //$NON-NLS-1$
    private static final String RUNS_DIR = "runs"; //$NON-NLS-1$
    private static final String ARTIFACTS_DIR = "artifacts"; //$NON-NLS-1$

    private final Path rootDirectory;
    private final Path runDirectory;
    private final Path artifactsDirectory;

    private ArtifactLayout(Path rootDirectory, Path runDirectory, Path artifactsDirectory) {
        this.rootDirectory = rootDirectory;
        this.runDirectory = runDirectory;
        this.artifactsDirectory = artifactsDirectory;
    }

    public static ArtifactLayout create(String runId) throws IOException {
        Path root = resolveRootDirectory();
        Path runs = root.resolve(RUNS_DIR);
        Path run = runs.resolve(runId);
        Path artifacts = run.resolve(ARTIFACTS_DIR);

        Files.createDirectories(artifacts);
        return new ArtifactLayout(root, run, artifacts);
    }

    private static Path resolveRootDirectory() {
        String override = System.getProperty(PROP_TRACE_DIR);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }

        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin != null) {
            IPath stateLocation = plugin.getStateLocation();
            if (stateLocation != null) {
                return Path.of(stateLocation.toOSString()).resolve(ROOT_DIR);
            }
        }

        return Path.of(System.getProperty("user.home"), ".codepilot1c", ROOT_DIR); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public Path getRunDirectory() {
        return runDirectory;
    }

    public Path getArtifactsDirectory() {
        return artifactsDirectory;
    }

    public Path getRunMetadataFile() {
        return runDirectory.resolve("run.json"); //$NON-NLS-1$
    }

    public Path getEventsFile() {
        return runDirectory.resolve("events.jsonl"); //$NON-NLS-1$
    }

    public Path getLlmFile() {
        return runDirectory.resolve("llm.jsonl"); //$NON-NLS-1$
    }

    public Path getToolsFile() {
        return runDirectory.resolve("tools.jsonl"); //$NON-NLS-1$
    }

    public Path getMcpFile() {
        return runDirectory.resolve("mcp.jsonl"); //$NON-NLS-1$
    }
}
