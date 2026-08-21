package com.codepilot1c.core.tools.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.wst.server.core.IServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.codepilot1c.core.edt.observability.CommandResult;
import com.codepilot1c.core.edt.observability.CommandRunner;
import com.codepilot1c.core.edt.observability.EdtLogTailService;
import com.codepilot1c.core.edt.observability.EdtObservabilityGateway;
import com.codepilot1c.core.edt.observability.InfobaseLockService;
import com.codepilot1c.core.edt.observability.OneCProcessInspectionService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeGateway;
import com.codepilot1c.core.tools.ToolResult;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ObservabilityToolsTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void getOneCProcessesSerializesProcessSnapshotAndCanOmitPorts() {
        RecordingRunner runner = new RecordingRunner();
        runner.addStdout("ps -axo pid,ppid,user,command", //$NON-NLS-1$
                "86151 1 alex /opt/1cv8/8.3.27.2170/ibsrv /tmp/base"); //$NON-NLS-1$
        runner.addStdout("lsof -nP -iTCP -sTCP:LISTEN", //$NON-NLS-1$
                "ibsrv 86151 alex 12u IPv4 0 TCP *:1540 (LISTEN)"); //$NON-NLS-1$

        GetOneCProcessesTool tool = new GetOneCProcessesTool(
                new OneCProcessInspectionService(new EmptyObservabilityGateway(), runner));

        ToolResult result = tool.execute(Map.of("include_ports", Boolean.FALSE)).join(); //$NON-NLS-1$

        assertTrue(result.isSuccess());
        JsonObject json = json(result);
        assertOkEnvelope(json, "get_1c_processes"); //$NON-NLS-1$
        JsonObject process = json.getAsJsonObject("data").getAsJsonArray("processes").get(0).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(86151L, process.get("pid").getAsLong()); //$NON-NLS-1$
        assertEquals("ibsrv", process.get("process_type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(process.getAsJsonArray("infobase_paths").contains(JsonParser.parseString("\"/tmp/base\""))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, process.getAsJsonArray("ports").size()); //$NON-NLS-1$
        assertFalse(tool.requiresConfirmation());
        assertFalse(tool.isMutating());
        assertEquals("Lists 1C/EDT runtime processes with PID, parent, command line, ports, and inferred infobase paths.", //$NON-NLS-1$
                tool.getDescription());
        assertTrue(tool.getDescription().length() < 200);
    }

    @Test
    public void getInfobaseLocksOmitsEvidenceWhenRequested() {
        RecordingRunner runner = new RecordingRunner();
        runner.addStdout("ps -axo pid,ppid,user,command", //$NON-NLS-1$
                "86152 1 alex /opt/1cv8/8.3.27.2170/1cv8 DESIGNER /F/tmp/base"); //$NON-NLS-1$
        runner.addStdout("lsof -nP /tmp/base/1Cv8.1CD", //$NON-NLS-1$
                """
                COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
                1cv8    86152 alex   14u   REG   1,4        0  42 /tmp/base/1Cv8.1CD
                """);
        GetInfobaseLocksTool tool = new GetInfobaseLocksTool(new InfobaseLockService(
                new EmptyObservabilityGateway(), runner));

        ToolResult result = tool.execute(Map.of(
                "path_or_connection", "/tmp/base", //$NON-NLS-1$ //$NON-NLS-2$
                "include_evidence", Boolean.FALSE)).join(); //$NON-NLS-1$

        assertTrue(result.isSuccess());
        JsonObject json = json(result);
        assertOkEnvelope(json, "get_infobase_locks"); //$NON-NLS-1$
        JsonObject lock = json.getAsJsonObject("data").getAsJsonObject("lock"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("configuration", lock.get("lock_kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, lock.getAsJsonArray("evidence").size()); //$NON-NLS-1$
        assertTrue(lock.getAsJsonArray("pids").contains(JsonParser.parseString("86152"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(tool.requiresConfirmation());
        assertFalse(tool.isMutating());
    }

    @Test
    public void standaloneStatusUsesNonBlockingGatewayAndNeutralDefaults() {
        IServer server = newServer("demo-standalone", IServer.STATE_STARTED); //$NON-NLS-1$
        StubStandaloneServerService standaloneService = new StubStandaloneServerService(List.of(server));
        GetStandaloneServerStatusTool tool = new GetStandaloneServerStatusTool(
                new EdtRuntimeGatewayStub(standaloneService),
                new OneCProcessInspectionService(new EmptyObservabilityGateway(), new RecordingRunner()),
                new InfobaseLockService(new EmptyObservabilityGateway(), new RecordingRunner()));

        ToolResult result = tool.execute(Map.of()).join();

        assertTrue(result.isSuccess());
        assertTrue(standaloneService.getServersCalled);
        JsonObject json = json(result);
        assertOkEnvelope(json, "get_standalone_server_status"); //$NON-NLS-1$
        JsonObject status = json.getAsJsonObject("data").getAsJsonArray("servers").get(0).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("demo-standalone", status.get("server_name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("started", status.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0L, status.get("pid").getAsLong()); //$NON-NLS-1$
        assertTrue(status.has("ports")); //$NON-NLS-1$
        assertTrue(status.has("config_path")); //$NON-NLS-1$
        assertTrue(status.has("infobase_path")); //$NON-NLS-1$
        assertTrue(status.has("debug_session")); //$NON-NLS-1$
        assertTrue(status.has("breakpoints_count")); //$NON-NLS-1$
        assertTrue(status.has("designer_or_import_session")); //$NON-NLS-1$
        assertTrue(status.has("related_processes")); //$NON-NLS-1$
        assertTrue(status.has("locks")); //$NON-NLS-1$
        assertTrue(status.has("last_errors")); //$NON-NLS-1$
        assertFalse(tool.requiresConfirmation());
        assertFalse(tool.isMutating());
    }

    @Test
    public void tailEdtLogsSearchesWorkspaceLogsAndFiltersByOpIdAndError() throws IOException {
        Path workspace = temporaryFolder.newFolder("workspace").toPath(); //$NON-NLS-1$
        Path metadataLog = workspace.resolve(".metadata/.log"); //$NON-NLS-1$
        Files.createDirectories(metadataLog.getParent());
        Files.writeString(metadataLog, """
                !ENTRY com.codepilot 4 0 2026-06-05 10:00:00.000
                !MESSAGE op_id=obs-1 pid=86151 infobase=/tmp/base import failed
                plain informational op_id=obs-2
                """, StandardCharsets.UTF_8);
        Path runLog = workspace.resolve(".codepilot/runs/edt_update_infobase/obs-1/update.log"); //$NON-NLS-1$
        Files.createDirectories(runLog.getParent());
        Files.writeString(runLog, """
                [INFO] op_id=obs-1 pid=86151 starting
                [ERROR] op_id=obs-1 pid=86151 lock conflict for /tmp/base
                """, StandardCharsets.UTF_8);

        TailEdtLogsTool tool = new TailEdtLogsTool(new EdtLogTailService(workspace));

        ToolResult result = tool.execute(Map.of(
                "op_id", "obs-1", //$NON-NLS-1$ //$NON-NLS-2$
                "errors_only", Boolean.TRUE, //$NON-NLS-1$
                "max_lines", Integer.valueOf(5))).join(); //$NON-NLS-1$

        assertTrue(result.isSuccess());
        JsonObject json = json(result);
        assertOkEnvelope(json, "tail_edt_logs"); //$NON-NLS-1$
        JsonArray lines = json.getAsJsonObject("data").getAsJsonArray("lines"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).getAsJsonObject().get("text").getAsString().contains("!MESSAGE")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(lines.get(1).getAsJsonObject().get("text").getAsString().contains("[ERROR]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(tool.requiresConfirmation());
        assertFalse(tool.isMutating());
    }

    private static JsonObject json(ToolResult result) {
        String payload = result.isSuccess() ? result.getContent() : result.getErrorMessage();
        return JsonParser.parseString(payload).getAsJsonObject();
    }

    private static void assertOkEnvelope(JsonObject json, String tool) {
        assertEquals("ok", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(tool, json.get("tool").getAsString()); //$NON-NLS-1$
        assertTrue(json.has("op_id")); //$NON-NLS-1$
        assertTrue(json.has("data")); //$NON-NLS-1$
    }

    private static IServer newServer(String name, int state) {
        return (IServer) Proxy.newProxyInstance(
                IServer.class.getClassLoader(),
                new Class<?>[] { IServer.class },
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) { //$NON-NLS-1$
                        return name;
                    }
                    if ("getServerState".equals(method.getName())) { //$NON-NLS-1$
                        return Integer.valueOf(state);
                    }
                    if ("getModules".equals(method.getName())) { //$NON-NLS-1$
                        return new org.eclipse.wst.server.core.IModule[0];
                    }
                    if ("toString".equals(method.getName())) { //$NON-NLS-1$
                        return "StubServer[" + name + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    return defaultReturn(method);
                });
    }

    private static Object defaultReturn(Method method) {
        Class<?> ret = method.getReturnType();
        if (ret == boolean.class) {
            return Boolean.FALSE;
        }
        if (ret == int.class) {
            return Integer.valueOf(0);
        }
        if (ret == long.class) {
            return Long.valueOf(0L);
        }
        if (ret.isPrimitive()) {
            return Integer.valueOf(0);
        }
        return null;
    }

    private static class EmptyObservabilityGateway extends EdtObservabilityGateway {
        @Override
        public List<ProcessHandle> allProcesses() {
            return List.of();
        }
    }

    private static class RecordingRunner implements CommandRunner {
        private final java.util.Map<String, String> stdoutByCommand = new java.util.HashMap<>();
        private final List<List<String>> commands = new ArrayList<>();

        void addStdout(String command, String stdout) {
            stdoutByCommand.put(command, stdout);
        }

        @Override
        public CommandResult run(List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            String stdout = stdoutByCommand.get(String.join(" ", command)); //$NON-NLS-1$
            if (stdout == null) {
                return new CommandResult(1, "", "", false); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return new CommandResult(0, stdout, "", false); //$NON-NLS-1$
        }
    }

    private static class EdtRuntimeGatewayStub extends EdtRuntimeGateway {
        private final IStandaloneServerService service;

        EdtRuntimeGatewayStub(IStandaloneServerService service) {
            this.service = service;
        }

        @Override
        public IStandaloneServerService peekStandaloneServerService() {
            return service;
        }
    }

    private static final class StubStandaloneServerService implements IStandaloneServerService {
        private final List<IServer> servers;
        boolean getServersCalled;

        StubStandaloneServerService(List<IServer> servers) {
            this.servers = servers;
        }

        @Override
        public List<IServer> getServers() {
            getServersCalled = true;
            return servers;
        }

        @Override
        public List<org.eclipse.wst.server.core.IRuntime> getRuntimes() {
            return List.of();
        }

        @Override
        public org.eclipse.wst.server.core.IServer createServer(
                org.eclipse.wst.server.core.IRuntime runtime, IProgressMonitor monitor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com._1c.g5.v8.dt.common.Pair<IServer, StandaloneServerInfobase> createServerWithInfobase(
                String platformVersion, String projectName,
                com._1c.g5.v8.dt.platform.services.model.InfobaseReference infobase, int clusterPort,
                String clusterRegistryDirectory, String publicationPath, IProgressMonitor monitor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<IServer> getServer(StandaloneServerInfobase infobase) {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.URI getDesignerUrl(StandaloneServerInfobase infobase) {
            return null;
        }

        @Override
        public java.net.URI getInfobaseUrl(StandaloneServerInfobase infobase) {
            return null;
        }

        @Override
        public org.eclipse.core.runtime.IStatus validateRuntimeInstallation(
                com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation installation) {
            return null;
        }

        @Override
        public java.util.Optional<org.eclipse.wst.server.core.IRuntime> findRuntime(String platformVersion,
                IProgressMonitor monitor) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerRuntime> //
                getStandaloneServerRuntime(org.eclipse.wst.server.core.IRuntime runtime,
                        IProgressMonitor monitor) {
            return java.util.Optional.empty();
        }

        @Override
        public java.nio.file.Path getServerLocation(IServer server) {
            return null;
        }

        @Override
        public java.nio.file.Path getServerDataLocation(IServer server) {
            return null;
        }

        @Override
        public String getServerVersion(IServer server) {
            return ""; //$NON-NLS-1$
        }

        @Override
        public org.eclipse.core.runtime.IStatus validateServerLocation(java.nio.file.Path path) {
            return null;
        }

        @Override
        public org.eclipse.core.runtime.IStatus deleteServer(IServer server, IProgressMonitor monitor) {
            return null;
        }

        @Override
        public org.eclipse.core.runtime.IStatus startServer(IServer server, String mode, IProgressMonitor monitor) {
            return null;
        }

        @Override
        public org.eclipse.core.runtime.IStatus stopServer(IServer server, IProgressMonitor monitor) {
            return null;
        }

        @Override
        public void execServerOperation(IServer server,
                java.util.function.Consumer<IServer.IOperationListener> consumer,
                IProgressMonitor monitor) {
            // no-op
        }

        @Override
        public com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerBehaviourDelegate //
                findBehaviourDelegate(IServer server) {
            return null;
        }

        @Override
        public com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerDelegate findServerDelegate(
                IServer server) {
            return null;
        }

        @Override
        public boolean isStandaloneServer(IServer server) {
            return true;
        }
    }
}
