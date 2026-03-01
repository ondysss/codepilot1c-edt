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
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

public class EdtRuntimeService {

    private final EdtRuntimeGateway gateway;

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
        IInfobaseAssociationManager manager = gateway.getInfobaseAssociationManager();
        IInfobaseAssociation association = manager.getAssociation(project)
                .orElseThrow(() -> new IllegalStateException("Infobase association not found for project: "
                        + projectName)); //$NON-NLS-1$
        InfobaseReference infobase = association.getDefaultInfobase();
        if (infobase == null && !association.getInfobases().isEmpty()) {
            infobase = association.getInfobases().iterator().next();
        }
        if (infobase == null) {
            throw new IllegalStateException("Infobase reference not found for project: " + projectName); //$NON-NLS-1$
        }
        return infobase;
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
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve thick client: " + e.getMessage(), e); //$NON-NLS-1$
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
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        ThickClientInfo info = resolveThickClientInfo(infobase, versionMask);
        File clientFile = info.component().getFile();

        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(clientFile,
                ThickClientMode.ENTERPRISE);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        applyAccessSettings(builder, infobase);
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
        InfobaseReference infobase = resolveDefaultInfobase(projectName);
        ThickClientInfo info = resolveThickClientInfo(infobase, versionMask);
        File clientFile = info.component().getFile();

        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(clientFile,
                ThickClientMode.ENTERPRISE);
        if (infobase.getConnectionString() == null) {
            throw new IllegalStateException("Infobase connection string not available"); //$NON-NLS-1$
        }
        builder.forInfobase(infobase.getConnectionString(), false);
        applyAccessSettings(builder, infobase);
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
        } catch (Exception e) {
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
}
