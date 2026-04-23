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

@SuppressWarnings("nls")
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

    @Test
    public void commitWithoutMessageFailsWithClearError() {
        GitMutateTool tool = new GitMutateTool();

        ToolResult result = tool.execute(Map.of(
                "operation", "commit", //$NON-NLS-1$ //$NON-NLS-2$
                "repo_path", "/tmp/does-not-matter" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertFalse(result.isSuccess());
        assertTrue("expected INVALID_ARGUMENT on missing message, got: " + result.getErrorMessage(), //$NON-NLS-1$
                result.getErrorMessage().contains("INVALID_ARGUMENT")); //$NON-NLS-1$
        assertTrue("expected 'message is required' in error, got: " + result.getErrorMessage(), //$NON-NLS-1$
                result.getErrorMessage().contains("message is required")); //$NON-NLS-1$
    }

    @Test
    public void cloneWithoutRemoteUrlFailsWithClearError() throws Exception {
        Path repo = Files.createTempDirectory("git-mutate-clone-missing-url"); //$NON-NLS-1$
        GitMutateTool tool = new GitMutateTool();

        ToolResult result = tool.execute(Map.of(
                "operation", "clone", //$NON-NLS-1$ //$NON-NLS-2$
                "repo_path", repo.toString() //$NON-NLS-1$
        )).join();

        assertFalse(result.isSuccess());
        assertTrue("expected 'remote_url is required' in error, got: " + result.getErrorMessage(), //$NON-NLS-1$
                result.getErrorMessage().contains("remote_url is required")); //$NON-NLS-1$
    }

    @Test
    public void checkoutWithoutBranchFailsWithClearError() throws Exception {
        Path repo = Files.createTempDirectory("git-mutate-checkout-missing-branch"); //$NON-NLS-1$
        GitMutateTool tool = new GitMutateTool();

        ToolResult result = tool.execute(Map.of(
                "operation", "checkout", //$NON-NLS-1$ //$NON-NLS-2$
                "repo_path", repo.toString() //$NON-NLS-1$
        )).join();

        assertFalse(result.isSuccess());
        assertTrue("expected 'branch is required' in error, got: " + result.getErrorMessage(), //$NON-NLS-1$
                result.getErrorMessage().contains("branch is required")); //$NON-NLS-1$
    }

    @Test
    public void remoteAddWithoutRepoContextFailsWithClearError() {
        GitMutateTool tool = new GitMutateTool();

        ToolResult result = tool.execute(Map.of(
                "operation", "remote_add", //$NON-NLS-1$ //$NON-NLS-2$
                "remote_url", "https://example.com/demo.git" //$NON-NLS-1$ //$NON-NLS-2$
        )).join();

        assertFalse(result.isSuccess());
        assertTrue("expected 'project_name or repo_path is required' in error, got: " + result.getErrorMessage(), //$NON-NLS-1$
                result.getErrorMessage().contains("project_name or repo_path is required")); //$NON-NLS-1$
    }

    @Test
    public void publishedSchemaHasNoTopLevelCompositionKeywords() {
        GitMutateTool tool = new GitMutateTool();

        JsonObject schema = JsonParser.parseString(tool.getParameterSchema()).getAsJsonObject();

        assertFalse("schema must not contain top-level allOf (Anthropic API rejects it)", schema.has("allOf")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("schema must not contain top-level anyOf (Anthropic API rejects it)", schema.has("anyOf")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("schema must not contain top-level oneOf (Anthropic API rejects it)", schema.has("oneOf")); //$NON-NLS-1$ //$NON-NLS-2$
        // Ensure we kept the baseline shape intact.
        assertTrue(schema.has("required")); //$NON-NLS-1$
        assertTrue(schema.has("additionalProperties")); //$NON-NLS-1$
        assertFalse(schema.get("additionalProperties").getAsBoolean()); //$NON-NLS-1$
        assertEquals("operation", schema.getAsJsonArray("required").get(0).getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
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
