package com.codepilot1c.core.qa;

import java.io.File;

public final class QaRuntimeSettings {

    public static final String SOURCE_PROJECT_CONFIG = "project_config"; //$NON-NLS-1$
    public static final String SOURCE_PREFERENCES = "preferences"; //$NON-NLS-1$
    public static final String SOURCE_WORKSPACE_DEFAULT = "workspace_default"; //$NON-NLS-1$
    public static final String SOURCE_BUNDLED = "bundled"; //$NON-NLS-1$
    public static final String SOURCE_GENERATED = "generated"; //$NON-NLS-1$
    public static final String SOURCE_MISSING = "missing"; //$NON-NLS-1$

    public static final String UNKNOWN_STEPS_MODE_OFF = "off"; //$NON-NLS-1$
    public static final String UNKNOWN_STEPS_MODE_WARN = "warn"; //$NON-NLS-1$
    public static final String UNKNOWN_STEPS_MODE_STRICT = "strict"; //$NON-NLS-1$

    private static final String DEFAULT_STEPS_CATALOG = "tests/va/steps_catalog.json"; //$NON-NLS-1$

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
            return SOURCE_PROJECT_CONFIG;
        }
        if (preferenceEpfPath != null && !preferenceEpfPath.isBlank()) {
            return SOURCE_PREFERENCES;
        }
        return SOURCE_MISSING;
    }

    public static File resolveParamsTemplate(QaConfig config, File workspaceRoot) {
        if (config == null || config.vanessa == null) {
            return null;
        }
        return QaPaths.resolve(config.vanessa.params_template, workspaceRoot);
    }

    public static String describeParamsTemplateSource(QaConfig config, File workspaceRoot) {
        if (config != null && config.vanessa != null && config.vanessa.params_template != null
                && !config.vanessa.params_template.isBlank()) {
            return SOURCE_PROJECT_CONFIG;
        }
        return SOURCE_GENERATED;
    }

    public static File resolveStepsCatalog(QaConfig config, File workspaceRoot) {
        if (config != null && config.vanessa != null && config.vanessa.steps_catalog != null
                && !config.vanessa.steps_catalog.isBlank()) {
            return QaPaths.resolve(config.vanessa.steps_catalog, workspaceRoot);
        }
        return QaPaths.resolve(DEFAULT_STEPS_CATALOG, workspaceRoot);
    }

    public static String describeStepsCatalogSource(QaConfig config, File workspaceRoot, boolean bundledExists) {
        if (config != null && config.vanessa != null && config.vanessa.steps_catalog != null
                && !config.vanessa.steps_catalog.isBlank()) {
            return SOURCE_PROJECT_CONFIG;
        }
        File workspaceDefault = QaPaths.resolve(DEFAULT_STEPS_CATALOG, workspaceRoot);
        if (workspaceDefault != null && workspaceDefault.exists()) {
            return SOURCE_WORKSPACE_DEFAULT;
        }
        return bundledExists ? SOURCE_BUNDLED : SOURCE_MISSING;
    }

    public static String describeStepsCatalogSource(QaConfig config, File workspaceRoot, File resolvedCatalog) {
        if (config != null && config.vanessa != null && config.vanessa.steps_catalog != null
                && !config.vanessa.steps_catalog.isBlank()) {
            return SOURCE_PROJECT_CONFIG;
        }
        File workspaceDefault = QaPaths.resolve(DEFAULT_STEPS_CATALOG, workspaceRoot);
        if (workspaceDefault != null && workspaceDefault.exists()) {
            return SOURCE_WORKSPACE_DEFAULT;
        }
        return resolvedCatalog != null && resolvedCatalog.exists() ? SOURCE_BUNDLED : SOURCE_MISSING;
    }

    public static String resolveUnknownStepsMode(QaConfig config) {
        String configured = null;
        if (config != null && config.test_runner != null) {
            configured = config.test_runner.unknown_steps_mode;
        }
        if (configured == null || configured.isBlank()) {
            return UNKNOWN_STEPS_MODE_WARN;
        }
        String normalized = configured.trim().toLowerCase();
        if (UNKNOWN_STEPS_MODE_OFF.equals(normalized)
                || UNKNOWN_STEPS_MODE_WARN.equals(normalized)
                || UNKNOWN_STEPS_MODE_STRICT.equals(normalized)) {
            return normalized;
        }
        return UNKNOWN_STEPS_MODE_WARN;
    }
}
