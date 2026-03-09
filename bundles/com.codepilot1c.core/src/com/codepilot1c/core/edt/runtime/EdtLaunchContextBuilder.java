package com.codepilot1c.core.edt.runtime;

/**
 * Builds normalized launch context and shared access-setting merge rules.
 */
public class EdtLaunchContextBuilder {

    public EdtResolvedLaunchContext build(EdtResolvedLaunchInputs inputs) {
        if (inputs == null) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "launch inputs are required"); //$NON-NLS-1$
        }
        if (inputs.thickClientInfo() == null
                || inputs.thickClientInfo().component() == null
                || inputs.thickClientInfo().component().getFile() == null) {
            throw new EdtToolException(EdtToolErrorCode.RUNTIME_NOT_RESOLVED,
                    "Thick client runtime component not resolved"); //$NON-NLS-1$
        }
        return new EdtResolvedLaunchContext(
                inputs.workspaceRoot(),
                inputs.projectName(),
                inputs.launchConfiguration() == null ? null : inputs.launchConfiguration().file(),
                inputs.runtimeVersion(),
                inputs.runtimeUseAuto(),
                inputs.infobase(),
                inputs.thickClientInfo().component().getFile(),
                mergeLaunchAccessSettings(inputs.infobaseAccessSettings(), inputs.launchConfiguration()));
    }

    public static EdtRuntimeService.AccessSettings mergeLaunchAccessSettings(
            EdtRuntimeService.AccessSettings fallback,
            EdtLaunchConfigurationService.LaunchConfigurationSettings launchConfig) {
        if (launchConfig == null) {
            return fallback;
        }
        if (launchConfig.launchUserUseInfobaseAccess()) {
            return fallback;
        }
        String additional = fallback == null ? null : fallback.getAdditionalParameters();
        if (launchConfig.launchOsInfobaseAccess()) {
            return EdtRuntimeService.AccessSettings.osAuthentication(additional);
        }
        String launchUser = launchConfig.launchUserName();
        if (launchUser != null && !launchUser.isBlank()) {
            String password = fallback != null && launchUser.equals(fallback.getUserName())
                    ? fallback.getPassword()
                    : null;
            return EdtRuntimeService.AccessSettings.infobaseAuthentication(launchUser, password, additional);
        }
        return fallback;
    }
}
