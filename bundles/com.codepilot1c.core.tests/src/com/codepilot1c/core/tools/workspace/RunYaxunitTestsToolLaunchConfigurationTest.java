package com.codepilot1c.core.tools.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder.ThickClientMode;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Exercises the real tool execution and launch-file parser without starting a client. */
public class RunYaxunitTestsToolLaunchConfigurationTest {
    private static final String PROJECT = "ExamProject";
    private static final String FACTORY_PROPERTY = "javax.xml.parsers.DocumentBuilderFactory";

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private String previousFactory;
    private File workspace;
    private CapturingRuntime runtime;
    private List<String> command;

    @Before
    public void setUp() throws IOException {
        // The standalone EDT classpath also contains an old Xerces provider. Use the
        // JDK provider so secure XML features are the same as in the EDT OSGi bundle.
        previousFactory = System.getProperty(FACTORY_PROPERTY);
        System.setProperty(FACTORY_PROPERTY,
                "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");
        workspace = temporary.newFolder("workspace");
        runtime = new CapturingRuntime();
    }

    @After
    public void restoreXmlProvider() {
        if (previousFactory == null) {
            System.clearProperty(FACTORY_PROPERTY);
        } else {
            System.setProperty(FACTORY_PROPERTY, previousFactory);
        }
    }

    @Test
    public void usesExplicitVersionMaskFromMatchingLaunchProfile() throws Exception {
        writeLaunch(false, "8.3.27");
        execute();
        assertEquals("8.3.27", runtime.versionMask);
        assertNotNull(command);
        assertTrue(command.get(0).contains("8.3.27"));
    }

    @Test
    public void preservesExplicitBuildNumber() throws Exception {
        writeLaunch(false, "8.3.27.1989");
        execute();
        assertEquals("8.3.27.1989", runtime.versionMask);
        assertTrue(command.get(0).contains("8.3.27.1989"));
    }

    @Test
    public void automaticProfileDoesNotPinStoredVersion() throws Exception {
        writeLaunch(true, "8.3.27");
        execute();
        assertTrue(runtime.called);
        assertNull(runtime.versionMask);
        assertNotNull(command);
    }

    @Test
    public void missingProfilePreservesAutomaticRuntimeSelection() throws Exception {
        execute();
        assertTrue(runtime.called);
        assertNull(runtime.versionMask);
        assertNotNull(command);
    }

    @Test
    public void invalidExplicitVersionRefusesToStartAnotherRuntime() throws Exception {
        writeLaunch(false, "");
        JsonObject result = execute();
        assertFalse(runtime.called);
        assertNull(command);
        assertEquals("RUNTIME_VERSION_NOT_FOUND", result.get("error_code").getAsString());
    }

    @Test
    public void unreadableLaunchProfileRefusesToStartAnotherRuntime() throws Exception {
        File launch = writeLaunch(false, "8.3.27");
        Files.writeString(launch.toPath(), "<launchConfiguration>", StandardCharsets.UTF_8);
        JsonObject result = execute();
        assertFalse(runtime.called);
        assertNull(command);
        assertTrue(result.get("message").getAsString().contains("launch configuration"));
    }

    private File writeLaunch(boolean automatic, String version) throws IOException {
        File launches = new File(workspace, ".metadata/.plugins/org.eclipse.debug.core/.launches");
        Files.createDirectories(launches.toPath());
        File launch = new File(launches, PROJECT + ".launch");
        String installation = version.isEmpty() ? "" : "runtime:EnterprisePlatform=" + version;
        Files.writeString(launch.toPath(), """
                <launchConfiguration type="com._1c.g5.v8.dt.launching.core.RuntimeClient">
                  <stringAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME" value="%s"/>
                  <stringAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION" value="%s"/>
                  <booleanAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION_USE_AUTO" value="%s"/>
                </launchConfiguration>
                """.formatted(PROJECT, installation, automatic), StandardCharsets.UTF_8);
        return launch;
    }

    private JsonObject execute() throws Exception {
        EdtProjectResolver resolver = new EdtProjectResolver() {
            @Override
            public InfobaseReference resolveInfobase(String projectName, File workspaceRoot) {
                assertEquals(PROJECT, projectName);
                assertEquals(workspace, workspaceRoot);
                return null;
            }
        };
        RunYaxunitTestsTool tool = new RunYaxunitTestsTool(resolver, runtime, builder -> {
            command = List.copyOf(builder.command());
            throw new IOException("Client intentionally not started by unit test");
        }) {
            @Override
            protected File getWorkspaceRoot() {
                return workspace;
            }
        };
        ToolResult result = tool.doExecute(new ToolParameters(Map.of("project_name", PROJECT)))
                .get(10, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        return JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
    }

    private static class CapturingRuntime extends EdtRuntimeService {
        private String versionMask;
        private boolean called;

        @Override
        public RuntimeExecutionCommandBuilder buildTestManagerCommand(String projectName, File epfPath,
                File vaParamsPath, File workspaceRoot, boolean showMainForm, boolean quietInstall,
                boolean clearStepsCache, File logFile, String versionMask) {
            return capture(versionMask, workspaceRoot);
        }

        // Fork-only overload; deliberately no @Override so the same test binary
        // also runs against upstream and both pre-fix baselines.
        public RuntimeExecutionCommandBuilder buildTestManagerCommand(String projectName, File epfPath,
                File vaParamsPath, File workspaceRoot, boolean showMainForm, boolean quietInstall,
                boolean clearStepsCache, File logFile, String versionMask,
                AccessSettings accessSettings, boolean applyFeaturePlayerStartupOption) {
            return capture(versionMask, workspaceRoot);
        }

        private RuntimeExecutionCommandBuilder capture(String versionMask, File workspaceRoot) {
            called = true;
            this.versionMask = versionMask;
            String selected = versionMask == null ? "newest" : versionMask;
            return new RuntimeExecutionCommandBuilder(new File(workspaceRoot, selected + "/1cv8"),
                    ThickClientMode.ENTERPRISE);
        }
    }
}
