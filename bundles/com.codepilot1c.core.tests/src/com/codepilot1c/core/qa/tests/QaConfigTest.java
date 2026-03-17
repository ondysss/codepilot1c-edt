package com.codepilot1c.core.qa.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

import com.codepilot1c.core.qa.QaConfig;
import com.codepilot1c.core.qa.QaConfigMigration;
import com.codepilot1c.core.qa.QaRuntimeSettings;

public class QaConfigTest {

    @Test
    public void defaultConfigCreatesRunnableTestClient() {
        QaConfig config = QaConfig.defaultConfig("test"); //$NON-NLS-1$

        assertNotNull(config.test_clients);
        assertEquals(1, config.test_clients.size());
        assertNotNull(config.test_clients.get(0).port);
        assertEquals(Integer.valueOf(48111), config.test_clients.get(0).port);
        assertTrue(Boolean.TRUE.equals(config.vanessa.screenshots_on_failure));
        assertTrue(Boolean.TRUE.equals(config.vanessa.close_test_client_after_run));
        assertTrue(Boolean.TRUE.equals(config.vanessa.junit_report_enabled));
        assertTrue(Boolean.TRUE.equals(config.test_runner.use_test_manager));
        assertEquals(Integer.valueOf(300), config.test_runner.timeout_seconds);
        assertEquals("warn", config.test_runner.unknown_steps_mode); //$NON-NLS-1$
    }

    @Test
    public void saveAndLoadPreservesVanessaRunSettings() throws Exception {
        QaConfig config = QaConfig.defaultConfig("test"); //$NON-NLS-1$
        config.vanessa.epf_path = "/tmp/vanessa.epf"; //$NON-NLS-1$
        config.vanessa.params_template = "tests/qa/va-template.json"; //$NON-NLS-1$
        config.vanessa.screenshots_on_failure = Boolean.FALSE;
        config.vanessa.close_test_client_after_run = Boolean.FALSE;
        config.vanessa.junit_report_enabled = Boolean.FALSE;

        File tempDir = Files.createTempDirectory("qa-config-test").toFile(); //$NON-NLS-1$
        File configFile = new File(tempDir, "qa-config.json"); //$NON-NLS-1$
        config.save(configFile);

        QaConfig loaded = QaConfig.load(configFile);
        assertEquals("/tmp/vanessa.epf", loaded.vanessa.epf_path); //$NON-NLS-1$
        assertEquals("tests/qa/va-template.json", loaded.vanessa.params_template); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, loaded.vanessa.screenshots_on_failure);
        assertEquals(Boolean.FALSE, loaded.vanessa.close_test_client_after_run);
        assertEquals(Boolean.FALSE, loaded.vanessa.junit_report_enabled);
    }

    @Test
    public void loadLegacyConfigMapsLibrariesDirAndTestRunner() throws Exception {
        File tempDir = Files.createTempDirectory("qa-config-legacy").toFile(); //$NON-NLS-1$
        File configFile = new File(tempDir, "qa-config.json"); //$NON-NLS-1$
        Files.writeString(configFile.toPath(), """
                {
                  "vanessa": {
                    "path_to_va": "C:/VA",
                    "version": "latest",
                    "epf_path": "C:/VA/vanessa.epf"
                  },
                  "test_runner": {
                    "use_test_manager": false,
                    "timeout_seconds": 900
                  },
                  "paths": {
                    "features_dir": "./tests/features",
                    "results_dir": "./tests/results",
                    "libraries_dir": "./tests/lib"
                  }
                }
                """);

        QaConfig loaded = QaConfig.load(configFile);
        assertEquals("C:/VA", loaded.vanessa.path_to_va); //$NON-NLS-1$
        assertEquals("latest", loaded.vanessa.version); //$NON-NLS-1$
        assertEquals("C:/VA/vanessa.epf", loaded.vanessa.epf_path); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, loaded.test_runner.use_test_manager);
        assertEquals(Integer.valueOf(900), loaded.test_runner.timeout_seconds);
        assertEquals("./tests/lib", loaded.paths.libraries_dir); //$NON-NLS-1$
        assertEquals("./tests/lib", loaded.paths.steps_dir); //$NON-NLS-1$
    }

    @Test
    public void migrationAddsMissingDefaultsWithoutDroppingLegacyFields() {
        QaConfig config = new QaConfig();
        config.vanessa.path_to_va = "C:/VA"; //$NON-NLS-1$
        config.paths.features_dir = "tests/features"; //$NON-NLS-1$
        config.paths.results_dir = "tests/results"; //$NON-NLS-1$
        config.test_runner.use_test_manager = Boolean.TRUE;

        QaConfigMigration.MigrationReport report = QaConfigMigration.analyze(config, "DemoProject", true, true); //$NON-NLS-1$

        assertTrue(report.legacyDetected());
        assertTrue(report.changed());
        assertEquals("DemoProject", report.migratedConfig().edt.project_name); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, report.migratedConfig().vanessa.junit_report_enabled);
        assertEquals(Boolean.TRUE, report.migratedConfig().vanessa.screenshots_on_failure);
        assertEquals(Boolean.TRUE, report.migratedConfig().vanessa.close_test_client_after_run);
        assertFalse(report.migratedConfig().test_clients.isEmpty());
        assertEquals("C:/VA", report.migratedConfig().vanessa.path_to_va); //$NON-NLS-1$
    }

    @Test
    public void runtimeSettingsPreferProjectEpfPathOverPreferences() {
        QaConfig config = QaConfig.defaultConfig("Demo"); //$NON-NLS-1$
        config.vanessa.epf_path = "tests/qa/vanessa.epf"; //$NON-NLS-1$

        assertTrue(QaRuntimeSettings.hasConfiguredEpfPath(config, "")); //$NON-NLS-1$
        assertEquals("project_config", QaRuntimeSettings.describeEpfSource(config, "/pref/path.epf")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void runtimeSettingsDefaultUnknownStepsModeToWarn() {
        QaConfig config = new QaConfig();

        assertEquals("warn", QaRuntimeSettings.resolveUnknownStepsMode(config)); //$NON-NLS-1$
    }

    @Test
    public void runtimeSettingsResolveTemplateAndCatalogSources() throws Exception {
        File workspace = Files.createTempDirectory("qa-runtime-settings").toFile(); //$NON-NLS-1$
        File template = new File(workspace, "tests/qa/va-base.json"); //$NON-NLS-1$
        template.getParentFile().mkdirs();
        Files.writeString(template.toPath(), "{}"); //$NON-NLS-1$
        File catalog = new File(workspace, "tests/va/steps_catalog.json"); //$NON-NLS-1$
        catalog.getParentFile().mkdirs();
        Files.writeString(catalog.toPath(), "{}"); //$NON-NLS-1$

        QaConfig config = QaConfig.defaultConfig("Demo"); //$NON-NLS-1$
        config.vanessa.params_template = "tests/qa/va-base.json"; //$NON-NLS-1$

        assertEquals("project_config", QaRuntimeSettings.describeParamsTemplateSource(config, workspace)); //$NON-NLS-1$
        assertEquals(template.getAbsolutePath(),
                QaRuntimeSettings.resolveParamsTemplate(config, workspace).getAbsolutePath());
        assertEquals("workspace_default",
                QaRuntimeSettings.describeStepsCatalogSource(config, workspace, true)); //$NON-NLS-1$
        assertEquals(catalog.getAbsolutePath(),
                QaRuntimeSettings.resolveStepsCatalog(config, workspace).getAbsolutePath());
    }
}
