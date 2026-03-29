package com.codepilot1c.core.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jgit.lib.Repository;

import com._1c.g5.v8.dt.common.git.GitUtils;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.session.SessionManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Structured wrapper around a small allowlisted subset of Git CLI operations.
 */
public class GitService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GitService.class);
    private static final int INSPECT_TIMEOUT_SECONDS = 30;
    private static final int MUTATE_TIMEOUT_SECONDS = 300;
    private static final int OUTPUT_TAIL_LIMIT = 4000;
    private final Supplier<Path> contextProjectPathResolver;
    private final Supplier<String> contextProjectNameResolver;
    private final GitContextResolver contextResolver;

    public GitService() {
        this(GitService::resolveDefaultContextProjectPath, GitService::resolveDefaultContextProjectName);
    }

    public GitService(Supplier<Path> contextProjectPathResolver) {
        this(contextProjectPathResolver, () -> null);
    }

    public GitService(Supplier<Path> contextProjectPathResolver, Supplier<String> contextProjectNameResolver) {
        this.contextProjectPathResolver = contextProjectPathResolver;
        this.contextProjectNameResolver = contextProjectNameResolver;
        this.contextResolver = new GitContextResolver(this.contextProjectPathResolver, this.contextProjectNameResolver);
    }

    public JsonObject inspect(String opId, GitOperation operation, Path repoPath, Map<String, Object> parameters) {
        GitContextResolution context = requireRepositoryContext(repoPath, asOptionalString(parameters.get("project_name"))); //$NON-NLS-1$
        return switch (operation) {
            case STATUS -> inspectStatus(opId, context);
            case BRANCH_LIST -> inspectBranches(opId, context);
            case REMOTE_LIST -> inspectRemotes(opId, context);
            case LOG -> inspectLog(opId, context, asPositiveInt(parameters.get("limit"), 20)); //$NON-NLS-1$
            case DIFF_SUMMARY -> inspectDiffSummary(opId, context,
                    asOptionalString(parameters.get("base_ref")), asOptionalString(parameters.get("head_ref"))); //$NON-NLS-1$ //$NON-NLS-2$
            default -> throw new GitToolException(GitErrorCode.INVALID_ARGUMENT,
                    "Operation is not supported by git_inspect: " + operation); //$NON-NLS-1$
        };
    }

    public JsonObject mutate(String opId, GitOperation operation, Path repoPath, Map<String, Object> parameters) {
        return switch (operation) {
            case INIT -> mutateInit(opId, repoPath, asOptionalString(parameters.get("initial_branch"))); //$NON-NLS-1$
            case CLONE -> mutateClone(opId, repoPath, requireString(parameters.get("remote_url")), //$NON-NLS-1$
                    asOptionalString(parameters.get("branch"))); //$NON-NLS-1$
            case REMOTE_ADD -> mutateRemoteAdd(opId, requireRepositoryContext(repoPath, asOptionalString(parameters.get("project_name"))), //$NON-NLS-1$
                    defaultString(parameters.get("remote_name"), "origin"), requireString(parameters.get("remote_url"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case REMOTE_SET_URL -> mutateRemoteSetUrl(opId, requireRepositoryContext(repoPath, asOptionalString(parameters.get("project_name"))), //$NON-NLS-1$
                    defaultString(parameters.get("remote_name"), "origin"), requireString(parameters.get("remote_url"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case FETCH -> mutateSimpleRepoCommand(opId, operation,
                    requireRepositoryContext(repoPath, asOptionalString(parameters.get("project_name"))), //$NON-NLS-1$
                    GitCommandBuilder.create().args("fetch", defaultString(parameters.get("remote_name"), "origin")).build()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case PULL -> mutatePull(opId, requireRepositoryContext(repoPath, asOptionalString(parameters.get("project_name"))), //$NON-NLS-1$
                    defaultString(parameters.get("remote_name"), "origin"), asOptionalString(parameters.get("branch"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case PUSH -> mutatePush(opId, requireRepositoryContext(repoPath, asOptionalString(parameters.get("project_name"))), //$NON-NLS-1$
                    defaultString(parameters.get("remote_name"), "origin"), asOptionalString(parameters.get("branch")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    Boolean.TRUE.equals(parameters.get("set_upstream"))); //$NON-NLS-1$
            case CHECKOUT -> mutateSimpleRepoCommand(opId, operation,
                    requireRepositoryContext(repoPath, asOptionalString(parameters.get("project_name"))), //$NON-NLS-1$
                    GitCommandBuilder.create().args("checkout", requireString(parameters.get("branch"))).build()); //$NON-NLS-1$ //$NON-NLS-2$
            case CREATE_BRANCH -> mutateCreateBranch(opId,
                    requireRepositoryContext(repoPath, asOptionalString(parameters.get("project_name"))), //$NON-NLS-1$
                    requireString(parameters.get("branch")), //$NON-NLS-1$
                    asOptionalString(parameters.get("start_point")), Boolean.TRUE.equals(parameters.get("checkout"))); //$NON-NLS-1$ //$NON-NLS-2$
            case ADD -> mutateAdd(opId, requireRepositoryContext(repoPath, asOptionalString(parameters.get("project_name"))), parameters.get("paths")); //$NON-NLS-1$ //$NON-NLS-2$
            case COMMIT -> mutateSimpleRepoCommand(opId, operation,
                    requireRepositoryContext(repoPath, asOptionalString(parameters.get("project_name"))), //$NON-NLS-1$
                    GitCommandBuilder.create().args("commit", "-m", requireString(parameters.get("message"))).build()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> throw new GitToolException(GitErrorCode.INVALID_ARGUMENT,
                    "Operation is not supported by git_mutate: " + operation); //$NON-NLS-1$
        };
    }

    private JsonObject inspectStatus(String opId, GitContextResolution context) {
        CommandOutput output = runRepoCommand(opId, context, INSPECT_TIMEOUT_SECONDS,
                GitCommandBuilder.create()
                        .args("status", "--short", "--branch", "--untracked-files=no", "--no-ahead-behind") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                        .build());
        JsonObject json = basePayload(opId, GitOperation.STATUS, context, output);
        JsonArray lines = new JsonArray();
        for (String line : splitLines(output.stdout())) {
            lines.add(line);
        }
        json.add("entries", lines); //$NON-NLS-1$
        return json;
    }

    private JsonObject inspectBranches(String opId, GitContextResolution context) {
        CommandOutput output = runRepoCommand(opId, context, INSPECT_TIMEOUT_SECONDS,
                GitCommandBuilder.create().args("branch", "--format=%(refname:short)\t%(HEAD)\t%(upstream:short)\t%(objectname:short)").build()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject json = basePayload(opId, GitOperation.BRANCH_LIST, context, output);
        JsonArray branches = new JsonArray();
        for (String line : splitLines(output.stdout())) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1); //$NON-NLS-1$
            JsonObject branch = new JsonObject();
            branch.addProperty("name", part(parts, 0)); //$NON-NLS-1$
            branch.addProperty("current", "*".equals(part(parts, 1))); //$NON-NLS-1$ //$NON-NLS-2$
            branch.addProperty("upstream", part(parts, 2)); //$NON-NLS-1$
            branch.addProperty("commit", part(parts, 3)); //$NON-NLS-1$
            branches.add(branch);
        }
        json.add("branches", branches); //$NON-NLS-1$
        return json;
    }

    private JsonObject inspectRemotes(String opId, GitContextResolution context) {
        CommandOutput output = runRepoCommand(opId, context, INSPECT_TIMEOUT_SECONDS,
                GitCommandBuilder.create().args("remote", "-v").build()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject json = basePayload(opId, GitOperation.REMOTE_LIST, context, output);
        JsonArray remotes = new JsonArray();
        for (String line : splitLines(output.stdout())) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+"); //$NON-NLS-1$
            JsonObject remote = new JsonObject();
            remote.addProperty("name", part(parts, 0)); //$NON-NLS-1$
            remote.addProperty("url", part(parts, 1)); //$NON-NLS-1$
            String direction = part(parts, 2);
            remote.addProperty("direction", direction.startsWith("(") && direction.endsWith(")") //$NON-NLS-1$ //$NON-NLS-2$
                    ? direction.substring(1, direction.length() - 1)
                    : direction);
            remotes.add(remote);
        }
        json.add("remotes", remotes); //$NON-NLS-1$
        return json;
    }

    private JsonObject inspectLog(String opId, GitContextResolution context, int limit) {
        CommandOutput output = runRepoCommand(opId, context, INSPECT_TIMEOUT_SECONDS,
                GitCommandBuilder.create()
                        .args("log", "--date=iso-strict", "--pretty=format:%H\t%h\t%an\t%ad\t%s", "-n", String.valueOf(limit)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        .build());
        JsonObject json = basePayload(opId, GitOperation.LOG, context, output);
        JsonArray commits = new JsonArray();
        for (String line : splitLines(output.stdout())) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 5); //$NON-NLS-1$
            JsonObject commit = new JsonObject();
            commit.addProperty("hash", part(parts, 0)); //$NON-NLS-1$
            commit.addProperty("short_hash", part(parts, 1)); //$NON-NLS-1$
            commit.addProperty("author", part(parts, 2)); //$NON-NLS-1$
            commit.addProperty("date", part(parts, 3)); //$NON-NLS-1$
            commit.addProperty("subject", part(parts, 4)); //$NON-NLS-1$
            commits.add(commit);
        }
        json.addProperty("limit", limit); //$NON-NLS-1$
        json.add("commits", commits); //$NON-NLS-1$
        return json;
    }

    private JsonObject inspectDiffSummary(String opId, GitContextResolution context, String baseRef, String headRef) {
        GitCommandBuilder builder = GitCommandBuilder.create().args("diff", "--stat"); //$NON-NLS-1$ //$NON-NLS-2$
        if (baseRef != null && !baseRef.isBlank()) {
            builder.arg(baseRef);
        }
        if (headRef != null && !headRef.isBlank()) {
            builder.arg(headRef);
        }
        CommandOutput output = runRepoCommand(opId, context, INSPECT_TIMEOUT_SECONDS, builder.build());
        JsonObject json = basePayload(opId, GitOperation.DIFF_SUMMARY, context, output);
        json.addProperty("base_ref", baseRef == null ? "" : baseRef); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("head_ref", headRef == null ? "" : headRef); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray lines = new JsonArray();
        for (String line : splitLines(output.stdout())) {
            lines.add(line);
        }
        json.add("summary_lines", lines); //$NON-NLS-1$
        return json;
    }

    private JsonObject mutateInit(String opId, Path repoPath, String initialBranch) {
        if (repoPath == null) {
            throw new GitToolException(GitErrorCode.INVALID_ARGUMENT, "repo_path is required"); //$NON-NLS-1$
        }
        try {
            Files.createDirectories(repoPath);
        } catch (IOException e) {
            throw new GitToolException(GitErrorCode.REPOSITORY_NOT_FOUND,
                    "Failed to create repository directory: " + repoPath, e); //$NON-NLS-1$
        }
        GitCommandBuilder builder = GitCommandBuilder.create().arg("init"); //$NON-NLS-1$
        if (initialBranch != null && !initialBranch.isBlank()) {
            builder.args("-b", initialBranch); //$NON-NLS-1$
        }
        CommandOutput output = runCommand(opId, repoPath, MUTATE_TIMEOUT_SECONDS, builder.build());
        JsonObject json = basePayload(opId, GitOperation.INIT, repoPath, output);
        json.addProperty("initialized", true); //$NON-NLS-1$
        return json;
    }

    private JsonObject mutateClone(String opId, Path repoPath, String remoteUrl, String branch) {
        if (repoPath == null) {
            throw new GitToolException(GitErrorCode.INVALID_ARGUMENT, "repo_path is required for clone"); //$NON-NLS-1$
        }
        Path parent = repoPath.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new GitToolException(GitErrorCode.INVALID_ARGUMENT,
                    "repo_path parent directory is required for clone"); //$NON-NLS-1$
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new GitToolException(GitErrorCode.REPOSITORY_NOT_FOUND,
                    "Failed to create parent directory: " + parent, e); //$NON-NLS-1$
        }
        GitCommandBuilder builder = GitCommandBuilder.create().arg("clone"); //$NON-NLS-1$
        if (branch != null && !branch.isBlank()) {
            builder.args("--branch", branch); //$NON-NLS-1$
        }
        builder.arg(remoteUrl).arg(repoPath.getFileName().toString());
        CommandOutput output = runCommand(opId, parent, MUTATE_TIMEOUT_SECONDS, builder.build());
        JsonObject json = basePayload(opId, GitOperation.CLONE, repoPath, output);
        json.addProperty("remote_url", remoteUrl); //$NON-NLS-1$
        return json;
    }

    private JsonObject mutateRemoteAdd(String opId, GitContextResolution context, String remoteName, String remoteUrl) {
        ensureRemoteAbsent(opId, context, remoteName);
        return mutateSimpleRepoCommand(opId, GitOperation.REMOTE_ADD, context,
                GitCommandBuilder.create().args("remote", "add", remoteName, remoteUrl).build()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private JsonObject mutateRemoteSetUrl(String opId, GitContextResolution context, String remoteName, String remoteUrl) {
        return mutateSimpleRepoCommand(opId, GitOperation.REMOTE_SET_URL, context,
                GitCommandBuilder.create().args("remote", "set-url", remoteName, remoteUrl).build()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private JsonObject mutatePull(String opId, GitContextResolution context, String remoteName, String branch) {
        GitCommandBuilder builder = GitCommandBuilder.create().args("pull", remoteName); //$NON-NLS-1$ //$NON-NLS-2$
        if (branch != null && !branch.isBlank()) {
            builder.arg(branch);
        }
        return mutateSimpleRepoCommand(opId, GitOperation.PULL, context, builder.build());
    }

    private JsonObject mutatePush(String opId, GitContextResolution context, String remoteName, String branch, boolean setUpstream) {
        GitCommandBuilder builder = GitCommandBuilder.create().arg("push"); //$NON-NLS-1$
        if (setUpstream) {
            builder.arg("--set-upstream"); //$NON-NLS-1$
        }
        builder.arg(remoteName);
        if (branch != null && !branch.isBlank()) {
            builder.arg(branch);
        }
        return mutateSimpleRepoCommand(opId, GitOperation.PUSH, context, builder.build());
    }

    private JsonObject mutateCreateBranch(String opId, GitContextResolution context, String branch, String startPoint,
            boolean checkout) {
        GitCommandBuilder builder = GitCommandBuilder.create();
        if (checkout) {
            builder.args("checkout", "-b", branch); //$NON-NLS-1$ //$NON-NLS-2$
        } else {
            builder.args("branch", branch); //$NON-NLS-1$
        }
        if (startPoint != null && !startPoint.isBlank()) {
            builder.arg(startPoint);
        }
        return mutateSimpleRepoCommand(opId, GitOperation.CREATE_BRANCH, context, builder.build());
    }

    private JsonObject mutateAdd(String opId, GitContextResolution context, Object pathsValue) {
        GitCommandBuilder builder = GitCommandBuilder.create().arg("add"); //$NON-NLS-1$
        List<String> paths = normalizePaths(pathsValue);
        if (paths.isEmpty()) {
            builder.arg("."); //$NON-NLS-1$
        } else {
            for (String path : paths) {
                builder.arg(path);
            }
        }
        return mutateSimpleRepoCommand(opId, GitOperation.ADD, context, builder.build());
    }

    private JsonObject mutateSimpleRepoCommand(String opId, GitOperation operation, GitContextResolution context, List<String> command) {
        CommandOutput output = runRepoCommand(opId, context, MUTATE_TIMEOUT_SECONDS, command);
        JsonObject json = basePayload(opId, operation, context, output);
        json.addProperty("completed", true); //$NON-NLS-1$
        return json;
    }

    private void ensureRemoteAbsent(String opId, GitContextResolution context, String remoteName) {
        JsonObject remotes = inspectRemotes(opId, context);
        JsonArray items = remotes.getAsJsonArray("remotes"); //$NON-NLS-1$
        for (int i = 0; i < items.size(); i++) {
            JsonObject remote = items.get(i).getAsJsonObject();
            if (remoteName.equals(remote.get("name").getAsString())) { //$NON-NLS-1$
                throw new GitToolException(GitErrorCode.REMOTE_ALREADY_EXISTS,
                        "Git remote already exists: " + remoteName); //$NON-NLS-1$
            }
        }
    }

    private CommandOutput runRepoCommand(String opId, GitContextResolution context, int timeoutSeconds, List<String> command) {
        return runCommand(opId, context.repoRoot(), timeoutSeconds, command);
    }

    private CommandOutput runCommand(String opId, Path cwd, int timeoutSeconds, List<String> command) {
        LOG.info("[%s] git command cwd=%s command=%s", opId, cwd, command); //$NON-NLS-1$
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd == null ? null : cwd.toFile());
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new GitToolException(GitErrorCode.TIMEOUT,
                        "Git command timed out after " + timeoutSeconds + " seconds"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            CommandOutput output = new CommandOutput(command, process.exitValue(), stdout, stderr);
            if (output.exitCode() != 0) {
                throw classifyCommandFailure(output);
            }
            return output;
        } catch (GitToolException e) {
            throw e;
        } catch (IOException e) {
            throw new GitToolException(GitErrorCode.GIT_EXECUTABLE_NOT_FOUND,
                    "Failed to start git executable: " + e.getMessage(), e); //$NON-NLS-1$
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitToolException(GitErrorCode.TIMEOUT, "Git command interrupted", e); //$NON-NLS-1$
        }
    }

    private GitToolException classifyCommandFailure(CommandOutput output) {
        String stderr = output.stderr() == null ? "" : output.stderr(); //$NON-NLS-1$
        String stdout = output.stdout() == null ? "" : output.stdout(); //$NON-NLS-1$
        String combined = stderr + "\n" + stdout; //$NON-NLS-1$
        if (combined.contains("Authentication failed") || combined.contains("Permission denied") //$NON-NLS-1$ //$NON-NLS-2$
                || combined.contains("could not read Username")) { //$NON-NLS-1$
            return new GitToolException(GitErrorCode.AUTH_FAILED, truncate(combined));
        }
        if (combined.contains("not a git repository")) { //$NON-NLS-1$
            return new GitToolException(GitErrorCode.NOT_A_GIT_REPOSITORY, truncate(combined));
        }
        if (combined.contains("remote") && combined.contains("already exists")) { //$NON-NLS-1$ //$NON-NLS-2$
            return new GitToolException(GitErrorCode.REMOTE_ALREADY_EXISTS, truncate(combined));
        }
        return new GitToolException(GitErrorCode.COMMAND_FAILED, truncate(combined));
    }

    private GitContextResolution requireRepositoryContext(Path repoPath, String projectName) {
        GitContextResolution context = contextResolver.resolveRepositoryContext(repoPath, projectName);
        Path normalized = context.candidatePath();
        if (normalized == null) {
            throw new GitToolException(GitErrorCode.INVALID_ARGUMENT,
                    "Failed to resolve git repository path from project context"); //$NON-NLS-1$
        }
        if (!Files.isDirectory(normalized)) {
            throw new GitToolException(GitErrorCode.REPOSITORY_NOT_FOUND, "Repository path not found: " + normalized); //$NON-NLS-1$
        }
        Path resolvedRoot = resolveViaEdtGitUtils(normalized);
        if (resolvedRoot != null) {
            return context.withRepoRoot(resolvedRoot);
        }
        Path gitDir = normalized.resolve(".git"); //$NON-NLS-1$
        if (!Files.exists(gitDir)) {
            CommandOutput probe = runCommand("git-probe", normalized, INSPECT_TIMEOUT_SECONDS, //$NON-NLS-1$
                    GitCommandBuilder.create().args("rev-parse", "--is-inside-work-tree").build()); //$NON-NLS-1$ //$NON-NLS-2$
            if (!probe.stdout().trim().equals("true")) { //$NON-NLS-1$
                throw new GitToolException(GitErrorCode.NOT_A_GIT_REPOSITORY,
                        "Not a git repository: " + normalized); //$NON-NLS-1$
            }
            Path topLevel = resolveGitTopLevel(normalized);
            if (topLevel != null) {
                return context.withRepoRoot(topLevel);
            }
        }
        return context.withRepoRoot(normalized);
    }

    private static Path resolveDefaultContextProjectPath() {
        try {
            Session session = SessionManager.getInstance().getCurrentSession();
            if (session != null) {
                Path sessionPath = asExistingDirectory(session.getProjectPath());
                if (sessionPath != null) {
                    return sessionPath;
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("Git session lookup failed: %s", e.getMessage()); //$NON-NLS-1$
        }
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            if (workspace == null) {
                return null;
            }
            IWorkspaceRoot root = workspace.getRoot();
            if (root == null) {
                return null;
            }
            Path singleProjectPath = null;
            for (IProject project : root.getProjects()) {
                if (project == null || !project.exists() || !project.isOpen() || project.getLocation() == null) {
                    continue;
                }
                Path candidate = asExistingDirectory(project.getLocation().toOSString());
                if (candidate == null) {
                    continue;
                }
                if (singleProjectPath != null) {
                    return null;
                }
                singleProjectPath = candidate;
            }
            return singleProjectPath;
        } catch (RuntimeException e) {
            LOG.debug("Git workspace project lookup failed: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static String resolveDefaultContextProjectName() {
        try {
            Session session = SessionManager.getInstance().getCurrentSession();
            return session == null ? null : session.getProjectName();
        } catch (RuntimeException e) {
            LOG.debug("Git session name lookup failed: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private Path resolveViaEdtGitUtils(Path candidatePath) {
        try {
            Repository repository = GitUtils.getGitRepository(
                    org.eclipse.core.runtime.Path.fromOSString(candidatePath.toString()));
            if (repository == null || repository.getWorkTree() == null) {
                return null;
            }
            return repository.getWorkTree().toPath().toAbsolutePath().normalize();
        } catch (RuntimeException | LinkageError e) {
            LOG.debug("EDT GitUtils repository resolution failed for %s: %s", candidatePath, e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private Path resolveGitTopLevel(Path repositoryPath) {
        try {
            CommandOutput output = runCommand("git-top-level", repositoryPath, INSPECT_TIMEOUT_SECONDS, //$NON-NLS-1$
                    GitCommandBuilder.create().args("rev-parse", "--show-toplevel").build()); //$NON-NLS-1$ //$NON-NLS-2$
            String topLevel = output.stdout().trim();
            return topLevel.isEmpty() ? null : Path.of(topLevel).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            LOG.debug("Git top-level resolution failed for %s: %s", repositoryPath, e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static Path asExistingDirectory(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            return Files.isDirectory(candidate) ? candidate : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonObject basePayload(String opId, GitOperation operation, Path repoPath, CommandOutput output) {
        Path normalized = repoPath == null ? null : repoPath.toAbsolutePath().normalize();
        return basePayload(opId, operation,
                new GitContextResolution(null, null, normalized, normalized, "explicit_repo_path"), output); //$NON-NLS-1$
    }

    private JsonObject basePayload(String opId, GitOperation operation, GitContextResolution context, CommandOutput output) {
        JsonObject json = new JsonObject();
        json.addProperty("op_id", opId); //$NON-NLS-1$
        json.addProperty("operation", operation.name().toLowerCase()); //$NON-NLS-1$
        json.addProperty("status", "ok"); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("repo_path", safePath(context == null ? null : context.repoRoot())); //$NON-NLS-1$
        json.addProperty("repo_root", safePath(context == null ? null : context.repoRoot())); //$NON-NLS-1$
        json.addProperty("project_path", safePath(context == null ? null : context.projectPath())); //$NON-NLS-1$
        json.addProperty("project_name", context == null || context.projectName() == null ? "" : context.projectName()); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("resolution_source",
                context == null || context.resolutionSource() == null ? "" : context.resolutionSource()); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("exit_code", output.exitCode()); //$NON-NLS-1$
        JsonArray command = new JsonArray();
        for (String item : output.command()) {
            command.add(item);
        }
        json.add("command", command); //$NON-NLS-1$
        json.addProperty("stdout_tail", truncate(output.stdout())); //$NON-NLS-1$
        json.addProperty("stderr_tail", truncate(output.stderr())); //$NON-NLS-1$
        return json;
    }

    private static String part(String[] parts, int index) {
        return index < parts.length ? parts[index] : ""; //$NON-NLS-1$
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.split("\\R")); //$NON-NLS-1$
    }

    private static String truncate(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        String normalized = value.strip();
        if (normalized.length() <= OUTPUT_TAIL_LIMIT) {
            return normalized;
        }
        return normalized.substring(normalized.length() - OUTPUT_TAIL_LIMIT);
    }

    private static String safePath(Path path) {
        return path == null ? "" : path.toString(); //$NON-NLS-1$
    }

    private static String requireString(Object value) {
        String result = asOptionalString(value);
        if (result == null) {
            throw new GitToolException(GitErrorCode.INVALID_ARGUMENT, "Required parameter is missing"); //$NON-NLS-1$
        }
        return result;
    }

    private static String defaultString(Object value, String defaultValue) {
        String result = asOptionalString(value);
        return result == null ? defaultValue : result;
    }

    private static String asOptionalString(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }

    private static int asPositiveInt(Object value, int defaultValue) {
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                int parsed = Integer.parseInt(string.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static List<String> normalizePaths(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String path = asOptionalString(item);
                if (path != null) {
                    result.add(path);
                }
            }
            return result;
        }
        String single = asOptionalString(value);
        if (single != null) {
            result.add(single);
        }
        return result;
    }

    private record CommandOutput(List<String> command, int exitCode, String stdout, String stderr) {
    }
}
