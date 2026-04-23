package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

import com.codepilot1c.core.git.GitService;
import com.codepilot1c.core.tools.git.GitInspectTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class GitInspectToolTest {

    @Test
    public void statusReturnsStructuredEntries() throws Exception {
        assumeGitAvailable();
        Path repo = Files.createTempDirectory("git-inspect-status"); //$NON-NLS-1$
        run(repo, "git", "init"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(repo.resolve("README.md"), "demo"); //$NON-NLS-1$ //$NON-NLS-2$
        GitInspectTool tool = new GitInspectTool();

        ToolResult result = tool.execute(Map.of(
                "operation", "status", //$NON-NLS-1$ //$NON-NLS-2$
                "repo_path", repo.toString() //$NON-NLS-1$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        JsonArray entries = json.getAsJsonArray("entries"); //$NON-NLS-1$
        assertFalse(entries.isEmpty());
    }

    @Test
    public void logReturnsCommitEntries() throws Exception {
        assumeGitAvailable();
        Path repo = Files.createTempDirectory("git-inspect-log"); //$NON-NLS-1$
        run(repo, "git", "init"); //$NON-NLS-1$ //$NON-NLS-2$
        run(repo, "git", "config", "user.email", "test@example.com"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        run(repo, "git", "config", "user.name", "Test User"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Files.writeString(repo.resolve("README.md"), "demo"); //$NON-NLS-1$ //$NON-NLS-2$
        run(repo, "git", "add", "README.md"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run(repo, "git", "commit", "-m", "Initial"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        GitInspectTool tool = new GitInspectTool();

        ToolResult result = tool.execute(Map.of(
                "operation", "log", //$NON-NLS-1$ //$NON-NLS-2$
                "repo_path", repo.toString(), //$NON-NLS-1$
                "limit", Integer.valueOf(5) //$NON-NLS-1$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        JsonArray commits = json.getAsJsonArray("commits"); //$NON-NLS-1$
        assertFalse(commits.isEmpty());
    }

    @Test
    public void statusResolvesDotAgainstCurrentSessionProjectAndReturnsRepositoryRoot() throws Exception {
        assumeGitAvailable();
        Path repo = Files.createTempDirectory("git-inspect-session"); //$NON-NLS-1$
        Path project = Files.createDirectories(repo.resolve("project")); //$NON-NLS-1$
        run(repo, "git", "init"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(project.resolve(".project"), "<projectDescription/>"); //$NON-NLS-1$ //$NON-NLS-2$
        GitInspectTool tool = new GitInspectTool(new GitService(() -> project));

        ToolResult result = tool.execute(Map.of(
                "operation", "status", //$NON-NLS-1$ //$NON-NLS-2$
                "repo_path", "." //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.has("repo_path")); //$NON-NLS-1$
        assertEquals(repo.toRealPath().toString(), Path.of(json.get("repo_path").getAsString()).toRealPath().toString()); //$NON-NLS-1$
    }

    @Test
    public void statusWithoutRepoPathUsesContextProjectAndReturnsProjectMetadata() throws Exception {
        assumeGitAvailable();
        Path repo = Files.createTempDirectory("git-inspect-context"); //$NON-NLS-1$
        Path project = Files.createDirectories(repo.resolve("project")); //$NON-NLS-1$
        run(repo, "git", "init"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(project.resolve(".project"), "<projectDescription/>"); //$NON-NLS-1$ //$NON-NLS-2$
        GitInspectTool tool = new GitInspectTool(new GitService(() -> project));

        ToolResult result = tool.execute(Map.of(
                "operation", "status" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals(repo.toRealPath().toString(), Path.of(json.get("repo_root").getAsString()).toRealPath().toString()); //$NON-NLS-1$
        assertEquals(project.toRealPath().toString(), Path.of(json.get("project_path").getAsString()).toRealPath().toString()); //$NON-NLS-1$
        assertEquals("context_project_path", json.get("resolution_source").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assumeGitAvailable() throws Exception {
        try {
            run(null, "git", "--version"); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (IOException e) {
            Assume.assumeNoException(e);
        }
    }

    private static String run(Path cwd, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        Process process = builder.start();
        int exitCode = process.waitFor();
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        if (exitCode != 0) {
            throw new IOException(stderr.isBlank() ? stdout : stderr);
        }
        return stdout;
    }
}
