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
            return config == null ? new QaConfig() : config;
        } catch (JsonSyntaxException e) {
            throw new IOException("Invalid QA config JSON: " + e.getMessage(), e);
        }
    }

    public static QaConfig defaultConfig(String projectName) {
        QaConfig config = new QaConfig();
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
        return config;
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

    public static class Platform {
        public String bin_path;
    }

    public static class Vanessa {
        public String epf_path;
        public String params_template;
        public String steps_catalog;
        public Boolean quiet_install_ext;
        public Boolean show_main_form;
    }

    public static class TestManager {
        public String ib_connection;
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
    }

    public static class Edt {
        public Boolean use_runtime;
        public String project_name;
    }

    public static class Infobase {
        public String ib_connection;
    }
}
