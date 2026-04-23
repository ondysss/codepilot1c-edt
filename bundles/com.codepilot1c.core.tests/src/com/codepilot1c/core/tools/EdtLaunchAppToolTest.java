package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.tools.workspace.EdtLaunchAppTool;
import com.codepilot1c.core.edt.runtime.EdtLaunchConfigurationService;
import com.codepilot1c.core.edt.runtime.EdtLaunchContextBuilder;
import com.codepilot1c.core.edt.runtime.EdtLaunchProcessRegistry;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtResolvedLaunchContext;
import com.codepilot1c.core.edt.runtime.EdtResolvedLaunchInputs;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class EdtLaunchAppToolTest {

    @Test
    public void returnsDryRunCommand() throws Exception {
        File workspaceRoot = Files.createTempDirectory("edt-launch-tool").toFile(); //$NON-NLS-1$
        EdtLaunchAppTool tool = new TestEdtLaunchAppTool(
                new StubProjectResolver(workspaceRoot),
                new StubLaunchContextBuilder(workspaceRoot),
                new StubRuntimeService(),
                ProcessBuilder::start,
                EdtLaunchProcessRegistry.getInstance(),
                workspaceRoot);

        ToolResult result = tool.execute(Map.of(
                "project_name", "Demo", //$NON-NLS-1$ //$NON-NLS-2$
                "dry_run", Boolean.TRUE //$NON-NLS-1$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("dry_run", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.getAsJsonArray("command").size() > 0); //$NON-NLS-1$
    }

    @Test
    public void waitsForShortLivedProcess() throws Exception {
        File workspaceRoot = Files.createTempDirectory("edt-launch-tool-wait").toFile(); //$NON-NLS-1$
        EdtLaunchAppTool tool = new TestEdtLaunchAppTool(
                new StubProjectResolver(workspaceRoot),
                new StubLaunchContextBuilder(workspaceRoot),
                new StubRuntimeService(),
                ProcessBuilder::start,
                EdtLaunchProcessRegistry.getInstance(),
                workspaceRoot);

        ToolResult result = tool.execute(Map.of(
                "project_name", "Demo", //$NON-NLS-1$ //$NON-NLS-2$
                "wait_for_exit", Boolean.TRUE, //$NON-NLS-1$
                "timeout_s", Integer.valueOf(30) //$NON-NLS-1$
        )).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals("completed", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, json.get("exit_code").getAsInt()); //$NON-NLS-1$
        assertFalse(json.get("timed_out").getAsBoolean()); //$NON-NLS-1$
    }

    private static class TestEdtLaunchAppTool extends EdtLaunchAppTool {
        private final File workspaceRoot;

        TestEdtLaunchAppTool(EdtProjectResolver projectResolver, EdtLaunchContextBuilder contextBuilder,
                EdtRuntimeService runtimeService, ProcessStarter processStarter,
                EdtLaunchProcessRegistry processRegistry, File workspaceRoot) {
            super(projectResolver, contextBuilder, runtimeService, processStarter, processRegistry);
            this.workspaceRoot = workspaceRoot;
        }

        @Override
        protected File getWorkspaceRoot() {
            return workspaceRoot;
        }
    }

    private static class StubProjectResolver extends EdtProjectResolver {
        private final File workspaceRoot;

        StubProjectResolver(File workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        @Override
        public EdtResolvedLaunchInputs resolveLaunchInputs(String projectName, File ignoredWorkspaceRoot) {
            return new EdtResolvedLaunchInputs(
                    workspaceRoot,
                    projectName,
                    null,
                    new EdtLaunchConfigurationService.LaunchConfigurationSettings(
                            new File(workspaceRoot, "test.launch"), "test", //$NON-NLS-1$ //$NON-NLS-2$
                            EdtLaunchConfigurationService.RUNTIME_CLIENT_TYPE,
                            projectName, false, null, "8.3.25.1", false, null, true, false, true), //$NON-NLS-1$
                    "8.3.25.1", false, null, null); //$NON-NLS-1$
        }
    }

    private static class StubLaunchContextBuilder extends EdtLaunchContextBuilder {
        private final File workspaceRoot;

        StubLaunchContextBuilder(File workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        @Override
        public EdtResolvedLaunchContext build(EdtResolvedLaunchInputs inputs) {
            return new EdtResolvedLaunchContext(
                    workspaceRoot,
                    inputs.projectName(),
                    new File(workspaceRoot, "test.launch"), //$NON-NLS-1$
                    "8.3.25.1", false, null, new File(javaBin()), null); //$NON-NLS-1$
        }
    }

    private static class StubRuntimeService extends EdtRuntimeService {
        @Override
        public ProcessBuilder buildEnterpriseLaunchProcess(EdtResolvedLaunchContext context,
                String additionalParameters, File logFile) {
            return new ProcessBuilder(javaBin(), "-version"); //$NON-NLS-1$
        }
    }

    private static String javaBin() {
        File home = new File(System.getProperty("java.home")); //$NON-NLS-1$
        File bin = new File(home, "bin/java"); //$NON-NLS-1$
        return bin.getAbsolutePath();
    }
}
