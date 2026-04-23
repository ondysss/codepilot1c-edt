package com.codepilot1c.core.tools;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

import com.codepilot1c.core.tools.git.GitCloneAndImportProjectTool;
import com.codepilot1c.core.git.GitService;
import com.codepilot1c.core.workspace.WorkspaceProjectImportResult;
import com.codepilot1c.core.workspace.WorkspaceProjectImportService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class GitCloneAndImportProjectToolTest {

    @Test
    public void clonesRepositoryAndCallsImportService() throws Exception {
        assumeGitAvailable();
        Path sourceRepo = Files.createTempDirectory("git-clone-source"); //$NON-NLS-1$
        run(sourceRepo, "git", "init"); //$NON-NLS-1$ //$NON-NLS-2$
        run(sourceRepo, "git", "config", "user.email", "test@example.com"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        run(sourceRepo, "git", "config", "user.name", "Test User"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Files.writeString(sourceRepo.resolve("README.md"), "demo"); //$NON-NLS-1$ //$NON-NLS-2$
        run(sourceRepo, "git", "add", "README.md"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run(sourceRepo, "git", "commit", "-m", "Initial"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        Path cloneTarget = Files.createTempDirectory("git-clone-target-parent").resolve("demo-repo"); //$NON-NLS-1$ //$NON-NLS-2$
        GitCloneAndImportProjectTool tool = new GitCloneAndImportProjectTool(new GitService(),
                new WorkspaceProjectImportService() {
                    @Override
                    public WorkspaceProjectImportResult importProject(Path projectPath, boolean openProject,
                            boolean refreshProject) {
                        return new WorkspaceProjectImportResult("Demo", projectPath.toString(), true, openProject, refreshProject); //$NON-NLS-1$
                    }
                });

        ToolResult result = tool.execute(Map.of(
                "remote_url", sourceRepo.toString(), //$NON-NLS-1$
                "repo_path", cloneTarget.toString() //$NON-NLS-1$
        )).join();

        assertTrue(result.isSuccess());
        assertTrue(Files.exists(cloneTarget.resolve(".git"))); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue("completed".equals(json.get("status").getAsString())); //$NON-NLS-1$ //$NON-NLS-2$
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
