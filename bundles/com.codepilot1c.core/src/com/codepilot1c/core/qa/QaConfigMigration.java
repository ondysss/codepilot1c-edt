package com.codepilot1c.core.qa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QaConfigMigration {

    private QaConfigMigration() {
    }

    public static MigrationReport analyze(QaConfig source, String fallbackProjectName,
            boolean useEdtRuntime, boolean useTestManager) {
        QaConfig config = QaConfig.copyOf(source);
        List<String> warnings = new ArrayList<>();
        List<String> appliedChanges = new ArrayList<>();

        detectLegacySignals(config, warnings);
        applyDefaults(config, fallbackProjectName, appliedChanges);
        boolean incomplete = detectIncompleteState(config, useEdtRuntime, useTestManager, warnings);

        return new MigrationReport(config, !appliedChanges.isEmpty(), !warnings.isEmpty(), incomplete,
                warnings, appliedChanges);
    }

    private static void detectLegacySignals(QaConfig config, List<String> warnings) {
        if (config == null) {
            warnings.add("QA config is missing and will be created from defaults"); //$NON-NLS-1$
            return;
        }
        if (!isBlank(config.vanessa.path_to_va)) {
            warnings.add("Legacy field vanessa.path_to_va is present"); //$NON-NLS-1$
        }
        if (!isBlank(config.vanessa.version)) {
            warnings.add("Legacy field vanessa.version is present"); //$NON-NLS-1$
        }
        if (!isBlank(config.paths.libraries_dir)) {
            warnings.add("Legacy field paths.libraries_dir is present"); //$NON-NLS-1$
        }
        if (!isBlank(config.paths.snippets_dir)) {
            warnings.add("Legacy field paths.snippets_dir is present"); //$NON-NLS-1$
        }
        if (!isBlank(config.infobase.db_type) || !isBlank(config.infobase.db_path)) {
            warnings.add("Legacy infobase.db_type/db_path fields are present"); //$NON-NLS-1$
        }
    }

    private static void applyDefaults(QaConfig config, String fallbackProjectName, List<String> appliedChanges) {
        QaConfig defaults = QaConfig.defaultConfig(fallbackProjectName);

        if (config.edt.use_runtime == null) {
            config.edt.use_runtime = defaults.edt.use_runtime;
            appliedChanges.add("Set edt.use_runtime to default true"); //$NON-NLS-1$
        }
        if (isBlank(config.edt.project_name) && !isBlank(fallbackProjectName)) {
            config.edt.project_name = fallbackProjectName;
            appliedChanges.add("Filled edt.project_name from current project"); //$NON-NLS-1$
        }
        if (config.test_runner.use_test_manager == null) {
            config.test_runner.use_test_manager = defaults.test_runner.use_test_manager;
            appliedChanges.add("Set test_runner.use_test_manager to default true"); //$NON-NLS-1$
        }
        if (config.test_runner.timeout_seconds == null || config.test_runner.timeout_seconds.intValue() <= 0) {
            config.test_runner.timeout_seconds = defaults.test_runner.timeout_seconds;
            appliedChanges.add("Set test_runner.timeout_seconds to default 300"); //$NON-NLS-1$
        }
        if (isBlank(config.test_runner.unknown_steps_mode)) {
            config.test_runner.unknown_steps_mode = defaults.test_runner.unknown_steps_mode;
            appliedChanges.add("Set test_runner.unknown_steps_mode to default warn"); //$NON-NLS-1$
        }
        if (isBlank(config.paths.features_dir)) {
            config.paths.features_dir = defaults.paths.features_dir;
            appliedChanges.add("Filled paths.features_dir with tests/features"); //$NON-NLS-1$
        }
        if (isBlank(config.paths.steps_dir) && !isBlank(config.paths.libraries_dir)) {
            config.paths.steps_dir = config.paths.libraries_dir;
            appliedChanges.add("Mapped paths.libraries_dir to paths.steps_dir"); //$NON-NLS-1$
        }
        if (isBlank(config.paths.steps_dir)) {
            config.paths.steps_dir = defaults.paths.steps_dir;
            appliedChanges.add("Filled paths.steps_dir with tests/steps"); //$NON-NLS-1$
        }
        if (isBlank(config.paths.results_dir)) {
            config.paths.results_dir = defaults.paths.results_dir;
            appliedChanges.add("Filled paths.results_dir with tests/qa/results"); //$NON-NLS-1$
        }
        if (config.vanessa.junit_report_enabled == null) {
            config.vanessa.junit_report_enabled = defaults.vanessa.junit_report_enabled;
            appliedChanges.add("Set vanessa.junit_report_enabled to default true"); //$NON-NLS-1$
        }
        if (config.vanessa.screenshots_on_failure == null) {
            config.vanessa.screenshots_on_failure = defaults.vanessa.screenshots_on_failure;
            appliedChanges.add("Set vanessa.screenshots_on_failure to default true"); //$NON-NLS-1$
        }
        if (config.vanessa.close_test_client_after_run == null) {
            config.vanessa.close_test_client_after_run = defaults.vanessa.close_test_client_after_run;
            appliedChanges.add("Set vanessa.close_test_client_after_run to default true"); //$NON-NLS-1$
        }
        if (isBlank(config.vanessa.epf_path) && looksLikeEpfFile(config.vanessa.path_to_va)) {
            config.vanessa.epf_path = config.vanessa.path_to_va;
            appliedChanges.add("Copied vanessa.path_to_va into vanessa.epf_path"); //$NON-NLS-1$
        }
        if (config.test_clients.isEmpty()
                && (Boolean.TRUE.equals(config.edt.use_runtime)
                        || Boolean.TRUE.equals(config.test_runner.use_test_manager))) {
            config.test_clients.add(copyClient(defaults.test_clients.get(0)));
            appliedChanges.add("Added default TestClient definition"); //$NON-NLS-1$
        }
    }

    private static boolean detectIncompleteState(QaConfig config, boolean useEdtRuntime,
            boolean useTestManager, List<String> warnings) {
        boolean incomplete = false;
        if (isBlank(config.paths.features_dir)) {
            warnings.add("paths.features_dir is missing"); //$NON-NLS-1$
            incomplete = true;
        }
        if (isBlank(config.paths.results_dir)) {
            warnings.add("paths.results_dir is missing"); //$NON-NLS-1$
            incomplete = true;
        }
        if (useEdtRuntime && isBlank(config.edt.project_name)) {
            warnings.add("edt.project_name is missing for EDT runtime mode"); //$NON-NLS-1$
            incomplete = true;
        }
        if (useTestManager && config.test_clients.isEmpty()) {
            warnings.add("test_clients is empty for TestManager mode"); //$NON-NLS-1$
            incomplete = true;
        }
        if (!isBlank(config.vanessa.path_to_va) && isBlank(config.vanessa.epf_path)
                && !looksLikeEpfFile(config.vanessa.path_to_va)) {
            warnings.add("Legacy vanessa.path_to_va could not be converted to vanessa.epf_path automatically"); //$NON-NLS-1$
            incomplete = true;
        }
        return incomplete;
    }

    private static QaConfig.TestClient copyClient(QaConfig.TestClient source) {
        QaConfig.TestClient copy = new QaConfig.TestClient();
        if (source == null) {
            return copy;
        }
        copy.name = source.name;
        copy.alias = source.alias;
        copy.type = source.type;
        copy.ib_connection = source.ib_connection;
        copy.host = source.host;
        copy.port = source.port;
        copy.additional = source.additional;
        return copy;
    }

    private static boolean looksLikeEpfFile(String value) {
        return !isBlank(value) && value.trim().toLowerCase().endsWith(".epf"); //$NON-NLS-1$
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static final class MigrationReport {
        private final QaConfig migratedConfig;
        private final boolean changed;
        private final boolean legacyDetected;
        private final boolean incomplete;
        private final List<String> warnings;
        private final List<String> appliedChanges;

        private MigrationReport(QaConfig migratedConfig, boolean changed, boolean legacyDetected, boolean incomplete,
                List<String> warnings, List<String> appliedChanges) {
            this.migratedConfig = migratedConfig;
            this.changed = changed;
            this.legacyDetected = legacyDetected;
            this.incomplete = incomplete;
            this.warnings = List.copyOf(warnings);
            this.appliedChanges = List.copyOf(appliedChanges);
        }

        public QaConfig migratedConfig() {
            return migratedConfig;
        }

        public boolean changed() {
            return changed;
        }

        public boolean legacyDetected() {
            return legacyDetected;
        }

        public boolean incomplete() {
            return incomplete;
        }

        public List<String> warnings() {
            return Collections.unmodifiableList(warnings);
        }

        public List<String> appliedChanges() {
            return Collections.unmodifiableList(appliedChanges);
        }
    }
}
