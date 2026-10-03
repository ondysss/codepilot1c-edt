package com.codepilot1c.core.edt.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;

/** Resolves an extension through EDT's model, never through its name prefix. */
public class EdtExtensionUpdateService {
    private final EdtMetadataGateway metadata;
    private final EdtRuntimeService runtime;

    public EdtExtensionUpdateService() {
        this(new EdtMetadataGateway(), new EdtRuntimeService());
    }

    public EdtExtensionUpdateService(EdtMetadataGateway metadata, EdtRuntimeService runtime) {
        this.metadata = metadata;
        this.runtime = runtime;
    }

    public record Target(IProject extension, IProject base, InfobaseReference infobase, String binding) {}

    public Target resolve(String extensionName, String requestedBase) {
        if (extensionName == null || extensionName.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "extension_project is required");
        }
        IProject project = metadata.resolveProject(extensionName.trim());
        requireOpen(project, "Extension project");
        IV8ProjectManager manager = metadata.getV8ProjectManager();
        IV8Project model = manager.getProject(project.getName());
        // EDT can expose extensions in its typed registry before the direct DT-project lookup.
        // Match the exact workspace project, following the existing extension service's resolver.
        if (!(model instanceof IExtensionProject)) {
            for (IExtensionProject candidate : manager.getProjects(IExtensionProject.class)) {
                if (candidate != null && candidate.getProject() != null
                        && project.getName().equals(candidate.getProject().getName())) {
                    model = candidate;
                    break;
                }
            }
        }
        if (!(model instanceof IExtensionProject extension)) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "The selected EDT project is not a configuration extension");
        }
        IProject parent = extension.getParentProject();
        requireOpen(parent, "Base project of extension");
        if (project.equals(parent)) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "Extension and base project must differ");
        }
        if (requestedBase != null && !requestedBase.isBlank()
                && !parent.getName().equals(requestedBase.trim())) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "base_project does not match the extension's EDT parent project");
        }
        InfobaseReference infobase;
        try {
            infobase = runtime.resolveDefaultInfobase(parent.getName());
        } catch (IllegalStateException e) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_ASSOCIATION_NOT_FOUND,
                    "Cannot resolve the base project's default infobase in EDT", e);
        }
        if (infobase == null || infobase.getConnectionString() == null) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_NOT_FOUND,
                    "The base project's default infobase has no connection string");
        }
        String binding;
        try {
            // Credentials and connection paths stay internal; the hash pins queued work to its target.
            binding = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    infobase.getConnectionString().asConnectionString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new EdtToolException(EdtToolErrorCode.INFOBASE_NOT_FOUND,
                    "Cannot identify the base project's default infobase", e);
        }
        return new Target(project, parent, infobase, binding);
    }

    public boolean update(Target target, boolean keepConnected, boolean allowConflictOverride,
            IProgressMonitor monitor) throws Exception {
        Target current = resolve(target.extension().getName(), target.base().getName());
        if (!current.binding().equals(target.binding())) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "The default infobase changed after this update was prepared; resolve the target again");
        }
        return runtime.reloadExtension(current.extension(), current.infobase(), keepConnected,
                allowConflictOverride, monitor);
    }

    private static void requireOpen(IProject project, String label) {
        if (project == null || !project.exists()) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_NOT_FOUND, label + " was not found");
        }
        if (!project.isOpen()) {
            throw new EdtToolException(EdtToolErrorCode.EDT_NOT_READY, label + " is closed in EDT");
        }
    }
}
