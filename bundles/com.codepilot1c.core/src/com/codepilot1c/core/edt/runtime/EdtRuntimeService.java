package com.codepilot1c.core.edt.runtime;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager.ThickClientInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder.ThickClientMode;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com.codepilot1c.core.logging.VibeLogger;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;

public class EdtRuntimeService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtRuntimeService.class);

    /**
     * Runtime-type and component-type ids used to resolve the thick client launcher. EDT 2025.2
     * (services.core 21.x) dropped the {@code IRuntimeComponentManager.getThickClientInfo(...)}
     * convenience methods; their former bodies resolved an {@link IResolvableRuntimeInstallation}
     * for these ids and delegated to {@code resolveExecutor(...)}. We inline that logic in
     * {@link #resolveThickClient}.
     */
    private static final String RUNTIME_TYPE_ENTERPRISE_PLATFORM =
            "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform"; //$NON-NLS-1$
    private static final String COMPONENT_TYPE_THICK_CLIENT =
            "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient"; //$NON-NLS-1$

    private final EdtRuntimeGateway gateway;

    public static final class AccessSettings {
        private final boolean osAuthentication;
        private final boolean infobaseAuthentication;
        private final String userName;
        private final String password;
        private final String additionalParameters;

        private AccessSettings(boolean osAuthentication, boolean infobaseAuthentication,
                               String userName, String password, String additionalParameters) {
            this.osAuthentication = osAuthentication;
            this.infobaseAuthentication = infobaseAuthentication;
            this.userName = userName;
            this.password = password;
            this.additionalParameters = additionalParameters;
        }

        public boolean isOsAuthentication() {
            return osAuthentication;
        }

        public boolean isInfobaseAuthentication() {
            return infobaseAuthentication;
        }

        public String getUserName() {
            return userName;
        }

        public String getPassword() {
            return password;
        }

        public String getAdditionalParameters() {
            return additionalParameters;
        }

        public static AccessSettings osAuthentication(String additionalParameters) {
            return new AccessSettings(true, false, null, null, additionalParameters);
        }

        public static AccessSettings infobaseAuthentication(String userName, String password,
                                                            String additionalParameters) {
            return new AccessSettings(false, true, userName, password, additionalParameters);
        }

        public static AccessSettings additionalParameters(String additionalParameters) {
            return new AccessSettings(false, false, null, null, additionalParameters);
        }

        public AccessSettings withAdditionalParameters(String additionalParameters) {
            return new AccessSettings(osAuthentication, infobaseAuthentication, userName, password,
                    additionalParameters);
        }
    }

    public EdtRuntimeService() {
        this(new EdtRuntimeGateway());
    }

    public EdtRuntimeService(EdtRuntimeGateway gateway) {
        this.gateway = gateway;
    }

    public InfobaseReference resolveDefaultInfobase(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null) {
            throw new IllegalStateException("EDT project not found: " + projectName); //$NON-NLS-1$
        }

        // Primary path: file-binding via IInfobaseAssociationManager.
        boolean associationPresent = false;
        InfobaseReference infobase = null;
        Throwable primaryFailure = null;
        try {
            IInfobaseAssociationManager manager = gateway.getInfobaseAssociationManager();
            java.util.Optional<IInfobaseAssociation> associationOpt = manager.getAssociation(project);
            if (associationOpt.isPresent()) {
                associationPresent = true;
                IInfobaseAssociation association = associationOpt.get();
                infobase = association.getDefaultInfobase();
                if (infobase == null && !association.getInfobases().isEmpty()) {
                    infobase = association.getInfobases().iterator().next();
                }
            }
        } catch (IllegalStateException e) {
            // IInfobaseAssociationManager service is unavailable; fall through to standalone path.
            LOG.warn("IInfobaseAssociationManager unavailable; attempting standalone-server fallback: " //$NON-NLS-1$
                    + e.getMessage(), e);
            primaryFailure = e;
        } catch (Exception e) {
            // EDT may throw InfobaseAssociationException for malformed/missing bindings;
            // treat as absent and fall back to the standalone path.
            LOG.warn("Failed to query IInfobaseAssociationManager for project " + projectName //$NON-NLS-1$
                    + "; attempting standalone-server fallback: " + e.getMessage(), e); //$NON-NLS-1$
            primaryFailure = e;
        }

        if (infobase != null) {
            return infobase;
        }

        // Fallback: standalone-server binding (com.e1c.g5.v8.dt.platform.standaloneserver.wst.core).
        InfobaseReference standaloneInfobase = resolveStandaloneInfobase(project);
        if (standaloneInfobase != null) {
            return standaloneInfobase;
        }

        String message;
        if (!associationPresent && primaryFailure == null) {
            message = "Infobase association not found for project: " + projectName; //$NON-NLS-1$
        } else {
            message = "Infobase reference not found for project: " + projectName; //$NON-NLS-1$
        }
        IllegalStateException failure = new IllegalStateException(message);
        if (primaryFailure != null) {
            // Preserve the original primary-path exception so diagnostics can trace back to the
            // underlying IInfobaseAssociationManager failure even when fallback also fails.
            failure.addSuppressed(primaryFailure);
        }
        throw failure;
    }

    /**
     * Attempts to resolve an {@link InfobaseReference} for a project bound through the standalone
     * server plugin. Returns {@code null} if the standalone service is not registered, no server
     * hosts an infobase module for this project, or the module cannot be adapted.
     *
     * <p>Uses the non-blocking {@code peekStandaloneServerService()} accessor so that a missing
     * standalone-server service does not stall the agent tool dispatcher while a 30-second
     * {@code ServiceTracker.waitForService} elapses.
     */
    private InfobaseReference resolveStandaloneInfobase(IProject project) {
        IStandaloneServerService service = gateway.peekStandaloneServerService();
        if (service == null) {
            return null;
        }
        String projectName = project.getName();
        java.util.List<IServer> servers;
        try {
            servers = service.getServers();
        } catch (Exception | NoSuchMethodError e) {
            // Standalone-server enumeration runs through EDT internal services that may invoke
            // interruptible operations. Restore the interrupt flag if it was raised so that
            // upstream cancellation propagates instead of being silently swallowed.
            if (e instanceof InterruptedException || Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Standalone server enumeration failed: " + e.getMessage(), e); //$NON-NLS-1$
            return null;
        }
        if (servers == null || servers.isEmpty()) {
            return null;
        }
        for (IServer server : servers) {
            if (server == null) {
                continue;
            }
            IModule[] modules = server.getModules();
            if (modules == null) {
                continue;
            }
            for (IModule module : modules) {
                if (!(module instanceof StandaloneServerInfobase standaloneInfobase)) {
                    continue;
                }
                if (!matchesProject(standaloneInfobase, project, projectName)) {
                    continue;
                }
                InfobaseReference adapted = adaptStandaloneInfobase(standaloneInfobase);
                if (adapted != null) {
                    return adapted;
                }
            }
        }
        return null;
    }

    private static boolean matchesProject(StandaloneServerInfobase infobase, IProject project, String projectName) {
        IProject modProject = infobase.getProject();
        if (modProject != null && modProject.equals(project)) {
            return true;
        }
        String modProjectName = infobase.getProjectName();
        return modProjectName != null && modProjectName.equals(projectName);
    }

    private static InfobaseReference adaptStandaloneInfobase(StandaloneServerInfobase infobase) {
        try {
            Object adapter = infobase.getAdapter(InfobaseReference.class);
            if (adapter instanceof InfobaseReference ref) {
                return ref;
            }
            // Fallback to loadAdapter if the adapter factory has not yet been registered.
            Object loaded = infobase.loadAdapter(InfobaseReference.class, new NullProgressMonitor());
            if (loaded instanceof InfobaseReference ref) {
                return ref;
            }
        } catch (Exception | NoSuchMethodError e) {
            // EDT adapter factories may run user/internal code that performs interruptible
            // operations; if the worker thread was interrupted during the adapter resolution
            // we must restore the interrupt flag so callers (and the agent dispatcher) can
            // observe cancellation instead of treating the result as a benign null.
            if (e instanceof InterruptedException || Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Failed to adapt standalone-server infobase to InfobaseReference: " //$NON-NLS-1$
                    + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Web client (and designer) URLs of the project's standalone-server infobase, plus the server
     * state. The web client URL ({@link #webClientUrl()}) is what a browser/Playwright session should
     * navigate to in order to verify the running 1C interface. The URL already embeds host and port.
     */
    public record WebClientInfo(
            boolean available,
            String message,
            String webClientUrl,
            String designerUrl,
            boolean serverRunning,
            String serverState,
            String serverName) {
        static WebClientInfo unavailable(String message) {
            return new WebClientInfo(false, message, null, null, false, null, null);
        }
    }

    /**
     * Resolves the web client URL of the standalone-server infobase bound to the given project,
     * using {@code IStandaloneServerService.getInfobaseUrl(...)}. Never throws — returns an
     * {@code available=false} {@link WebClientInfo} with a human-readable {@code message} instead.
     */
    public WebClientInfo resolveWebClientInfo(String projectName) {
        IProject project;
        try {
            project = gateway.resolveProject(projectName);
        } catch (RuntimeException e) {
            return WebClientInfo.unavailable("Project not found: " + projectName); //$NON-NLS-1$
        }
        if (project == null) {
            return WebClientInfo.unavailable("Project not found: " + projectName); //$NON-NLS-1$
        }
        IStandaloneServerService service = gateway.peekStandaloneServerService();
        if (service == null) {
            return WebClientInfo.unavailable("Standalone server service is not available in this EDT session."); //$NON-NLS-1$
        }
        java.util.List<IServer> servers;
        try {
            servers = service.getServers();
        } catch (Exception | NoSuchMethodError e) {
            if (e instanceof InterruptedException || Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }
            return WebClientInfo.unavailable("Failed to enumerate standalone servers: " + e.getMessage()); //$NON-NLS-1$
        }
        if (servers == null || servers.isEmpty()) {
            return WebClientInfo.unavailable("No standalone server is registered in this EDT workspace."); //$NON-NLS-1$
        }
        for (IServer server : servers) {
            if (server == null) {
                continue;
            }
            IModule[] modules = server.getModules();
            if (modules == null) {
                continue;
            }
            for (IModule module : modules) {
                if (!(module instanceof StandaloneServerInfobase standaloneInfobase)
                        || !matchesProject(standaloneInfobase, project, project.getName())) {
                    continue;
                }
                String webUrl = standaloneUrlString(() -> service.getInfobaseUrl(standaloneInfobase));
                String designerUrl = standaloneUrlString(() -> service.getDesignerUrl(standaloneInfobase));
                return new WebClientInfo(true, "", webUrl, designerUrl, //$NON-NLS-1$
                        isServerRunning(server), serverStateName(server), safeServerName(server));
            }
        }
        return WebClientInfo.unavailable(
                "No standalone-server infobase is bound to project '" + project.getName() //$NON-NLS-1$
                        + "'. Connect/create the infobase and start the server first."); //$NON-NLS-1$
    }

    @FunctionalInterface
    private interface UrlSupplier {
        Object get() throws Exception; // NOSONAR — wraps EDT's checked StandaloneServerException
    }

    private static String standaloneUrlString(UrlSupplier supplier) {
        try {
            Object uri = supplier.get();
            return uri == null ? null : uri.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isServerRunning(IServer server) {
        try {
            return server.getServerState() == IServer.STATE_STARTED;
        } catch (Exception e) {
            return false;
        }
    }

    private static String serverStateName(IServer server) {
        try {
            return switch (server.getServerState()) {
                case IServer.STATE_STARTED -> "started"; //$NON-NLS-1$
                case IServer.STATE_STARTING -> "starting"; //$NON-NLS-1$
                case IServer.STATE_STOPPED -> "stopped"; //$NON-NLS-1$
                case IServer.STATE_STOPPING -> "stopping"; //$NON-NLS-1$
                default -> "unknown"; //$NON-NLS-1$
            };
        } catch (Exception e) {
            return "unknown"; //$NON-NLS-1$
        }
    }

    private static String safeServerName(IServer server) {
        try {
            String name = server.getName();
            return name == null ? "" : name; //$NON-NLS-1$
        } catch (Exception e) {
            return ""; //$NON-NLS-1$
        }
    }

    public AccessSettings resolveAccessSettings(String projectName) {
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        return resolveAccessSettings(infobase);
    }

    public AccessSettings resolveAccessSettings(InfobaseReference infobase) {
        if (infobase == null) {
            return null;
        }
        IInfobaseAccessSettings settings;
        try {
            IInfobaseAccessManager accessManager = gateway.getInfobaseAccessManager();
            settings = resolveAccessManagerSettings(accessManager, infobase);
        } catch (Exception | LinkageError e) {
            return null;
        }
        if (settings == null || settings == IInfobaseAccessSettings.NOT_DEFINED) {
            return null;
        }
        InfobaseAccess access = settings.access();
        boolean osAuth = access == InfobaseAccess.OS;
        boolean infobaseAuth = access == InfobaseAccess.INFOBASE;
        return new AccessSettings(osAuth, infobaseAuth, settings.userName(), settings.password(),
                settings.additionalProperties());
    }

    public ThickClientInfo resolveThickClientInfo(InfobaseReference infobase) {
        return resolveThickClientInfo(infobase, null);
    }

    public ThickClientInfo resolveThickClientInfo(InfobaseReference infobase, String versionMask) {
        IRuntimeComponentManager runtimeComponentManager = gateway.getRuntimeComponentManager();
        IResolvableRuntimeInstallationManager installationManager =
                gateway.getResolvableRuntimeInstallationManager();
        ThickClientInfo info;
        try {
            IResolvableRuntimeInstallation resolvable;
            if (versionMask != null && !versionMask.isBlank()) {
                resolvable = installationManager.resolveByVersionOrMask(RUNTIME_TYPE_ENTERPRISE_PLATFORM, versionMask);
            } else {
                resolvable = installationManager.resolveByProjectAndInfobase(RUNTIME_TYPE_ENTERPRISE_PLATFORM,
                        null, infobase, InfobaseAccessType.UPDATE);
            }
            info = resolveThickClient(runtimeComponentManager, resolvable, infobase);
        } catch (Exception | NoSuchMethodError e) {
            LOG.warn("Failed to resolve thick client (possible EDT API incompatibility): " + e.getMessage(), e); //$NON-NLS-1$
            return null;
        }
        if (info == null || info.component() == null || info.component().getFile() == null) {
            throw new IllegalStateException("Thick client runtime component not resolved for infobase"); //$NON-NLS-1$
        }
        return info;
    }

    /**
     * Resolves the thick client launcher for a runtime installation, replacing the
     * {@code IRuntimeComponentManager.getThickClientInfo(...)} methods removed in EDT 2025.2. This
     * mirrors their former private {@code resolveThickClient} body: pick the app architecture from
     * the infobase (or {@link AppArch#AUTO}), resolve the concrete {@link RuntimeInstallation} for
     * the thick client component, then ask {@code resolveExecutor} for the launchable component and
     * its launcher.
     */
    private static ThickClientInfo resolveThickClient(IRuntimeComponentManager runtimeComponentManager,
            IResolvableRuntimeInstallation resolvable, InfobaseReference infobase)
            throws MatchingRuntimeNotFound, RuntimeExecutionException {
        AppArch appArch = infobase != null ? infobase.getAppArch() : AppArch.AUTO;
        RuntimeInstallation installation = resolvable.resolve(List.of(COMPONENT_TYPE_THICK_CLIENT), appArch);
        ComponentExecutorInfo<ILaunchableRuntimeComponent, IThickClientLauncher> executorInfo =
                runtimeComponentManager.resolveExecutor(ILaunchableRuntimeComponent.class,
                        IThickClientLauncher.class, installation, COMPONENT_TYPE_THICK_CLIENT);
        return new ThickClientInfo(resolvable, executorInfo.getInstallation(), executorInfo.getComponent(),
                executorInfo.getExecutor());
    }

    public RuntimeExecutionCommandBuilder buildTestManagerCommand(String projectName, File epfPath,
                                                                  File vaParamsPath, File workspaceRoot,
                                                                  boolean showMainForm, boolean quietInstall,
                                                                  boolean clearStepsCache, File logFile) {
        return buildTestManagerCommand(projectName, epfPath, vaParamsPath, workspaceRoot, showMainForm,
                quietInstall, clearStepsCache, logFile, null);
    }

    public RuntimeExecutionCommandBuilder buildTestManagerCommand(String projectName, File epfPath,
                                                                  File vaParamsPath, File workspaceRoot,
                                                                  boolean showMainForm, boolean quietInstall,
                                                                  boolean clearStepsCache, File logFile,
                                                                  String versionMask) {
        return buildTestManagerCommand(projectName, epfPath, vaParamsPath, workspaceRoot, showMainForm,
                quietInstall, clearStepsCache, logFile, versionMask, null);
    }

    public RuntimeExecutionCommandBuilder buildTestManagerCommand(String projectName, File epfPath,
                                                                  File vaParamsPath, File workspaceRoot,
                                                                  boolean showMainForm, boolean quietInstall,
                                                                  boolean clearStepsCache, File logFile,
                                                                  String versionMask,
                                                                  AccessSettings explicitAccessSettings) {
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        ThickClientInfo info = resolveThickClientInfo(infobase, versionMask);
        File clientFile = info.component().getFile();

        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(clientFile,
                ThickClientMode.ENTERPRISE);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        if (explicitAccessSettings != null) {
            applyAccessSettings(builder, explicitAccessSettings);
        } else {
            applyAccessSettings(builder, infobase);
        }
        builder.testManagerMode();
        if (epfPath != null) {
            builder.execute(epfPath.getAbsolutePath());
        }
        builder.startupOption(buildStartupOption(vaParamsPath, workspaceRoot, showMainForm, quietInstall,
                clearStepsCache));
        builder.disableStartupDialogs();
        builder.disableStartupMessages();
        if (logFile != null) {
            builder.logTo(logFile, true);
        }
        return builder;
    }

    public RuntimeExecutionCommandBuilder buildSingleClientCommand(String projectName, File epfPath,
                                                                   File vaParamsPath, File workspaceRoot,
                                                                   boolean showMainForm, boolean quietInstall,
                                                                   boolean clearStepsCache, File logFile) {
        return buildSingleClientCommand(projectName, epfPath, vaParamsPath, workspaceRoot, showMainForm,
                quietInstall, clearStepsCache, logFile, null);
    }

    public RuntimeExecutionCommandBuilder buildSingleClientCommand(String projectName, File epfPath,
                                                                   File vaParamsPath, File workspaceRoot,
                                                                   boolean showMainForm, boolean quietInstall,
                                                                   boolean clearStepsCache, File logFile,
                                                                   String versionMask) {
        return buildSingleClientCommand(projectName, epfPath, vaParamsPath, workspaceRoot, showMainForm,
                quietInstall, clearStepsCache, logFile, versionMask, null);
    }

    public RuntimeExecutionCommandBuilder buildSingleClientCommand(String projectName, File epfPath,
                                                                   File vaParamsPath, File workspaceRoot,
                                                                   boolean showMainForm, boolean quietInstall,
                                                                   boolean clearStepsCache, File logFile,
                                                                   String versionMask,
                                                                   AccessSettings explicitAccessSettings) {
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        ThickClientInfo info = resolveThickClientInfo(infobase, versionMask);
        File clientFile = info.component().getFile();

        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(clientFile,
                ThickClientMode.ENTERPRISE);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        if (explicitAccessSettings != null) {
            applyAccessSettings(builder, explicitAccessSettings);
        } else {
            applyAccessSettings(builder, infobase);
        }
        if (epfPath != null) {
            builder.execute(epfPath.getAbsolutePath());
        }
        builder.startupOption(buildStartupOption(vaParamsPath, workspaceRoot, showMainForm, quietInstall,
                clearStepsCache));
        builder.disableStartupDialogs();
        builder.disableStartupMessages();
        if (logFile != null) {
            builder.logTo(logFile, true);
        }
        return builder;
    }

    public RuntimeExecutionCommandBuilder buildUpdateCommand(String projectName, File logFile) {
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        ThickClientInfo info = resolveThickClientInfo(infobase);
        File clientFile = info.component().getFile();

        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(clientFile,
                ThickClientMode.DESIGNER);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        applyAccessSettings(builder, infobase);
        invokeCompatibleNoArg(builder,
                "updateDatabaseConfiguration", "updateInfobase"); //$NON-NLS-1$ //$NON-NLS-2$
        builder.disableStartupDialogs();
        builder.disableStartupMessages();
        if (logFile != null) {
            builder.logTo(logFile, true);
        }
        return builder;
    }

    public ProcessBuilder buildEnterpriseLaunchProcess(EdtResolvedLaunchContext context,
            String additionalParameters, File logFile) {
        if (context == null) {
            throw new IllegalArgumentException("Launch context is required"); //$NON-NLS-1$
        }
        InfobaseReference infobase = context.infobase();
        if (infobase == null) {
            throw new IllegalStateException("Infobase reference not available"); //$NON-NLS-1$
        }
        if (context.clientFile() == null) {
            throw new IllegalStateException("Client executable not available"); //$NON-NLS-1$
        }
        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(
                context.clientFile(), ThickClientMode.ENTERPRISE);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        AccessSettings effectiveSettings = mergeAdditionalParameters(context.accessSettings(), additionalParameters);
        applyAccessSettings(builder, effectiveSettings);
        builder.disableStartupDialogs();
        builder.disableStartupMessages();
        if (logFile != null) {
            builder.logTo(logFile, true);
        }
        return builder.toProcessBuilder();
    }

    public boolean updateInfobase(String projectName) throws Exception {
        return updateInfobase(projectName, true, new NullProgressMonitor());
    }

    public boolean updateInfobase(String projectName, boolean keepConnected, IProgressMonitor monitor)
            throws Exception {
        IProject project = gateway.resolveProject(projectName);
        if (project == null) {
            throw new IllegalStateException("EDT project not found: " + projectName); //$NON-NLS-1$
        }
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        Object manager = gateway.getInfobaseSynchronizationManager();
        IProgressMonitor usedMonitor = monitor != null ? monitor : new NullProgressMonitor();
        Object callback = createAutoUpdateCallback(manager.getClass().getClassLoader());
        Method updateMethod = findUpdateMethod(manager.getClass());
        if (updateMethod == null) {
            throw new IllegalStateException("EDT updateInfobase method not found"); //$NON-NLS-1$
        }
        try {
            Object result = updateMethod.invoke(manager, project, infobase, callback, Boolean.valueOf(keepConnected),
                    usedMonitor);
            return result instanceof Boolean ? ((Boolean) result).booleanValue() : false;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw new IllegalStateException("EDT updateInfobase failed: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    public void applyAccessSettings(RuntimeExecutionCommandBuilder builder, AccessSettings settings) {
        if (builder == null || settings == null) {
            return;
        }
        if (settings.isOsAuthentication()) {
            builder.osAuthentication(true);
        } else if (settings.isInfobaseAuthentication()) {
            String user = settings.getUserName();
            if (user != null && !user.isBlank()) {
                builder.userName(user);
            }
            String password = settings.getPassword();
            if (password != null && !password.isBlank()) {
                builder.userPassword(password);
            }
        }
        String additional = settings.getAdditionalParameters();
        if (additional != null && !additional.isBlank()) {
            builder.additionalParameters(additional);
        }
    }

    public AccessSettings mergeAdditionalParameters(AccessSettings settings, String additionalParameters) {
        if (settings == null) {
            String merged = normalizeAdditionalParameters(null, additionalParameters);
            return merged == null ? null : AccessSettings.additionalParameters(merged);
        }
        String merged = normalizeAdditionalParameters(settings.getAdditionalParameters(), additionalParameters);
        return settings.withAdditionalParameters(merged);
    }

    private static Object createAutoUpdateCallback(ClassLoader loader) throws ClassNotFoundException {
        Class<?> callbackInterface = Class.forName(
                "com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback", //$NON-NLS-1$
                true,
                loader);
        return Proxy.newProxyInstance(callbackInterface.getClassLoader(),
                new Class<?>[] { callbackInterface },
                (Object proxy, Method method, Object[] args) -> handleUpdateCallback(proxy, method, args));
    }

    private static Object handleUpdateCallback(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if ("onConfirm".equals(name)) { //$NON-NLS-1$
            return Boolean.TRUE;
        }
        // EDT 2025.2.x calls resolveInfobaseChanges (from IInfobaseChangesResolver); the legacy
        // onInfobaseChanges name is kept for other API versions. Headless policy: the project overrides
        // the infobase. We must ACTUALLY perform the override via the conflict resolver passed in the
        // callback args (IInfobaseUpdateConflictResolver.overrideConflict) — just returning OVERRIDDEN
        // reports success while leaving the infobase unsynced, so updateInfobase() returns false. We
        // fall back to reporting OVERRIDDEN only when the resolver cannot be invoked.
        if ("resolveInfobaseChanges".equals(name) || "onInfobaseChanges".equals(name)) { //$NON-NLS-1$ //$NON-NLS-2$
            Object resolved = invokeConflictOverride(args);
            if (resolved != null) {
                return resolved;
            }
            Object overridden = enumValue(method.getReturnType(), "OVERRIDDEN"); //$NON-NLS-1$
            if (overridden != null) {
                return overridden;
            }
            Object deferred = enumValue(method.getReturnType(), "DEFERRED"); //$NON-NLS-1$
            return deferred != null ? deferred : defaultValue(method.getReturnType());
        }
        if ("toString".equals(name) && method.getParameterCount() == 0) { //$NON-NLS-1$
            return "AutoUpdateCallbackProxy"; //$NON-NLS-1$
        }
        if ("hashCode".equals(name) && method.getParameterCount() == 0) { //$NON-NLS-1$
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if ("equals".equals(name) && method.getParameterCount() == 1) { //$NON-NLS-1$
            return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
        }
        return defaultValue(method.getReturnType());
    }

    private static Method findUpdateMethod(Class<?> managerClass) {
        return findMethod(managerClass, "updateInfobase", 5); //$NON-NLS-1$
    }

    private static Method findMethod(Class<?> type, String name, int paramCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                return method;
            }
        }
        return null;
    }

    /**
     * Performs the infobase override by reflectively invoking {@code overrideConflict(...)} on the
     * conflict resolver passed in the callback args (the arg exposing such a method). Arguments are
     * matched to the available callback args by type, so it tolerates signature differences across EDT
     * versions. Returns the resolver's result enum, or {@code null} when it cannot be invoked (the
     * caller then safely falls back to reporting OVERRIDDEN).
     */
    private static Object invokeConflictOverride(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object resolver : args) {
            if (resolver == null) {
                continue;
            }
            Method override = findMethodByName(resolver.getClass(), "overrideConflict"); //$NON-NLS-1$
            if (override == null) {
                continue;
            }
            Object[] callArgs = matchArgsByType(override.getParameterTypes(), args, resolver);
            if (callArgs == null) {
                continue;
            }
            try {
                override.setAccessible(true);
                return override.invoke(resolver, callArgs);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.warn("overrideConflict invocation failed; reporting OVERRIDDEN instead: " //$NON-NLS-1$
                        + e.getMessage());
                return null;
            }
        }
        return null;
    }

    private static Method findMethodByName(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }

    /**
     * Best-effort matching of {@code paramTypes} against the available callback args (excluding
     * {@code exclude}, the resolver itself), by assignability. Returns {@code null} when any
     * non-primitive parameter has no assignable arg, so the caller can fall back instead of mis-invoking.
     */
    private static Object[] matchArgsByType(Class<?>[] paramTypes, Object[] available, Object exclude) {
        Object[] result = new Object[paramTypes.length];
        boolean[] used = new boolean[available.length];
        for (int p = 0; p < paramTypes.length; p++) {
            Object match = null;
            for (int a = 0; a < available.length; a++) {
                Object arg = available[a];
                if (used[a] || arg == null || arg == exclude) {
                    continue;
                }
                if (paramTypes[p].isInstance(arg)) {
                    match = arg;
                    used[a] = true;
                    break;
                }
            }
            if (match == null && !paramTypes[p].isPrimitive()) {
                return null;
            }
            result[p] = match;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object enumValue(Class<?> type, String name) {
        if (type != null && type.isEnum()) {
            try {
                return Enum.valueOf((Class<Enum>) type, name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == null || type == Void.TYPE || !type.isPrimitive()) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0F);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0D);
        }
        return null;
    }

    private void applyAccessSettings(RuntimeExecutionCommandBuilder builder, InfobaseReference infobase) {
        if (builder == null || infobase == null) {
            return;
        }
        IInfobaseAccessSettings settings = null;
        try {
            IInfobaseAccessManager accessManager = gateway.getInfobaseAccessManager();
            settings = resolveAccessManagerSettings(accessManager, infobase);
        } catch (Exception | LinkageError e) {
            LOG.warn("Failed to resolve access settings (possible EDT 2025.2 API change): " + e.getMessage(), e); //$NON-NLS-1$
            settings = null;
        }

        if (settings != null && settings != IInfobaseAccessSettings.NOT_DEFINED) {
            InfobaseAccess access = settings.access();
            if (access == InfobaseAccess.OS) {
                builder.osAuthentication(true);
            } else if (access == InfobaseAccess.INFOBASE) {
                String user = settings.userName();
                if (user != null && !user.isBlank()) {
                    builder.userName(user);
                }
                String password = settings.password();
                if (password != null && !password.isBlank()) {
                    builder.userPassword(password);
                }
            }
            String additional = settings.additionalProperties();
            if (additional != null && !additional.isBlank()) {
                builder.additionalParameters(additional);
                return;
            }
        }

        String fallback = infobase.getAdditionalParameters();
        if (fallback != null && !fallback.isBlank()) {
            builder.additionalParameters(fallback);
        }
    }

    private static IInfobaseAccessSettings resolveAccessManagerSettings(
            IInfobaseAccessManager manager, InfobaseReference infobase) throws Exception {
        Method method = findCompatibleMethod(manager.getClass(),
                new String[] {"resolveSettings", "getSettings"}, //$NON-NLS-1$ //$NON-NLS-2$
                InfobaseReference.class);
        if (method == null) {
            throw new NoSuchMethodException(
                    "IInfobaseAccessManager settings resolver is unavailable"); //$NON-NLS-1$
        }
        try {
            Object value = method.invoke(manager, infobase);
            if (value == null || value instanceof IInfobaseAccessSettings) {
                return (IInfobaseAccessSettings) value;
            }
            throw new IllegalStateException(
                    "EDT access settings resolver returned an unexpected result type"); //$NON-NLS-1$
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw new IllegalStateException("Access settings resolution failed", e); //$NON-NLS-1$
        }
    }

    private static void invokeCompatibleNoArg(Object receiver, String... methodNames) {
        Method method = findCompatibleMethod(receiver.getClass(), methodNames);
        if (method == null) {
            throw new IllegalStateException(
                    "Compatible EDT runtime command builder operation is unavailable"); //$NON-NLS-1$
        }
        try {
            method.invoke(receiver);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "EDT runtime command builder operation failed: " + cause.getMessage(), cause); //$NON-NLS-1$
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "EDT runtime command builder operation failed: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    private static Method findCompatibleMethod(
            Class<?> type, String[] methodNames, Class<?>... parameterTypes) {
        for (String name : methodNames) {
            try {
                return type.getMethod(name, parameterTypes);
            } catch (NoSuchMethodException e) {
                // try the next API-version name
            }
        }
        return null;
    }

    private String buildStartupOption(File vaParamsPath, File workspaceRoot, boolean showMainForm,
                                      boolean quietInstall, boolean clearStepsCache) {
        StringBuilder sb = new StringBuilder();
        sb.append("StartFeaturePlayer"); //$NON-NLS-1$
        if (vaParamsPath != null) {
            sb.append(";VAParams=").append(vaParamsPath.getAbsolutePath()); //$NON-NLS-1$
        }
        if (workspaceRoot != null) {
            sb.append(";WorkspaceRoot=").append(workspaceRoot.getAbsolutePath()); //$NON-NLS-1$
        }
        if (quietInstall) {
            sb.append(";QuietInstallVanessaExt"); //$NON-NLS-1$
        }
        if (!showMainForm) {
            sb.append(";ShowMainForm=Ложь"); //$NON-NLS-1$
        }
        if (clearStepsCache) {
            sb.append(";ClearStepsCache"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    static String normalizeAdditionalParameters(String base, String extra) {
        String left = base == null ? "" : base.trim(); //$NON-NLS-1$
        String right = extra == null ? "" : extra.trim(); //$NON-NLS-1$
        if (left.isBlank()) {
            return right.isBlank() ? null : right;
        }
        if (right.isBlank()) {
            return left;
        }
        if (left.contains(right)) {
            return left;
        }
        return left + " " + right; //$NON-NLS-1$
    }
}
