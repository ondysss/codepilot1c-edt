package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Assume;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.GsdShipPathPolicy;
import com.codepilot1c.core.agent.profiles.AgentCapability;
import com.codepilot1c.core.filesystem.SecureDirectoryCapabilityException;
import com.codepilot1c.core.filesystem.SecureDirectoryMutation;
import com.codepilot1c.core.filesystem.SecureDirectoryMutation.CapabilityPolicy;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolResult;

public class WorkspacePathContainmentTest {

    @Test
    public void releasePolicyRejectsAliasesAbsoluteAndAlternateSeparatorTraversal() {
        assertTrue(GsdShipPathPolicy.isReleaseArtifactPath("CHANGELOG.md")); //$NON-NLS-1$
        assertTrue(GsdShipPathPolicy.isReleaseArtifactPath(
                "docs\\release-notes\\v1.json")); //$NON-NLS-1$
        assertFalse(GsdShipPathPolicy.isReleaseArtifactPath(
                "docs/release-notes/../v1.md")); //$NON-NLS-1$
        assertFalse(GsdShipPathPolicy.isReleaseArtifactPath("/CHANGELOG.md")); //$NON-NLS-1$
        assertFalse(GsdShipPathPolicy.isReleaseArtifactPath(
                "C:\\workspace\\CHANGELOG.md")); //$NON-NLS-1$
        assertFalse(GsdShipPathPolicy.isReleaseArtifactPath(
                "docs//release-notes/v1.md")); //$NON-NLS-1$
    }

    @Test
    public void physicalContainmentRejectsLinkedAndSymlinkedOutsideTargets() throws Exception {
        Path workspace = Files.createTempDirectory("ship-workspace-"); //$NON-NLS-1$
        Path project = Files.createDirectories(workspace.resolve("project")); //$NON-NLS-1$
        Path outside = Files.createTempDirectory("ship-outside-"); //$NON-NLS-1$
        Path inside = Files.createDirectories(project.resolve("docs/release-notes")); //$NON-NLS-1$

        assertTrue(WorkspacePathContainment.isContained(
                workspace, project, inside.resolve("v1.md"))); //$NON-NLS-1$
        assertFalse(WorkspacePathContainment.isContained(
                workspace, project, outside.resolve("v1.md"))); //$NON-NLS-1$

        Path link = project.resolve("release-notes"); //$NON-NLS-1$
        Files.createSymbolicLink(link, outside);
        assertFalse(WorkspacePathContainment.isContained(
                workspace, project, link.resolve("v2.md"))); //$NON-NLS-1$

        Path source = Files.writeString(project.resolve("Main.java"), "source"); //$NON-NLS-1$ //$NON-NLS-2$
        Path releaseAlias = project.resolve("CHANGELOG.md"); //$NON-NLS-1$
        Files.createSymbolicLink(releaseAlias, source);
        assertFalse(WorkspacePathContainment.isContained(
                workspace, project, releaseAlias));
    }

    @Test
    public void physicalContainmentRejectsProjectOutsideWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("ship-workspace-"); //$NON-NLS-1$
        Path externalProject = Files.createTempDirectory("ship-project-"); //$NON-NLS-1$
        assertFalse(WorkspacePathContainment.isContained(
                workspace, externalProject, externalProject.resolve("CHANGELOG.md"))); //$NON-NLS-1$
    }

    @Test
    public void shipArtifactWriteRejectsDeterministicAncestrySwap() throws Exception {
        Path workspace = Files.createTempDirectory("ship-race-workspace-"); //$NON-NLS-1$
        Assume.assumeTrue("test requires the provider's actual SecureDirectoryStream", //$NON-NLS-1$
                SecureDirectoryMutation.supportsSecureDirectoryStreams(workspace));
        Path project = Files.createDirectories(workspace.resolve("project")); //$NON-NLS-1$
        Path parent = Files.createDirectories(project.resolve("docs/release-notes")); //$NON-NLS-1$
        Path target = parent.resolve("v1.md"); //$NON-NLS-1$
        Path outside = Files.createTempDirectory("ship-race-outside-"); //$NON-NLS-1$
        Path outsideTarget = Files.writeString(
                Files.createDirectories(outside.resolve("release-notes")).resolve("v1.md"), //$NON-NLS-1$ //$NON-NLS-2$
                "outside", StandardCharsets.UTF_8); //$NON-NLS-1$

        try {
            WorkspacePathContainment.writeContained(
                    workspace, project, target, "inside".getBytes(StandardCharsets.UTF_8), //$NON-NLS-1$
                    operation -> {
                        if ("ship-artifact".equals(operation)) { //$NON-NLS-1$
                            Files.move(project.resolve("docs"), //$NON-NLS-1$
                                    project.resolve("docs-original")); //$NON-NLS-1$
                            Files.createSymbolicLink(project.resolve("docs"), outside); //$NON-NLS-1$
                        }
                    });
            throw new AssertionError("expected changed ancestry rejection"); //$NON-NLS-1$
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("changed") //$NON-NLS-1$
                    || expected.getMessage().contains("escaped")); //$NON-NLS-1$
        }

        assertEquals("outside", Files.readString(outsideTarget)); //$NON-NLS-1$
        assertFalse(Files.exists(
                project.resolve("docs-original/release-notes/v1.md"))); //$NON-NLS-1$
    }

    @Test
    public void actualSecureProviderWritesOrdinaryShipArtifact() throws Exception {
        Path workspace = Files.createTempDirectory("ship-secure-workspace-"); //$NON-NLS-1$
        Assume.assumeTrue("test requires the provider's actual SecureDirectoryStream", //$NON-NLS-1$
                SecureDirectoryMutation.supportsSecureDirectoryStreams(workspace));
        Path project = Files.createDirectories(workspace.resolve("project")); //$NON-NLS-1$
        Path parent = Files.createDirectories(project.resolve("docs/release-notes")); //$NON-NLS-1$
        Path target = parent.resolve("v1.md"); //$NON-NLS-1$

        WorkspacePathContainment.writeContained(
                workspace, project, target, "release".getBytes(StandardCharsets.UTF_8), null); //$NON-NLS-1$

        assertEquals("release", Files.readString(target)); //$NON-NLS-1$
    }

    @Test
    public void workspaceBoundProjectBindRejectsDeterministicEscapeSwap() throws Exception {
        Path workspace = Files.createTempDirectory("ship-bind-workspace-"); //$NON-NLS-1$
        Assume.assumeTrue("test requires the provider's actual SecureDirectoryStream", //$NON-NLS-1$
                SecureDirectoryMutation.supportsSecureDirectoryStreams(workspace));
        Path project = Files.createDirectories(workspace.resolve("project")); //$NON-NLS-1$
        Path parent = Files.createDirectories(project.resolve("docs/release-notes")); //$NON-NLS-1$
        Path target = parent.resolve("v1.md"); //$NON-NLS-1$
        Path outsideProject = Files.createTempDirectory("ship-bind-outside-"); //$NON-NLS-1$
        Path outsideParent = Files.createDirectories(
                outsideProject.resolve("docs/release-notes")); //$NON-NLS-1$
        Path outsideTarget = Files.writeString(outsideParent.resolve("v1.md"), "outside"); //$NON-NLS-1$ //$NON-NLS-2$

        try {
            WorkspacePathContainment.writeContained(workspace, project, target,
                    "inside".getBytes(StandardCharsets.UTF_8), operation -> { //$NON-NLS-1$
                        if ("ship-project-bind".equals(operation)) { //$NON-NLS-1$
                            Files.move(project, workspace.resolve("project-original")); //$NON-NLS-1$
                            Files.createSymbolicLink(project, outsideProject);
                        }
                    });
            throw new AssertionError("expected workspace-bound project rejection"); //$NON-NLS-1$
        } catch (IOException expected) {
            // secure workspace-relative binding rejects the replacement symlink
        }

        assertEquals("outside", Files.readString(outsideTarget)); //$NON-NLS-1$
        assertFalse(Files.exists(workspace.resolve(
                "project-original/docs/release-notes/v1.md"))); //$NON-NLS-1$
    }

    @Test
    public void shipParentPreBindSwapCannotPublishOutsideArtifact() throws Exception {
        Path workspace = Files.createTempDirectory("ship-parent-bind-workspace-"); //$NON-NLS-1$
        Assume.assumeTrue("test requires the provider's actual SecureDirectoryStream", //$NON-NLS-1$
                SecureDirectoryMutation.supportsSecureDirectoryStreams(workspace));
        Path project = Files.createDirectories(workspace.resolve("project")); //$NON-NLS-1$
        Path parent = Files.createDirectories(project.resolve("docs/release-notes")); //$NON-NLS-1$
        Path target = parent.resolve("v1.md"); //$NON-NLS-1$
        Path outside = Files.createTempDirectory("ship-parent-bind-outside-"); //$NON-NLS-1$
        Path outsideParent = Files.createDirectories(outside.resolve("release-notes")); //$NON-NLS-1$
        Path outsideTarget = Files.writeString(outsideParent.resolve("v1.md"), "outside"); //$NON-NLS-1$ //$NON-NLS-2$

        try {
            WorkspacePathContainment.writeContained(workspace, project, target,
                    "inside".getBytes(StandardCharsets.UTF_8), operation -> { //$NON-NLS-1$
                        if ("ship-parent-bind".equals(operation)) { //$NON-NLS-1$
                            Files.move(project.resolve("docs"), //$NON-NLS-1$
                                    project.resolve("docs-original")); //$NON-NLS-1$
                            Files.createSymbolicLink(project.resolve("docs"), outside); //$NON-NLS-1$
                        }
                    });
            throw new AssertionError("expected parent pre-bind rejection"); //$NON-NLS-1$
        } catch (IOException expected) {
            // The project-relative secure open rejects the replacement symlink.
        }

        assertEquals("outside", Files.readString(outsideTarget)); //$NON-NLS-1$
        try (java.util.stream.Stream<Path> children = Files.list(outsideParent)) {
            assertEquals(1L, children.count());
        }
        assertFalse(Files.exists(
                project.resolve("docs-original/release-notes/v1.md"))); //$NON-NLS-1$
    }

    @Test
    public void missingShipParentFailsBeforeFolderOrOutsideMutation() throws Exception {
        Path workspace = Files.createTempDirectory("ship-missing-workspace-"); //$NON-NLS-1$
        Assume.assumeTrue("test requires the provider's actual SecureDirectoryStream", //$NON-NLS-1$
                SecureDirectoryMutation.supportsSecureDirectoryStreams(workspace));
        Path project = Files.createDirectories(workspace.resolve("project")); //$NON-NLS-1$
        Path target = project.resolve("docs/release-notes/v1.md"); //$NON-NLS-1$
        Path outside = Files.createTempDirectory("ship-missing-outside-"); //$NON-NLS-1$
        Path sentinel = Files.writeString(outside.resolve("sentinel"), "unchanged"); //$NON-NLS-1$ //$NON-NLS-2$
        AtomicInteger parentBoundary = new AtomicInteger();

        try {
            WorkspacePathContainment.writeContained(workspace, project, target,
                    "inside".getBytes(StandardCharsets.UTF_8), operation -> { //$NON-NLS-1$
                        if ("ship-parent-create".equals(operation)) { //$NON-NLS-1$
                            parentBoundary.incrementAndGet();
                        }
                    });
            throw new AssertionError("expected missing-parent capability rejection"); //$NON-NLS-1$
        } catch (SecureDirectoryCapabilityException expected) {
            assertTrue(expected.getMessage().contains("creation")); //$NON-NLS-1$
        }

        assertEquals(1, parentBoundary.get());
        assertFalse(Files.exists(project.resolve("docs"))); //$NON-NLS-1$
        assertEquals("unchanged", Files.readString(sentinel)); //$NON-NLS-1$
    }

    @Test
    public void forcedNonSecureShipProviderFailsBeforeAnyMutation() throws Exception {
        Path workspace = Files.createTempDirectory("ship-forced-workspace-"); //$NON-NLS-1$
        Path project = Files.createDirectories(workspace.resolve("project")); //$NON-NLS-1$
        Path parent = project.resolve("docs/release-notes"); //$NON-NLS-1$
        Path target = parent.resolve("v1.md"); //$NON-NLS-1$
        AtomicInteger parentBoundary = new AtomicInteger();

        try {
            WorkspacePathContainment.writeContained(workspace, project, target,
                    "inside".getBytes(StandardCharsets.UTF_8), operation -> { //$NON-NLS-1$
                        if ("ship-parent-create".equals(operation)) { //$NON-NLS-1$
                            parentBoundary.incrementAndGet();
                        }
                    },
                    CapabilityPolicy.FORCE_NON_SECURE_FOR_TESTS);
            throw new AssertionError("expected forced capability rejection"); //$NON-NLS-1$
        } catch (SecureDirectoryCapabilityException expected) {
            assertTrue(expected.getMessage().contains("SecureDirectoryStream")); //$NON-NLS-1$
            assertFalse(expected.getMessage().contains("pre-create")); //$NON-NLS-1$
        }

        assertEquals(0, parentBoundary.get());
        assertFalse(Files.exists(project.resolve("docs"))); //$NON-NLS-1$
        assertFalse(Files.exists(target));
    }

    @Test
    public void realWriteToolShipExecutionMapsForcedCapabilityWithoutMutation()
            throws Exception {
        Path workspace = Files.createTempDirectory("ship-tool-forced-workspace-"); //$NON-NLS-1$
        Path projectPath = Files.createDirectories(workspace.resolve("project")); //$NON-NLS-1$
        Path parent = Files.createDirectories(projectPath.resolve("docs/release-notes")); //$NON-NLS-1$
        Path target = parent.resolve("v1.md"); //$NON-NLS-1$
        Path outside = Files.createTempDirectory("ship-tool-forced-outside-"); //$NON-NLS-1$
        Path sentinel = Files.writeString(outside.resolve("sentinel"), "unchanged"); //$NON-NLS-1$ //$NON-NLS-2$
        org.eclipse.core.runtime.IPath workspaceLocation =
                org.eclipse.core.runtime.Path.fromOSString(workspace.toString());
        org.eclipse.core.runtime.IPath projectLocation =
                org.eclipse.core.runtime.Path.fromOSString(projectPath.toString());
        org.eclipse.core.runtime.IPath targetLocation =
                org.eclipse.core.runtime.Path.fromOSString(target.toString());
        org.eclipse.core.runtime.IPath projectRelative =
                org.eclipse.core.runtime.Path.fromPortableString("docs/release-notes/v1.md"); //$NON-NLS-1$
        AtomicReference<IFile> fileRef = new AtomicReference<>();

        IProject project = (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(), new Class<?>[] {IProject.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> Boolean.valueOf(args != null && args.length == 1 //$NON-NLS-1$
                            && proxy == args[0]);
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                    case "getLocation" -> projectLocation; //$NON-NLS-1$
                    case "getFile" -> fileRef.get(); //$NON-NLS-1$
                    case "getName" -> "project"; //$NON-NLS-1$ //$NON-NLS-2$
                    case "exists", "isOpen" -> Boolean.TRUE; //$NON-NLS-1$ //$NON-NLS-2$
                    default -> defaultValue(method.getReturnType());
                });
        IFile file = (IFile) Proxy.newProxyInstance(
                IFile.class.getClassLoader(), new Class<?>[] {IFile.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getProject" -> project; //$NON-NLS-1$
                    case "getLocation" -> targetLocation; //$NON-NLS-1$
                    case "getProjectRelativePath" -> projectRelative; //$NON-NLS-1$
                    case "getFullPath" -> projectRelative; //$NON-NLS-1$
                    case "getName" -> "v1.md"; //$NON-NLS-1$ //$NON-NLS-2$
                    case "exists", "isLinked" -> Boolean.FALSE; //$NON-NLS-1$ //$NON-NLS-2$
                    default -> defaultValue(method.getReturnType());
                });
        fileRef.set(file);
        IWorkspaceRoot root = (IWorkspaceRoot) Proxy.newProxyInstance(
                IWorkspaceRoot.class.getClassLoader(), new Class<?>[] {IWorkspaceRoot.class},
                (proxy, method, args) -> "getLocation".equals(method.getName()) //$NON-NLS-1$
                        ? workspaceLocation : defaultValue(method.getReturnType()));
        WriteTool tool = new WriteTool(null, root, project,
                CapabilityPolicy.FORCE_NON_SECURE_FOR_TESTS);
        ToolExecutionContext context = new ToolExecutionContext(
                "gsd-ship", AgentCapability.MUTATING, 0, //$NON-NLS-1$
                projectPath.toString(), "ship-test"); //$NON-NLS-1$

        ToolResult result = tool.execute(Map.of(
                "path", "docs/release-notes/v1.md", //$NON-NLS-1$ //$NON-NLS-2$
                "content", "release", //$NON-NLS-1$ //$NON-NLS-2$
                "overwrite", true), context).get(); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertTrue(result.hasStructuredData());
        assertEquals("error", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("write_file", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("unsupported", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Files.exists(target));
        try (java.util.stream.Stream<Path> children = Files.list(parent)) {
            assertEquals(0L, children.count());
        }
        assertEquals("unchanged", Files.readString(sentinel)); //$NON-NLS-1$
    }

    @Test
    public void eclipseLinkedFileIsRejectedWithAncestorChecking() {
        AtomicInteger options = new AtomicInteger(-1);
        IFile linkedFile = linkedResource(IFile.class, options, true);

        assertTrue(linkedFile.isLinked());
        assertTrue(WorkspacePathContainment.isLinkedResource(linkedFile));
        assertTrue((options.get() & IResource.CHECK_ANCESTORS) != 0);
    }

    @Test
    public void eclipseFileBelowLinkedDirectoryIsRejectedWithAncestorChecking() {
        AtomicInteger options = new AtomicInteger(-1);
        IFile fileBelowLinkedDirectory = linkedResource(IFile.class, options, false);

        assertFalse(fileBelowLinkedDirectory.isLinked());
        assertTrue(WorkspacePathContainment.isLinkedResource(fileBelowLinkedDirectory));
        assertTrue((options.get() & IResource.CHECK_ANCESTORS) != 0);
    }

    @Test
    public void shipWriteExecutionCheckRejectsSameProjectLinkedFile() throws Exception {
        assertFalse(invokeShipTargetCheck(true, false));
    }

    @Test
    public void shipWriteExecutionCheckRejectsFileBelowLinkedDirectory() throws Exception {
        assertFalse(invokeShipTargetCheck(false, true));
    }

    private static boolean invokeShipTargetCheck(
            boolean directlyLinked, boolean linkedAncestor) throws Exception {
        IProject project = (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(), new Class<?>[] {IProject.class},
                (proxy, method, args) -> {
                    if ("equals".equals(method.getName())) { //$NON-NLS-1$
                        return Boolean.valueOf(args != null && args.length == 1
                                && proxy == args[0]);
                    }
                    if ("hashCode".equals(method.getName())) { //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    }
                    return defaultValue(method.getReturnType());
                });
        IFile file = (IFile) Proxy.newProxyInstance(
                IFile.class.getClassLoader(), new Class<?>[] {IFile.class},
                (proxy, method, args) -> {
                    if ("getProject".equals(method.getName())) { //$NON-NLS-1$
                        return project;
                    }
                    if ("isLinked".equals(method.getName())) { //$NON-NLS-1$
                        if (args == null || args.length == 0) {
                            return Boolean.valueOf(directlyLinked);
                        }
                        int options = ((Integer) args[0]).intValue();
                        return Boolean.valueOf(directlyLinked || (linkedAncestor
                                && (options & IResource.CHECK_ANCESTORS) != 0));
                    }
                    return defaultValue(method.getReturnType());
                });
        IWorkspaceRoot root = (IWorkspaceRoot) Proxy.newProxyInstance(
                IWorkspaceRoot.class.getClassLoader(),
                new Class<?>[] {IWorkspaceRoot.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));

        Method check = WriteTool.class.getDeclaredMethod(
                "isPhysicalShipTarget", //$NON-NLS-1$
                IWorkspaceRoot.class, IProject.class, IFile.class);
        check.setAccessible(true);
        return ((Boolean) check.invoke(new WriteTool(), root, project, file)).booleanValue();
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == int.class) {
            return Integer.valueOf(0);
        }
        if (returnType == long.class) {
            return Long.valueOf(0L);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends IResource> T linkedResource(
            Class<T> type, AtomicInteger options, boolean directlyLinked) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if ("isLinked".equals(method.getName())) { //$NON-NLS-1$
                        if (args != null && args.length == 1) {
                            options.set(((Integer) args[0]).intValue());
                            return Boolean.valueOf((options.get()
                                    & IResource.CHECK_ANCESTORS) != 0 || directlyLinked);
                        }
                        return Boolean.valueOf(directlyLinked);
                    }
                    if ("toString".equals(method.getName())) { //$NON-NLS-1$
                        return "linked-test-resource"; //$NON-NLS-1$
                    }
                    return defaultValue(method.getReturnType());
                });
    }
}
