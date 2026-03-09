package com.codepilot1c.core.edt.runtime;

import java.io.File;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Normalized launch context used by generic runtime launch tools.
 */
public record EdtResolvedLaunchContext(
        File workspaceRoot,
        String projectName,
        File launchConfigurationFile,
        String runtimeVersion,
        boolean runtimeUseAuto,
        InfobaseReference infobase,
        File clientFile,
        EdtRuntimeService.AccessSettings accessSettings) {
}
