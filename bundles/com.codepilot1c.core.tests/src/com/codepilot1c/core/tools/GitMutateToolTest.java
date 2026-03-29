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
import com.codepilot1c.core.tools.git.GitMutateTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class GitMutateToolTest {

    @Test
    public void initCreatesRepository() throws Exception {
        assumeGitAvailable();
        Path repo = Files.createTempDirectory("git-mutate-init"); //$NON-NLS-1$
        GitMutateTool tool = new GitMutateTool();

        ToolResult result = tool.execute(Map.of(
                "operation", "init", //$NON-NLS-1$ //$NON-NLS-2$
                "repo_path", repo.toString() //$NON-NLS-1$
        )).join();

        assertTrue(result.isSuccess());
        assertTrue(Files.exists(repo.resolve(".git"))); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue((json.has("completed") && json.get("completed").getAsBoolean()) //$NON-NLS-1$ //$NON-NLS-2$
                || (json.has("initialized") && json.get("initialized").getAsBoolean())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createRepoAliasCreatesRepository() throws Exception {
        assumeGitAvailable();
        Path repo = Files.createTempDirectory("git-mutate-create"); //$NON-NLS-1$
        GitMutateTool tool = new GitMutateTool();

        ToolResult result = tool.execute(Map.of(
                "operation", "create_repo", //$NON-NLS-1$ //$NON-NLS-2$
                "repo_path", repo.toString(), //$NON-NLS-1$
                "initial_branch", "main" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertTrue(result.isSuccess());
        assertTrue(Files.exists(repo.resolve(".git"))); //$NON-NLS-1$
    }

    @Test
    public void initWithoutRepoPathFailsFastWithClearError() {
        GitMutateTool tool = new GitMutateTool();

        ToolResult result = tool.execute(Map.of(
                "operation", "init" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("repo_path is required")); //$NON-NLS-1$
    }

    @Test
    public void remoteAddAddsOrigin() throws Exception {
        assumeGitAvailable();
        Path repo = Files.createTempDirectory("git-mutate-remote"); //$NON-NLS-1$
        run(repo, "git", "init"); //$NON-NLS-1$ //$NON-NLS-2$
        GitMutateTool tool = new GitMutateTool();

        ToolResult result = tool.execute(Map.of(
                "operation", "remote_add", //$NON-NLS-1$ //$NON-NLS-2$
                "repo_path", repo.toString(), //$NON-NLS-1$
                "remote_name", "origin", //$NON-NLS-1$ //$NON-NLS-2$
                "remote_url", "https://example.com/demo.git" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertTrue(result.isSuccess());
        String remotes = run(repo, "git", "remote", "-v"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(remotes.contains("origin")); //$NON-NLS-1$
    }

    @Test
    public void remoteAddUsesContextProjectWhenRepoPathIsOmitted() throws Exception {
        assumeGitAvailable();
        Path repo = Files.createTempDirectory("git-mutate-context"); //$NON-NLS-1$
        Path project = Files.createDirectories(repo.resolve("project")); //$NON-NLS-1$
        run(repo, "git", "init"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(project.resolve(".project"), "<projectDescription/>"); //$NON-NLS-1$ //$NON-NLS-2$
        GitMutateTool tool = new GitMutateTool(new GitService(() -> project));

        ToolResult result = tool.execute(Map.of(
                "operation", "remote_add", //$NON-NLS-1$ //$NON-NLS-2$
                "remote_name", "origin", //$NON-NLS-1$ //$NON-NLS-2$
                "remote_url", "https://example.com/demo.git" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals(repo.toRealPath().toString(), Path.of(json.get("repo_root").getAsString()).toRealPath().toString()); //$NON-NLS-1$
        assertTrue(run(repo, "git", "remote", "-v").contains("origin")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
