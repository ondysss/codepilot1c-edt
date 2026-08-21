/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.wst.server.core.IRuntime;
import org.eclipse.wst.server.core.IServer;
import org.junit.Test;

import com._1c.g5.v8.dt.common.Pair;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectRequest;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectionKind;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerRuntime;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerBehaviourDelegate;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerDelegate;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase;

/**
 * Regression test for the {@code connect_infobase(standalone)} fix
 * (commit "Fix connect_infobase(standalone): assign infobase UUID before EDT call").
 *
 * <p>Before the fix, {@link EdtInfobaseConnectService#connectStandalone} created an
 * {@link InfobaseReference} via {@code InfobaseReferences.newFileInfobaseReference(...)}
 * and handed it straight to EDT's {@code IStandaloneServerService.createServerWithInfobase(...)}
 * without ever assigning a UUID. EDT then constructed a
 * {@link com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase} from it,
 * whose constructor enforces {@code Preconditions.checkArgument(uuid != null)} — every
 * standalone connect failed with a bare, message-less {@code IllegalArgumentException} that
 * the outer handler mislabelled as {@code EDT_SERVICE_UNAVAILABLE}.</p>
 *
 * <p>The fix sets {@code reference.setUuid(UUID.randomUUID())} before the EDT call, symmetric to
 * what {@code persistReference()} does in the file branch. This test guards that behaviour by
 * intercepting the InfobaseReference at the EDT-call boundary and asserting its UUID is
 * non-null.</p>
 */
public class EdtInfobaseConnectStandaloneUuidTest {

    /**
     * Drives {@link EdtInfobaseConnectService#connectStandalone} end-to-end with stubbed
     * collaborators and asserts that the {@link InfobaseReference} reaching the EDT standalone
     * API carries a non-null UUID.
     */
    @Test
    public void connectStandaloneAssignsUuidBeforeEdtCall() throws Exception {
        // Create the temp dir inside user.home so EdtInfobaseConnectService.validateAndNormalizePath
        // accepts it (it rejects paths outside the Eclipse workspace or user home).
        Path home = Path.of(System.getProperty("user.home")); //$NON-NLS-1$
        Path databasePath = Files.createTempDirectory(home, "edt-standalone-uuid-regression"); //$NON-NLS-1$
        try {
            CapturingStandaloneServerService captor = new CapturingStandaloneServerService();
            TestableConnectService service = new TestableConnectService(new StubGateway(captor));
            IProject project = newProjectProxy("Demo"); //$NON-NLS-1$

            ConnectRequest request = new ConnectRequest(
                    "Demo",                     // project_name //$NON-NLS-1$
                    databasePath.toString(),    // database_path (already an absolute temp dir under home)
                    ConnectionKind.STANDALONE,  // kind
                    null,                       // login (use OS auth so storeAccessSettings is harmless)
                    null,                       // password
                    false,                      // set_primary=false skips checkExistingPrimary
                    Integer.valueOf(1545),      // server_port
                    "");                       // runtime_version (forces findRuntime -> getRuntimes()) //$NON-NLS-1$

            try {
                service.invokeConnectStandalone(project, request);
                fail("expected EdtToolException because the capturing service aborts the EDT call"); //$NON-NLS-1$
            } catch (EdtToolException expected) {
                // The captor throws after recording the reference, so the service wraps it as
                // STANDALONE_SERVER_CREATE_FAILED (defense-in-depth catch in connectStandalone).
                assertEquals("captured exception must be wrapped as a typed EDT tool error", //$NON-NLS-1$
                        EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED, expected.getCode());
            }

            InfobaseReference captured = captor.captured.get();
            assertNotNull("EDT's createServerWithInfobase must have been invoked", captured); //$NON-NLS-1$
            assertNotNull("REGRESSION: standalone connect must assign a non-null UUID to the " //$NON-NLS-1$
                    + "InfobaseReference before handing it to EDT's StandaloneServerService — " //$NON-NLS-1$
                    + "otherwise EDT's StandaloneServerInfobase constructor fails its " //$NON-NLS-1$
                    + "Preconditions.checkArgument and the connect aborts with a bare " //$NON-NLS-1$
                    + "IllegalArgumentException.", //$NON-NLS-1$
                    captured.getUuid());
        } finally {
            deleteRecursively(databasePath.toFile());
        }
    }

    // ---- support stubs -----------------------------------------------------------------------

    /** Exposes the protected {@code connectStandalone} for direct invocation by the test. */
    private static final class TestableConnectService extends EdtInfobaseConnectService {
        TestableConnectService(EdtRuntimeGateway gateway) {
            super(gateway);
        }

        void invokeConnectStandalone(IProject project, ConnectRequest request) {
            connectStandalone(project, request);
        }
    }

    /** Gateway returning the captor as the EDT standalone-server service. */
    private static final class StubGateway extends EdtRuntimeGateway {
        private final IStandaloneServerService standaloneService;

        StubGateway(IStandaloneServerService standaloneService) {
            this.standaloneService = standaloneService;
        }

        @Override
        public IStandaloneServerService getStandaloneServerService() {
            return standaloneService;
        }

        @Override
        public IInfobaseAssociationManager getInfobaseAssociationManager() {
            // Not consulted because the test passes set_primary=false (checkExistingPrimary
            // short-circuits) and the captor throws before associate() runs.
            throw new UnsupportedOperationException("not used by this regression test"); //$NON-NLS-1$
        }

        @Override
        public IInfobaseAccessManager getInfobaseAccessManager() {
            throw new UnsupportedOperationException("not used by this regression test"); //$NON-NLS-1$
        }

        @Override
        public IRuntimeComponentManager getRuntimeComponentManager() {
            throw new UnsupportedOperationException("not used by this regression test"); //$NON-NLS-1$
        }
    }

    /**
     * Captures the {@link InfobaseReference} passed to
     * {@link IStandaloneServerService#createServerWithInfobase} and then throws so the
     * production code never reaches the post-EDT bookkeeping (which would need more stubs).
     */
    private static final class CapturingStandaloneServerService implements IStandaloneServerService {
        final AtomicReference<InfobaseReference> captured = new AtomicReference<>();
        private final IRuntime runtime = newRuntimeProxy();

        @Override
        public List<IRuntime> getRuntimes() {
            // Must be non-empty so connectStandalone's findRuntime() does not bail with
            // STANDALONE_RUNTIME_NOT_FOUND before reaching createServerWithInfobase.
            return List.of(runtime);
        }

        @Override
        public Optional<IRuntime> findRuntime(String platformVersion, IProgressMonitor monitor) {
            // Fall through to getRuntimes() so the test does not depend on version matching.
            return Optional.empty();
        }

        @Override
        public Pair<IServer, StandaloneServerInfobase> createServerWithInfobase(String platformVersion,
                String projectName, InfobaseReference infobase, int clusterPort,
                String clusterRegistryDirectory, String publicationPath, IProgressMonitor monitor) {
            captured.set(infobase);
            // Abort after capture — the test only cares about what was handed to EDT.
            throw new RuntimeException("aborted-by-regression-captor"); //$NON-NLS-1$
        }

        // -- Remaining IStandaloneServerService surface: not used by connectStandalone but the
        //    interface must be fully implemented. ---------------------------------------------------

        @Override
        public List<IServer> getServers() { return Collections.emptyList(); }

        @Override
        public IServer createServer(IRuntime r, IProgressMonitor monitor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IServer> getServer(StandaloneServerInfobase infobase) {
            return Optional.empty();
        }

        @Override
        public URI getDesignerUrl(StandaloneServerInfobase infobase) { return null; }

        @Override
        public URI getInfobaseUrl(StandaloneServerInfobase infobase) { return null; }

        @Override
        public IStatus validateRuntimeInstallation(RuntimeInstallation installation) { return null; }

        @Override
        public Optional<IStandaloneServerRuntime> getStandaloneServerRuntime(IRuntime r,
                IProgressMonitor monitor) {
            return Optional.empty();
        }

        @Override
        public Path getServerLocation(IServer server) { return null; }

        @Override
        public Path getServerDataLocation(IServer server) { return null; }

        @Override
        public String getServerVersion(IServer server) { return ""; } //$NON-NLS-1$

        @Override
        public IStatus validateServerLocation(Path path) { return null; }

        @Override
        public IStatus deleteServer(IServer server, IProgressMonitor monitor) { return null; }

        @Override
        public IStatus startServer(IServer server, String mode, IProgressMonitor monitor) { return null; }

        @Override
        public IStatus stopServer(IServer server, IProgressMonitor monitor) { return null; }

        @Override
        public void execServerOperation(IServer server, Consumer<IServer.IOperationListener> consumer,
                IProgressMonitor monitor) {
            // no-op
        }

        @Override
        public StandaloneServerBehaviourDelegate findBehaviourDelegate(IServer server) { return null; }

        @Override
        public StandaloneServerDelegate findServerDelegate(IServer server) { return null; }

        @Override
        public boolean isStandaloneServer(IServer server) { return false; }

    }

    // ---- proxies -----------------------------------------------------------------------------

    private static IProject newProjectProxy(String projectName) {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(),
                new Class<?>[] { IProject.class },
                new ProjectHandler(projectName));
    }

    private static IRuntime newRuntimeProxy() {
        return (IRuntime) Proxy.newProxyInstance(
                IRuntime.class.getClassLoader(),
                new Class<?>[] { IRuntime.class },
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) { //$NON-NLS-1$
                        return "stub-runtime"; //$NON-NLS-1$
                    }
                    if ("toString".equals(method.getName())) { //$NON-NLS-1$
                        return "StubRuntime"; //$NON-NLS-1$
                    }
                    return defaultReturn(method);
                });
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

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        assertTrue("failed to delete temp file " + file, file.delete() || !file.exists()); //$NON-NLS-1$
    }
}
