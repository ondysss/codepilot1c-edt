package com.codepilot1c.core.qa;

import java.io.File;

public final class QaRuntimeSettings {

    private QaRuntimeSettings() {
    }

    public static File resolveEpfPath(QaConfig config, File workspaceRoot, String preferenceEpfPath) {
        if (config != null && config.vanessa != null && config.vanessa.epf_path != null
                && !config.vanessa.epf_path.isBlank()) {
            return QaPaths.resolve(config.vanessa.epf_path, workspaceRoot);
        }
        if (preferenceEpfPath != null && !preferenceEpfPath.isBlank()) {
            return QaPaths.resolve(preferenceEpfPath, workspaceRoot);
        }
        return null;
    }

    public static boolean hasConfiguredEpfPath(QaConfig config, String preferenceEpfPath) {
        return (config != null && config.vanessa != null && config.vanessa.epf_path != null
                && !config.vanessa.epf_path.isBlank())
                || (preferenceEpfPath != null && !preferenceEpfPath.isBlank());
    }

    public static String describeEpfSource(QaConfig config, String preferenceEpfPath) {
        if (config != null && config.vanessa != null && config.vanessa.epf_path != null
                && !config.vanessa.epf_path.isBlank()) {
            return "project_config"; //$NON-NLS-1$
        }
        if (preferenceEpfPath != null && !preferenceEpfPath.isBlank()) {
            return "preferences"; //$NON-NLS-1$
        }
        return "missing"; //$NON-NLS-1$
    }
}
