package com.codepilot1c.core.edt.runtime;

import java.io.File;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Resolves project/runtime launch inputs from EDT workspace state.
 */
public class EdtProjectResolver {

    private final EdtRuntimeService runtimeService;
    private final EdtLaunchConfigurationService launchConfigurationService;

    public EdtProjectResolver() {
        this(new EdtRuntimeService(), new EdtLaunchConfigurationService());
    }

    EdtProjectResolver(EdtRuntimeService runtimeService,
            EdtLaunchConfigurationService launchConfigurationService) {
        this.runtimeService = runtimeService;
        this.launchConfigurationService = launchConfigurationService;
    }

    public EdtResolvedLaunchInputs resolveLaunchInputs(String projectName, File workspaceRoot) {
        InfobaseReference infobase = resolveInfobase(projectName, workspaceRoot);

        EdtLaunchConfigurationService.LaunchConfigurationSettings launchConfiguration;
        try {
            launchConfiguration = launchConfigurationService.resolveRuntimeClientConfiguration(projectName, workspaceRoot);
        } catch (Exception e) {
            throw new EdtToolException(EdtToolErrorCode.LAUNCH_CONFIG_NOT_FOUND,
                    "Failed to read EDT launch configuration: " + e.getMessage(), e); //$NON-NLS-1$
        }
        if (launchConfiguration == null) {
            throw new EdtToolException(EdtToolErrorCode.LAUNCH_CONFIG_NOT_FOUND,
                    "EDT launch configuration not found for project: " + projectName); //$NON-NLS-1$
        }
        if (!launchConfiguration.runtimeInstallationUseAuto()
                && (launchConfiguration.runtimeVersion() == null || launchConfiguration.runtimeVersion().isBlank())) {
            throw new EdtToolException(EdtToolErrorCode.RUNTIME_VERSION_NOT_FOUND,
                    "EDT launch configuration does not define runtime version"); //$NON-NLS-1$
        }

        EdtRuntimeService.AccessSettings accessSettings = runtimeService.resolveAccessSettings(infobase);
        String runtimeVersion = launchConfiguration.runtimeInstallationUseAuto()
                ? null
                : launchConfiguration.runtimeVersion();
        try {
            return new EdtResolvedLaunchInputs(
                    workspaceRoot,
                    projectName,
                    infobase,
                    launchConfiguration,
                    runtimeVersion,
                    launchConfiguration.runtimeInstallationUseAuto(),
                    runtimeService.resolveThickClientInfo(infobase, runtimeVersion),
                    accessSettings);
        } catch (IllegalStateException e) {
            throw new EdtToolException(EdtToolErrorCode.RUNTIME_NOT_RESOLVED,
                    "Failed to resolve EDT runtime: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    public InfobaseReference resolveInfobase(String projectName, File workspaceRoot) {
        requireProject(projectName, workspaceRoot);
        try {
            return runtimeService.resolveDefaultInfobase(projectName);
        } catch (IllegalStateException e) {
            throw mapInfobaseFailure(projectName, e);
        }
    }

    private void requireProject(String projectName, File workspaceRoot) {
        if (projectName == null || projectName.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "project_name is required"); //$NON-NLS-1$
        }
        if (workspaceRoot == null) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "workspace root is unavailable"); //$NON-NLS-1$
        }
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null ? null : ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root == null ? null : root.getProject(projectName);
        if (project == null || !project.exists()) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_NOT_FOUND,
                    "EDT project not found: " + projectName); //$NON-NLS-1$
        }
    }

    private EdtToolException mapInfobaseFailure(String projectName, IllegalStateException e) {
        String message = e.getMessage();
        if (message != null && message.startsWith("Infobase association not found")) { //$NON-NLS-1$
            return new EdtToolException(EdtToolErrorCode.INFOBASE_ASSOCIATION_NOT_FOUND,
                    "Infobase association not found for project: " + projectName, e); //$NON-NLS-1$
        }
        if (message != null && message.startsWith("Infobase reference not found")) { //$NON-NLS-1$
            return new EdtToolException(EdtToolErrorCode.INFOBASE_NOT_FOUND,
                    "Infobase reference not found for project: " + projectName, e); //$NON-NLS-1$
        }
        return new EdtToolException(EdtToolErrorCode.INFOBASE_NOT_FOUND,
                "Failed to resolve EDT infobase: " + message, e); //$NON-NLS-1$
    }
}
