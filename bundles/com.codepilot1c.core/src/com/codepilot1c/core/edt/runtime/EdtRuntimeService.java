package com.codepilot1c.core.edt.runtime;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager.ThickClientInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder.ThickClientMode;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
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
            settings = accessManager.getSettings(infobase, InfobaseAccess.INFOBASE);
        } catch (Exception | NoSuchMethodError e) {
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
        ThickClientInfo info;
        try {
            if (versionMask != null && !versionMask.isBlank()) {
                info = runtimeComponentManager.getThickClientInfo(versionMask);
            } else {
                info = runtimeComponentManager.getThickClientInfo(infobase);
            }
        } catch (Exception | NoSuchMethodError e) {
            LOG.warn("Failed to resolve thick client (possible EDT API incompatibility): " + e.getMessage(), e); //$NON-NLS-1$
            return null;
        }
        if (info == null || info.component() == null || info.component().getFile() == null) {
            throw new IllegalStateException("Thick client runtime component not resolved for infobase"); //$NON-NLS-1$
        }
        return info;
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
        builder.updateInfobase();
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
                (Object proxy, Method method, Object[] args) -> handleUpdateCallback(method, args));
    }

    @SuppressWarnings("unchecked")
    private static Object handleUpdateCallback(Method method, Object[] args) throws Exception {
        String name = method.getName();
        if ("onConfirm".equals(name)) { //$NON-NLS-1$
            return Boolean.TRUE;
        }
        if ("onInfobaseChanges".equals(name)) { //$NON-NLS-1$
            if (args == null || args.length < 7) {
                return enumValue(method.getReturnType(), "DEFERRED"); //$NON-NLS-1$
            }
            Object conflictResolver = args[4];
            if (conflictResolver == null) {
                return enumValue(method.getReturnType(), "DEFERRED"); //$NON-NLS-1$
            }
            Method override = findMethod(conflictResolver.getClass(), "overrideConflict", 6); //$NON-NLS-1$
            if (override != null) {
                return override.invoke(conflictResolver, args[0], args[1], args[2], args[3], args[5], args[6]);
            }
            return enumValue(method.getReturnType(), "DEFERRED"); //$NON-NLS-1$
        }
        if ("toString".equals(name)) { //$NON-NLS-1$
            return "AutoUpdateCallbackProxy"; //$NON-NLS-1$
        }
        return null;
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

    @SuppressWarnings("unchecked")
    private static Object enumValue(Class<?> type, String name) {
        if (type != null && type.isEnum()) {
            return Enum.valueOf((Class<Enum>) type, name);
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
            settings = accessManager.getSettings(infobase, InfobaseAccess.INFOBASE);
        } catch (Exception | NoSuchMethodError e) {
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
