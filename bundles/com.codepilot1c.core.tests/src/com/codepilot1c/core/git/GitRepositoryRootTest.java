package com.codepilot1c.core.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonObject;

/** Actual Git operations; the eval runner supplies an EDT cache with a stale parent mapping. */
public class GitRepositoryRootTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path parent;
    private Path child;
    private GitService service;

    @Before public void prepare() throws Exception {
        parent = temporary.newFolder("parent").toPath();
        git(parent, "init");
        child = Files.createDirectories(parent.resolve("nested"));
        git(child, "init");
        System.setProperty("codepilot.test.cached.repo", parent.toString());
        service = new GitService(() -> child);
    }

    @After public void clearCacheFixture() {
        System.clearProperty("codepilot.test.cached.repo");
    }

    @Test public void nestedRepositoryWinsOverCachedParent() throws Exception {
        assertRoot(child, child);
    }

    @Test public void nestedSubdirectoryResolvesNearestRepository() throws Exception {
        assertRoot(Files.createDirectories(child.resolve("src/modules")), child);
    }

    @Test public void relativePathUsesContextProject() throws Exception {
        assertRoot(Path.of("."), child);
    }

    @Test public void projectWithoutOwnRepositoryUsesParent() throws Exception {
        assertRoot(Files.createDirectories(parent.resolve("other-project/src")), parent);
    }

    @Test public void linkedWorktreeGitFileResolvesItsOwnRoot() throws Exception {
        git(child, "config", "user.name", "Test User");
        git(child, "config", "user.email", "test@example.com");
        Files.writeString(child.resolve("seed.txt"), "seed");
        git(child, "add", "seed.txt");
        git(child, "commit", "-m", "Seed");
        Path worktree = parent.resolve("linked");
        git(child, "worktree", "add", "-b", "linked", worktree.toString());
        assertTrue(Files.isRegularFile(worktree.resolve(".git")));
        assertRoot(worktree, worktree);
    }

    @Test public void mutationStagesOnlyTheSelectedRepository() throws Exception {
        Files.writeString(child.resolve("selected.txt"), "nested");
        Files.writeString(parent.resolve("selected.txt"), "parent");
        service.mutate("root-test-add", GitOperation.ADD, child, Map.of("paths", java.util.List.of("selected.txt")));
        assertEquals("selected.txt", git(child, "diff", "--cached", "--name-only").trim());
        assertEquals("", git(parent, "diff", "--cached", "--name-only").trim());
    }

    @Test public void unrelatedDirectoryRejectsCachedRepository() throws Exception {
        Path outside = temporary.newFolder("outside").toPath();
        try {
            service.inspect("root-test-invalid", GitOperation.STATUS, outside, Map.of());
            fail("A stale cache must not authorize Git operations on another repository");
        } catch (GitToolException expected) {
            assertTrue(expected.getMessage().contains("not a git repository"));
        }
    }

    private void assertRoot(Path input, Path expected) throws Exception {
        JsonObject result = service.inspect("root-test", GitOperation.STATUS, input, Map.of());
        Path actual = Path.of(result.get("repo_root").getAsString());
        assertEquals(expected.toRealPath(), actual.toRealPath());
    }

    private static String git(Path directory, String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor());
        return output;
    }
}
