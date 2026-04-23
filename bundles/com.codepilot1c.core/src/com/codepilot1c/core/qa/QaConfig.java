package com.codepilot1c.core.qa;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class QaConfig {

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_TEST_CLIENT_PORT = 48111;

    public Platform platform = new Platform();
    public Vanessa vanessa = new Vanessa();
    public TestManager test_manager = new TestManager();
    public TestRunner test_runner = new TestRunner();
    public List<TestClient> test_clients = new ArrayList<>();
    public Paths paths = new Paths();
    public Edt edt = new Edt();
    public Infobase infobase = new Infobase();
    public Boolean update_db;

    public static QaConfig load(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("QA config not found: " + (file == null ? "<null>" : file.getAbsolutePath()));
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            QaConfig config = GSON.fromJson(reader, QaConfig.class);
            return normalize(config == null ? new QaConfig() : config);
        } catch (JsonSyntaxException e) {
            throw new IOException("Invalid QA config JSON: " + e.getMessage(), e);
        }
    }

    public static QaConfig defaultConfig(String projectName) {
        QaConfig config = normalize(new QaConfig());
        if (config.edt == null) {
            config.edt = new Edt();
        }
        config.edt.use_runtime = Boolean.TRUE;
        config.edt.project_name = projectName;
        if (config.test_clients == null) {
            config.test_clients = new ArrayList<>();
        }
        if (config.test_clients.isEmpty()) {
            TestClient client = new TestClient();
            client.name = "TestClient"; //$NON-NLS-1$
            client.alias = "Test Client"; //$NON-NLS-1$
            client.type = "thin"; //$NON-NLS-1$
            client.host = "localhost"; //$NON-NLS-1$
            client.port = Integer.valueOf(DEFAULT_TEST_CLIENT_PORT);
            config.test_clients.add(client);
        }
        if (config.paths == null) {
            config.paths = new Paths();
        }
        config.paths.features_dir = "tests/features";
        config.paths.steps_dir = "tests/steps";
        config.paths.results_dir = "tests/qa/results";
        if (config.vanessa == null) {
            config.vanessa = new Vanessa();
        }
        config.vanessa.screenshots_on_failure = Boolean.TRUE;
        config.vanessa.close_test_client_after_run = Boolean.TRUE;
        config.vanessa.junit_report_enabled = Boolean.TRUE;
        if (config.test_runner == null) {
            config.test_runner = new TestRunner();
        }
        config.test_runner.use_test_manager = Boolean.TRUE;
        config.test_runner.timeout_seconds = Integer.valueOf(300);
        config.test_runner.unknown_steps_mode = QaRuntimeSettings.UNKNOWN_STEPS_MODE_WARN;
        return config;
    }

    public static QaConfig copyOf(QaConfig source) {
        if (source == null) {
            return normalize(new QaConfig());
        }
        return normalize(GSON.fromJson(GSON.toJson(source), QaConfig.class));
    }

    public void save(File file) throws IOException {
        if (file == null) {
            throw new IOException("QA config path is null");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(this);
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        boolean useEdt = edt != null && Boolean.TRUE.equals(edt.use_runtime);
        if (!useEdt) {
            if (platform == null || isBlank(platform.bin_path)) {
                errors.add("platform.bin_path is required");
            }
            if (test_manager == null || isBlank(test_manager.ib_connection)) {
                errors.add("test_manager.ib_connection is required");
            }
        } else if (edt == null || isBlank(edt.project_name)) {
            errors.add("edt.project_name is required when edt.use_runtime=true");
        }
        if (paths == null || isBlank(paths.features_dir)) {
            errors.add("paths.features_dir is required");
        }
        if (paths == null || isBlank(paths.results_dir)) {
            errors.add("paths.results_dir is required");
        }
        return Collections.unmodifiableList(errors);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static QaConfig normalize(QaConfig config) {
        if (config == null) {
            config = new QaConfig();
        }
        if (config.platform == null) {
            config.platform = new Platform();
        }
        if (config.vanessa == null) {
            config.vanessa = new Vanessa();
        }
        if (config.test_manager == null) {
            config.test_manager = new TestManager();
        }
        if (config.test_runner == null) {
            config.test_runner = new TestRunner();
        }
        if (config.test_clients == null) {
            config.test_clients = new ArrayList<>();
        }
        if (config.paths == null) {
            config.paths = new Paths();
        }
        if (config.edt == null) {
            config.edt = new Edt();
        }
        if (config.infobase == null) {
            config.infobase = new Infobase();
        }
        if (isBlank(config.paths.steps_dir) && !isBlank(config.paths.libraries_dir)) {
            config.paths.steps_dir = config.paths.libraries_dir;
        }
        if (config.test_clients.isEmpty()) {
            boolean runtimeOrManager = Boolean.TRUE.equals(config.edt.use_runtime)
                    || Boolean.TRUE.equals(config.test_runner.use_test_manager);
            if (runtimeOrManager) {
                TestClient client = new TestClient();
                client.name = "TestClient"; //$NON-NLS-1$
                client.alias = "Test Client"; //$NON-NLS-1$
                client.type = "thin"; //$NON-NLS-1$
                client.host = "localhost"; //$NON-NLS-1$
                client.port = Integer.valueOf(DEFAULT_TEST_CLIENT_PORT);
                config.test_clients.add(client);
            }
        }
        return config;
    }

    public static class Platform {
        public String bin_path;
    }

    public static class Vanessa {
        public String epf_path;
        public String params_template;
        public String steps_catalog;
        public Boolean quiet_install_ext;
        public Boolean show_main_form;
        public Boolean screenshots_on_failure;
        public Boolean close_test_client_after_run;
        public Boolean junit_report_enabled;
        public String path_to_va;
        public String version;
    }

    public static class TestManager {
        public String ib_connection;
    }

    public static class TestRunner {
        public Boolean use_test_manager;
        public Integer timeout_seconds;
        public Boolean auto_steps;
        public Boolean load_step_libraries;
        public String unknown_steps_mode;
    }

    public static class TestClient {
        public String name;
        public String alias;
        public String type;
        public String ib_connection;
        public String host;
        public Integer port;
        public String additional;
    }

    public static class Paths {
        public String features_dir;
        public String steps_dir;
        public String results_dir;
        public String snippets_dir;
        public String libraries_dir;
    }

    public static class Edt {
        public Boolean use_runtime;
        public String project_name;
    }

    public static class Infobase {
        public String ib_connection;
        public String platform_version;
        public String db_type;
        public String db_path;
    }
}
