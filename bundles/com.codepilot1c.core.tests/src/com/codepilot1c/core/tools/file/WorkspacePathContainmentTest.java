package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.GsdShipPathPolicy;

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
