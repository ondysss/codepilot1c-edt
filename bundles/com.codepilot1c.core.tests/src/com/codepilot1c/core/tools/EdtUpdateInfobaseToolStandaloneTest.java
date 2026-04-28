package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtInfobaseUpdateException;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtRuntimeGateway;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.JobState;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.JobStatus;
import com.codepilot1c.core.tools.workspace.EdtUpdateInfobaseTool;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Regression tests for issue #28: edt_update_infobase must resolve infobases for projects bound
 * through the standalone-server plugin, not only through the file-binding
 * {@link IInfobaseAssociationManager}.
 */
public class EdtUpdateInfobaseToolStandaloneTest {

    @Test
    public void resolveDefaultInfobaseFallsBackToStandaloneBinding() {
        IProject project = newProjectProxy("StandaloneDemo"); //$NON-NLS-1$
        InfobaseReference expected = newInfobaseReferenceProxy();
        StandaloneServerInfobase module = newStandaloneModule(project, "StandaloneDemo", expected); //$NON-NLS-1$
        IServer server = newServerWithModules(module);
        IStandaloneServerService service = new StubStandaloneServerService(List.of(server));

        EdtRuntimeService runtimeService = new EdtRuntimeService(
                new StubGateway(project, /* associationEmpty */ true, service));

        InfobaseReference actual = runtimeService.resolveDefaultInfobase("StandaloneDemo"); //$NON-NLS-1$

        assertSame("Fallback must return the InfobaseReference adapted from the standalone module", //$NON-NLS-1$
                expected, actual);
    }

    @Test
    public void resolveDefaultInfobasePrefersFileBindingOverStandalone() {
        IProject project = newProjectProxy("FileDemo"); //$NON-NLS-1$
        InfobaseReference fileBound = newInfobaseReferenceProxy();
        InfobaseReference standaloneBound = newInfobaseReferenceProxy();
        StandaloneServerInfobase module = newStandaloneModule(project, "FileDemo", standaloneBound); //$NON-NLS-1$
        IServer server = newServerWithModules(module);
        StubStandaloneServerService service = new StubStandaloneServerService(List.of(server));

        EdtRuntimeService runtimeService = new EdtRuntimeService(
                new StubGateway(project, fileBound, service));

        InfobaseReference actual = runtimeService.resolveDefaultInfobase("FileDemo"); //$NON-NLS-1$

        assertSame("File-bound projects must use the IInfobaseAssociationManager result", //$NON-NLS-1$
                fileBound, actual);
        assertFalse("Standalone fallback must not be consulted when file binding succeeds", //$NON-NLS-1$
                service.getServersCalled);
    }

    @Test
    public void resolveDefaultInfobaseFallsBackToStandaloneWhenPrimaryThrows() {
        IProject project = newProjectProxy("PrimaryThrows"); //$NON-NLS-1$
        InfobaseReference expected = newInfobaseReferenceProxy();
        StandaloneServerInfobase module = newStandaloneModule(project, "PrimaryThrows", expected); //$NON-NLS-1$
        IServer server = newServerWithModules(module);
        IStandaloneServerService service = new StubStandaloneServerService(List.of(server));

        EdtRuntimeService runtimeService = new EdtRuntimeService(
                new ThrowingPrimaryGateway(project, service,
                        new RuntimeException("simulated primary-path failure"))); //$NON-NLS-1$

        InfobaseReference actual = runtimeService.resolveDefaultInfobase("PrimaryThrows"); //$NON-NLS-1$

        assertSame("Standalone fallback must fire when the primary file-binding path throws", //$NON-NLS-1$
                expected, actual);
    }

    @Test
    public void resolveDefaultInfobasePropagatesPrimaryFailureWhenFallbackAlsoMisses() {
        IProject project = newProjectProxy("BothFail"); //$NON-NLS-1$
        RuntimeException primaryFailure = new RuntimeException("simulated primary-path failure"); //$NON-NLS-1$
        EdtRuntimeService runtimeService = new EdtRuntimeService(
                new ThrowingPrimaryGateway(project, new StubStandaloneServerService(List.of()),
                        primaryFailure));

        try {
            runtimeService.resolveDefaultInfobase("BothFail"); //$NON-NLS-1$
            fail("Expected IllegalStateException when both binding paths fail"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
            Throwable[] suppressed = e.getSuppressed();
            assertTrue("Original primary-path failure must be preserved as a suppressed exception", //$NON-NLS-1$
                    suppressed.length >= 1);
            assertSame("Suppressed entry must be the original primary failure", primaryFailure, //$NON-NLS-1$
                    suppressed[0]);
        }
    }

    @Test
    public void resolveDefaultInfobaseThrowsWhenNeitherBindingProvidesInfobase() {
        IProject project = newProjectProxy("Orphan"); //$NON-NLS-1$
        EdtRuntimeService runtimeService = new EdtRuntimeService(
                new StubGateway(project, /* associationEmpty */ true, new StubStandaloneServerService(List.of())));

        try {
            runtimeService.resolveDefaultInfobase("Orphan"); //$NON-NLS-1$
            fail("Expected IllegalStateException when no binding resolves"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
            assertTrue("Error message must identify the failing project", //$NON-NLS-1$
                    e.getMessage() != null && e.getMessage().contains("Orphan")); //$NON-NLS-1$
        }
    }

    @Test
    public void toolDryRunSucceedsForStandaloneBoundProject() throws Exception {
        File workspaceRoot = Files.createTempDirectory("edt-update-standalone").toFile(); //$NON-NLS-1$
        InfobaseReference resolved = newInfobaseReferenceProxy();
        EdtUpdateInfobaseTool tool = new StandaloneTestTool(
                new StubResolverReturning(resolved),
                new StubRuntimeService(),
                workspaceRoot);

        ToolResult result = tool.execute(Map.of(
                "project_name", "StandaloneDemo", //$NON-NLS-1$ //$NON-NLS-2$
                "dry_run", Boolean.TRUE //$NON-NLS-1$
        )).join();

        assertTrue("Tool must succeed when standalone fallback resolved the infobase", //$NON-NLS-1$
                result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("dry_run").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json.get("updated").getAsBoolean()); //$NON-NLS-1$
        assertEquals("StandaloneDemo", json.get("project_name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void asyncStandaloneUpdateClassifiesMissingSshProviderFailure() throws Exception {
        File workspaceRoot = Files.createTempDirectory("edt-update-standalone-async").toFile(); //$NON-NLS-1$
        InfobaseReference resolved = newInfobaseReferenceProxy();
        EdtUpdateInfobaseTool tool = new StandaloneTestTool(
                new StubResolverReturning(resolved),
                new MissingSshProviderRuntimeService(),
                workspaceRoot);

        ToolResult accepted = tool.execute(Map.of(
                "project_name", "StandaloneDemo", //$NON-NLS-1$ //$NON-NLS-2$
                "async", Boolean.TRUE //$NON-NLS-1$
        )).join();

        assertTrue(accepted.isSuccess());
        JsonObject acceptedJson = JsonParser.parseString(accepted.getContent()).getAsJsonObject();
        String jobId = acceptedJson.get("job_id").getAsString(); //$NON-NLS-1$
        JobStatus finished = pollForTerminal(BackgroundJobRegistry.getInstance(), jobId);

        assertEquals(JobState.DONE, finished.getState());
        assertNotNull(finished.getResult());
        JsonObject json = JsonParser.parseString(finished.getResult()).getAsJsonObject();
        assertEquals("error", json.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("UPDATE_FAILED", json.get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject details = json.getAsJsonObject("details"); //$NON-NLS-1$
        assertNotNull(details);
        assertTrue("missing details.provider in async error payload: " + finished.getResult(), //$NON-NLS-1$
                details.has("provider")); //$NON-NLS-1$
        assertEquals("ssh", details.get("provider").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("missing details.binding_source in async error payload: " + finished.getResult(), //$NON-NLS-1$
                details.has("binding_source")); //$NON-NLS-1$
        assertEquals("standalone_server", details.get("binding_source").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void standaloneUpdateUsesBoundInfobaseReferenceWhenAvailable() throws Exception {
        IProject project = newProjectProxy("StandaloneDemo"); //$NON-NLS-1$
        UUID infobaseId = UUID.randomUUID();
        InfobaseReference adapterFallback = newInfobaseReferenceProxy();
        InfobaseReference canonical = newInfobaseReferenceProxy();
        StandaloneServerInfobase module = newStandaloneModule(project, "StandaloneDemo", //$NON-NLS-1$
                infobaseId, "Standalone IB", adapterFallback); //$NON-NLS-1$
        RecordingSynchronizationManager syncManager = new RecordingSynchronizationManager();
        EdtRuntimeService runtimeService = new EdtRuntimeService(
                new CanonicalStandaloneGateway(project,
                        new StubStandaloneServerService(List.of(newServerWithModules(module))),
                        newInfobaseManagerReturning(infobaseId, "Standalone IB", canonical), //$NON-NLS-1$
                        syncManager));

        assertTrue(runtimeService.updateInfobase("StandaloneDemo", true, null)); //$NON-NLS-1$

        assertSame("EDT sync manager must receive the canonical IInfobaseManager reference", //$NON-NLS-1$
                canonical, syncManager.infobase);
        assertFalse("Canonical reference must differ from the standalone adapter fallback", //$NON-NLS-1$
                adapterFallback == syncManager.infobase);
    }

    @Test
    public void standaloneUpdateFallsBackToAdapterWhenUuidMissesEvenIfNameMatches() throws Exception {
        IProject project = newProjectProxy("StandaloneDemo"); //$NON-NLS-1$
        UUID infobaseId = UUID.randomUUID();
        InfobaseReference adapterFallback = newInfobaseReferenceProxy();
        InfobaseReference sameNameWrongReference = newInfobaseReferenceProxy();
        StandaloneServerInfobase module = newStandaloneModule(project, "StandaloneDemo", //$NON-NLS-1$
                infobaseId, "Standalone IB", adapterFallback); //$NON-NLS-1$
        RecordingSynchronizationManager syncManager = new RecordingSynchronizationManager();
        EdtRuntimeService runtimeService = new EdtRuntimeService(
                new CanonicalStandaloneGateway(project,
                        new StubStandaloneServerService(List.of(newServerWithModules(module))),
                        newInfobaseManagerReturning(UUID.randomUUID(), "Standalone IB", //$NON-NLS-1$
                                sameNameWrongReference),
                        syncManager));

        assertTrue(runtimeService.updateInfobase("StandaloneDemo", true, null)); //$NON-NLS-1$

        assertSame("UUID miss must not fall through to name lookup for a mutating update", //$NON-NLS-1$
                adapterFallback, syncManager.infobase);
        assertFalse("Same-name reference must not be used when standalone UUID does not match", //$NON-NLS-1$
                sameNameWrongReference == syncManager.infobase);
    }

    // --- helpers --------------------------------------------------------------------------------

    private static JobStatus pollForTerminal(BackgroundJobRegistry registry, String jobId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            JobStatus status = registry.getStatus(jobId).orElse(null);
            if (status != null
                    && (status.getState() == JobState.DONE || status.getState() == JobState.FAILED)) {
                return status;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Job " + jobId + " never reached terminal state"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static IProject newProjectProxy(String projectName) {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(),
                new Class<?>[] { IProject.class },
                new ProjectHandler(projectName));
    }

    private static InfobaseReference newInfobaseReferenceProxy() {
        return (InfobaseReference) Proxy.newProxyInstance(
                InfobaseReference.class.getClassLoader(),
                new Class<?>[] { InfobaseReference.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "StubInfobaseReference@" + System.identityHashCode(proxy); //$NON-NLS-1$ //$NON-NLS-2$
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                    case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                    default -> null;
                });
    }

    private static StandaloneServerInfobase newStandaloneModule(IProject project, String projectName,
            InfobaseReference adapter) {
        return newStandaloneModule(project, projectName, UUID.randomUUID(), projectName, adapter);
    }

    private static StandaloneServerInfobase newStandaloneModule(IProject project, String projectName,
            UUID infobaseId, String infobaseName, InfobaseReference adapter) {
        StandaloneServerInfobase module = new StandaloneServerInfobase(infobaseId) {
            @Override
            public IProject getProject() {
                return project;
            }

            @Override
            public String getProjectName() {
                return projectName;
            }

            @Override
            public UUID getInfobaseId() {
                return infobaseId;
            }

            @Override
            public String getName() {
                return infobaseName;
            }

            @SuppressWarnings("rawtypes")
            @Override
            public Object getAdapter(Class adapterType) {
                if (adapterType == InfobaseReference.class) {
                    return adapter;
                }
                return null;
            }
        };
        return module;
    }

    private static IInfobaseManager newInfobaseManagerReturning(UUID infobaseId, String infobaseName,
            InfobaseReference canonical) {
        return (IInfobaseManager) Proxy.newProxyInstance(
                IInfobaseManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseManager.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "findInfobaseByUuid" -> Optional.ofNullable( //$NON-NLS-1$
                            args != null && args.length == 1 && infobaseId.equals(args[0]) ? canonical : null);
                    case "findInfobaseByName" -> Optional.ofNullable( //$NON-NLS-1$
                            args != null && args.length == 1 && infobaseName.equals(args[0]) ? canonical : null);
                    case "toString" -> "StubInfobaseManager"; //$NON-NLS-1$ //$NON-NLS-2$
                    default -> defaultReturn(method);
                });
    }

    private static IServer newServerWithModules(IModule... modules) {
        return (IServer) Proxy.newProxyInstance(
                IServer.class.getClassLoader(),
                new Class<?>[] { IServer.class },
                (proxy, method, args) -> {
                    if ("getModules".equals(method.getName())) { //$NON-NLS-1$
                        return modules;
                    }
                    if ("getName".equals(method.getName())) { //$NON-NLS-1$
                        return "stub-server"; //$NON-NLS-1$
                    }
                    if ("toString".equals(method.getName())) { //$NON-NLS-1$
                        return "StubServer"; //$NON-NLS-1$
                    }
                    return defaultReturn(method);
                });
    }

    private static Object defaultReturn(Method method) {
        Class<?> ret = method.getReturnType();
        if (ret == boolean.class) {
            return Boolean.FALSE;
        }
        if (ret.isPrimitive()) {
            return Integer.valueOf(0);
        }
        return null;
    }

    private static final class ProjectHandler implements InvocationHandler {
        private final String name;

        ProjectHandler(String name) {
            this.name = name;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getName" -> name; //$NON-NLS-1$
                case "exists" -> Boolean.TRUE; //$NON-NLS-1$
                case "isOpen" -> Boolean.TRUE; //$NON-NLS-1$
                case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                case "toString" -> "StubProject[" + name + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                default -> defaultReturn(method);
            };
        }
    }

    private static final class StubGateway extends EdtRuntimeGateway {
        private final IProject project;
        private final boolean associationEmpty;
        private final InfobaseReference fileBoundInfobase;
        private final IStandaloneServerService standaloneService;

        StubGateway(IProject project, boolean associationEmpty, IStandaloneServerService standaloneService) {
            this.project = project;
            this.associationEmpty = associationEmpty;
            this.fileBoundInfobase = null;
            this.standaloneService = standaloneService;
        }

        StubGateway(IProject project, InfobaseReference fileBoundInfobase,
                IStandaloneServerService standaloneService) {
            this.project = project;
            this.associationEmpty = false;
            this.fileBoundInfobase = fileBoundInfobase;
            this.standaloneService = standaloneService;
        }

        @Override
        public IProject resolveProject(String projectName) {
            return project;
        }

        @Override
        public IInfobaseAssociationManager getInfobaseAssociationManager() {
            return (IInfobaseAssociationManager) Proxy.newProxyInstance(
                    IInfobaseAssociationManager.class.getClassLoader(),
                    new Class<?>[] { IInfobaseAssociationManager.class },
                    (proxy, method, args) -> {
                        if ("getAssociation".equals(method.getName()) //$NON-NLS-1$
                                && method.getParameterCount() == 1
                                && method.getParameterTypes()[0] == IProject.class) {
                            if (associationEmpty || fileBoundInfobase == null) {
                                return Optional.empty();
                            }
                            return Optional.of(new StubAssociation(project, fileBoundInfobase));
                        }
                        return defaultReturn(method);
                    });
        }

        @Override
        public IInfobaseAccessManager getInfobaseAccessManager() {
            throw new UnsupportedOperationException("not used by tests"); //$NON-NLS-1$
        }

        @Override
        public IRuntimeComponentManager getRuntimeComponentManager() {
            throw new UnsupportedOperationException("not used by tests"); //$NON-NLS-1$
        }

        @Override
        public Object getInfobaseSynchronizationManager() {
            throw new UnsupportedOperationException("not used by tests"); //$NON-NLS-1$
        }

        @Override
        public IStandaloneServerService getStandaloneServerServiceOrNull() {
            return standaloneService;
        }

        @Override
        public IStandaloneServerService peekStandaloneServerService() {
            return standaloneService;
        }
    }

    /**
     * Gateway whose {@code getInfobaseAssociationManager()} throws (simulating an EDT
     * service-layer exception) so the test can assert that the standalone fallback still fires
     * and that the original failure is preserved on the propagated exception chain.
     */
    private static final class ThrowingPrimaryGateway extends EdtRuntimeGateway {
        private final IProject project;
        private final IStandaloneServerService standaloneService;
        private final RuntimeException primaryFailure;

        ThrowingPrimaryGateway(IProject project, IStandaloneServerService standaloneService,
                RuntimeException primaryFailure) {
            this.project = project;
            this.standaloneService = standaloneService;
            this.primaryFailure = primaryFailure;
        }

        @Override
        public IProject resolveProject(String projectName) {
            return project;
        }

        @Override
        public IInfobaseAssociationManager getInfobaseAssociationManager() {
            throw primaryFailure;
        }

        @Override
        public IInfobaseAccessManager getInfobaseAccessManager() {
            throw new UnsupportedOperationException("not used by tests"); //$NON-NLS-1$
        }

        @Override
        public IRuntimeComponentManager getRuntimeComponentManager() {
            throw new UnsupportedOperationException("not used by tests"); //$NON-NLS-1$
        }

        @Override
        public Object getInfobaseSynchronizationManager() {
            throw new UnsupportedOperationException("not used by tests"); //$NON-NLS-1$
        }

        @Override
        public IStandaloneServerService getStandaloneServerServiceOrNull() {
            return standaloneService;
        }

        @Override
        public IStandaloneServerService peekStandaloneServerService() {
            return standaloneService;
        }
    }

    private static final class CanonicalStandaloneGateway extends EdtRuntimeGateway {
        private final IProject project;
        private final IStandaloneServerService standaloneService;
        private final IInfobaseManager infobaseManager;
        private final Object synchronizationManager;

        CanonicalStandaloneGateway(IProject project, IStandaloneServerService standaloneService,
                IInfobaseManager infobaseManager, Object synchronizationManager) {
            this.project = project;
            this.standaloneService = standaloneService;
            this.infobaseManager = infobaseManager;
            this.synchronizationManager = synchronizationManager;
        }

        @Override
        public IProject resolveProject(String projectName) {
            return project;
        }

        @Override
        public IInfobaseAssociationManager getInfobaseAssociationManager() {
            return (IInfobaseAssociationManager) Proxy.newProxyInstance(
                    IInfobaseAssociationManager.class.getClassLoader(),
                    new Class<?>[] { IInfobaseAssociationManager.class },
                    (proxy, method, args) -> {
                        if ("getAssociation".equals(method.getName())) { //$NON-NLS-1$
                            return Optional.empty();
                        }
                        return defaultReturn(method);
                    });
        }

        @Override
        public IInfobaseManager getInfobaseManager() {
            return infobaseManager;
        }

        @Override
        public IInfobaseAccessManager getInfobaseAccessManager() {
            throw new UnsupportedOperationException("not used by tests"); //$NON-NLS-1$
        }

        @Override
        public IRuntimeComponentManager getRuntimeComponentManager() {
            throw new UnsupportedOperationException("not used by tests"); //$NON-NLS-1$
        }

        @Override
        public Object getInfobaseSynchronizationManager() {
            return synchronizationManager;
        }

        @Override
        public IStandaloneServerService getStandaloneServerServiceOrNull() {
            return standaloneService;
        }

        @Override
        public IStandaloneServerService peekStandaloneServerService() {
            return standaloneService;
        }
    }

    public static final class RecordingSynchronizationManager {
        volatile IProject project;
        volatile InfobaseReference infobase;
        volatile Object callback;
        volatile Boolean keepConnected;
        volatile IProgressMonitor monitor;

        public boolean updateInfobase(IProject project, InfobaseReference infobase, Object callback,
                Boolean keepConnected, IProgressMonitor monitor) {
            this.project = project;
            this.infobase = infobase;
            this.callback = callback;
            this.keepConnected = keepConnected;
            this.monitor = monitor;
            return true;
        }
    }

    private static final class StubAssociation implements IInfobaseAssociation {
        private final IProject project;
        private final InfobaseReference infobase;

        StubAssociation(IProject project, InfobaseReference infobase) {
            this.project = project;
            this.infobase = infobase;
        }

        @Override
        public IProject getProject() {
            return project;
        }

        @Override
        public java.util.Collection<InfobaseReference> getInfobases() {
            return infobase == null ? Collections.emptyList() : List.of(infobase);
        }

        @Override
        public InfobaseReference getDefaultInfobase() {
            return infobase;
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
        public org.eclipse.wst.server.core.IServer createServer(org.eclipse.wst.server.core.IRuntime runtime,
                IProgressMonitor monitor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com._1c.g5.v8.dt.common.Pair<IServer, StandaloneServerInfobase> createServerWithInfobase(
                String platformVersion, String projectName, InfobaseReference infobase, int clusterPort,
                String clusterRegistryDirectory, String publicationPath, IProgressMonitor monitor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IServer> getServer(StandaloneServerInfobase infobase) {
            return Optional.empty();
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
        public Optional<org.eclipse.wst.server.core.IRuntime> findRuntime(String platformVersion,
                IProgressMonitor monitor) {
            return Optional.empty();
        }

        @Override
        public Optional<com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerRuntime> //
                getStandaloneServerRuntime(org.eclipse.wst.server.core.IRuntime runtime, IProgressMonitor monitor) {
            return Optional.empty();
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

    private static final class StandaloneTestTool extends EdtUpdateInfobaseTool {
        private final File workspaceRoot;

        StandaloneTestTool(EdtProjectResolver projectResolver, EdtRuntimeService runtimeService,
                File workspaceRoot) {
            super(projectResolver, runtimeService);
            this.workspaceRoot = workspaceRoot;
        }

        @Override
        protected File getWorkspaceRoot() {
            return workspaceRoot;
        }
    }

    private static final class StubResolverReturning extends EdtProjectResolver {
        private final InfobaseReference infobase;

        StubResolverReturning(InfobaseReference infobase) {
            this.infobase = infobase;
        }

        @Override
        public InfobaseReference resolveInfobase(String projectName, File workspaceRoot) {
            assertNotNull(projectName);
            return infobase;
        }
    }

    private static final class StubRuntimeService extends EdtRuntimeService {
        @Override
        public boolean updateInfobase(String projectName, boolean keepConnected, IProgressMonitor monitor) {
            return true;
        }
    }

    private static final class MissingSshProviderRuntimeService extends EdtRuntimeService {
        @Override
        public boolean updateInfobase(String projectName, boolean keepConnected, IProgressMonitor monitor)
                throws EdtInfobaseUpdateException {
            IllegalStateException cause = new IllegalStateException("Provider \"ssh\" not installed"); //$NON-NLS-1$
            throw new EdtInfobaseUpdateException(cause.getMessage(), cause, "ssh", //$NON-NLS-1$
                    "standalone_server", cause.getClass().getName()); //$NON-NLS-1$
        }
    }
}
