package com.codepilot1c.core.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;

/** Removes only the Eclipse project registration. Source files are never deleted. */
public class WorkspaceProjectRemovalService {
    public record Result(String status, String projectName, String location, boolean registered,
            boolean open, boolean canRemove, List<String> blockers, boolean sourceFilesPreserved) { }

    public Result remove(String name, boolean dryRun, String expectedLocation) throws CoreException {
        return remove(ResourcesPlugin.getWorkspace(), name, dryRun, expectedLocation);
    }

    public Result remove(IWorkspace workspace, String name, boolean dryRun, String expectedLocation)
            throws CoreException {
        if (workspace == null || name == null || name.isBlank()
                || !workspace.validateName(name, IResource.PROJECT).isOK()) {
            throw new IllegalArgumentException("An exact valid workspace project name is required");
        }
        IProject project = workspace.getRoot().getProject(name);
        Result before = inspect(project);
        if (dryRun || !before.registered()) {
            return before;
        }
        if (expectedLocation == null || expectedLocation.isBlank()
                || !Path.of(before.location()).equals(Path.of(expectedLocation).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("expected_location must match the location returned by dry_run");
        }
        if (!before.canRemove()) {
            throw new IllegalStateException("Project removal blocked: " + String.join("; ", before.blockers()));
        }
        workspace.run(monitor -> {
            Result current = inspect(project);
            if (!current.registered() || !current.canRemove()
                    || !before.location().equals(current.location())) {
                throw new IllegalStateException("Project changed after inspection; repeat dry_run");
            }
            // This native API updates resource, nature and EDT listeners consistently.
            // deleteContent=false is intentional and cannot be overridden by callers.
            project.delete(false, false, monitor);
        }, workspace.getRoot(), IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
        boolean retained = Files.isRegularFile(Path.of(before.location(), ".project"));
        if (project.exists() || !retained) {
            throw new IllegalStateException("Project removal postcondition failed: registration or source state differs");
        }
        return new Result("removed", name, before.location(), false, false, false, List.of(), true);
    }

    protected Result inspect(IProject project) throws CoreException {
        if (!project.exists()) {
            return new Result("not_found", project.getName(), "", false, false, false, List.of(), true);
        }
        if (project.getLocation() == null) {
            throw new IllegalStateException("Project has no local location; removal is not supported");
        }
        String location = project.getLocation().toFile().toPath().toAbsolutePath().normalize().toString();
        TreeSet<String> blockers = new TreeSet<>();
        for (IProject referring : project.getReferencingProjects()) {
            if (referring.exists()) {
                blockers.add("Referenced by project: " + referring.getName());
            }
        }
        blockers.addAll(additionalBlockers(project));
        if (!Files.isRegularFile(Path.of(location, ".project"))) {
            blockers.add("Source .project file is unavailable");
        }
        return new Result("preview", project.getName(), location, true, project.isOpen(),
                blockers.isEmpty(), List.copyOf(blockers), true);
    }

    /** Runtime checks are injectable so tests can drive the real removal algorithm headlessly. */
    protected List<String> additionalBlockers(IProject project) throws CoreException {
        List<String> blockers = new ArrayList<>();
        for (IExtensionProject extension : new EdtMetadataGateway().getV8ProjectManager()
                .getProjects(IExtensionProject.class)) {
            if (project.equals(extension.getParentProject()) && extension.getProject().exists()) {
                blockers.add("Base configuration of extension: " + extension.getProject().getName());
            }
        }
        if (PlatformUI.isWorkbenchRunning()) {
            PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
                for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                    for (var page : window.getPages()) {
                        for (IEditorPart editor : page.getDirtyEditors()) {
                            IFile file = editor.getEditorInput().getAdapter(IFile.class);
                            if (file != null && project.equals(file.getProject())) {
                                blockers.add("Unsaved editor: " + editor.getTitle());
                            }
                        }
                    }
                }
            });
        }
        return blockers;
    }
}
