package com.codepilot1c.core.edt.runtime;

import java.io.File;

import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager.ThickClientInfo;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Raw resolved EDT runtime launch inputs before access settings are normalized.
 */
public record EdtResolvedLaunchInputs(
        File workspaceRoot,
        String projectName,
        InfobaseReference infobase,
        EdtLaunchConfigurationService.LaunchConfigurationSettings launchConfiguration,
        String runtimeVersion,
        boolean runtimeUseAuto,
        ThickClientInfo thickClientInfo,
        EdtRuntimeService.AccessSettings infobaseAccessSettings) {
}
