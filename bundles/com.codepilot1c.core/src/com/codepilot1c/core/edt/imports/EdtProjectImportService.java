package com.codepilot1c.core.edt.imports;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.wst.server.core.IRuntime;
import org.eclipse.wst.server.core.IServer;

import com._1c.g5.v8.dt.cli.api.workspace.IImportConfigurationFilesApi;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ConfigurationFilesFormat;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ConfigurationFilesKind;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager.ThickClientInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.diagnostics.DiagnosticsService;
import com.codepilot1c.core.diagnostics.DiagnosticsService.DiagnosticsSummary;
import com.codepilot1c.core.edt.runtime.EdtProjectResolver;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService.AccessSettings;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.VibeLogger;
import com.e1c.g5.v8.dt.platform.standaloneserver.core.StandaloneServerException;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase;

/**
 * Imports configuration from an associated infobase into a new EDT project.
 */
public class EdtProjectImportService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtProjectImportService.class);

    private final EdtProjectResolver projectResolver;
    private final EdtRuntimeService runtimeService;
    private final EdtProjectImportGateway gateway;
    private final DiagnosticsService diagnosticsService;

    public EdtProjectImportService() {
        this(new EdtProjectResolver(), new EdtRuntimeService(), new EdtProjectImportGateway(), new DiagnosticsService());
    }

    EdtProjectImportService(EdtProjectResolver projectResolver, EdtRuntimeService runtimeService,
            EdtProjectImportGateway gateway, DiagnosticsService diagnosticsService) {
        this.projectResolver = projectResolver;
        this.runtimeService = runtimeService;
        this.gateway = gateway;
        this.diagnosticsService = diagnosticsService;
    }

    public ImportProjectFromInfobaseResult importProject(String opId, ImportProjectFromInfobaseRequest request) {
        request.validate();
        String sourceProjectName = request.normalizedSourceProjectName();
        String targetProjectName = request.normalizedTargetProjectName();
        File workspaceRoot = gateway.getWorkspaceRoot();
        if (workspaceRoot == null) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, "workspace root is unavailable"); //$NON-NLS-1$
        }

        IProject sourceProject = requireProject(sourceProjectName);
        ensureTargetProjectAbsent(targetProjectName);
        String platformVersion = request.effectiveVersion(gateway.resolvePlatformVersion(sourceProject).toString());
        InfobaseReference infobase = projectResolver.resolveInfobase(sourceProjectName, workspaceRoot);
        ThickClientInfo thickClientInfo = resolveThickClientInfo(infobase, platformVersion);
        Path operationRoot = prepareOperationRoot(workspaceRoot.toPath(), opId);
        Path exportRoot = operationRoot.resolve("export"); //$NON-NLS-1$

        LOG.info("[%s] START import_project_from_infobase source=%s target=%s version=%s", //$NON-NLS-1$
                opId, sourceProjectName, targetProjectName, platformVersion);

        StandaloneServerImportInfo standalone = request.dryRun()
                ? new StandaloneServerImportInfo(false, false, "dry_run", "", "", "", "", "", "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                : ensureStandaloneServer(opId, request, infobase, platformVersion, operationRoot);

        if (request.dryRun()) {
            return new ImportProjectFromInfobaseResult(
                    opId,
                    "dry_run", //$NON-NLS-1$
                    sourceProjectName,
                    targetProjectName,
                    effectiveTargetProjectPath(request, targetProjectName),
                    platformVersion,
                    exportRoot.toAbsolutePath().normalize().toString(),
                    true,
                    standalone,
                    null);
        }

        Path exportedConfiguration = exportConfiguration(opId, infobase, thickClientInfo,
                runtimeService.resolveAccessSettings(infobase), exportRoot);
        importConfiguration(request, exportedConfiguration, platformVersion);

        IProject targetProject = awaitImportedProject(targetProjectName);
        waitForModelSynchronization(opId, targetProject);
        refreshProject(targetProject);
        DiagnosticsSummary diagnostics = diagnosticsService.collectProjectDiagnostics(
                targetProjectName,
                request.effectiveDiagnosticsMaxItems(),
                request.effectiveDiagnosticsWaitMs());

        return new ImportProjectFromInfobaseResult(
                opId,
                "completed", //$NON-NLS-1$
                sourceProjectName,
                targetProjectName,
                targetProject.getLocation() == null
                        ? effectiveTargetProjectPath(request, targetProjectName)
                        : targetProject.getLocation().toOSString(),
                platformVersion,
                exportedConfiguration.toAbsolutePath().normalize().toString(),
                false,
                standalone,
                diagnostics);
    }

    private IProject requireProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_NOT_FOUND, "EDT project not found: " + projectName); //$NON-NLS-1$
        }
        return project;
    }

    private void ensureTargetProjectAbsent(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project != null && project.exists()) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_ALREADY_EXISTS,
                    "Target project already exists: " + projectName); //$NON-NLS-1$
        }
    }

    private ThickClientInfo resolveThickClientInfo(InfobaseReference infobase, String platformVersion) {
        try {
            return runtimeService.resolveThickClientInfo(infobase, platformVersion);
        } catch (IllegalStateException e) {
            throw new EdtToolException(EdtToolErrorCode.RUNTIME_NOT_RESOLVED,
                    "Failed to resolve thick client: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    private Path prepareOperationRoot(Path workspaceRoot, String opId) {
        try {
            Path operationRoot = workspaceRoot.resolve(".codepilot").resolve("imports").resolve(opId); //$NON-NLS-1$ //$NON-NLS-2$
            Files.createDirectories(operationRoot);
            return operationRoot;
        } catch (Exception e) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_IMPORT_FAILED,
                    "Failed to prepare operation directory: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    private StandaloneServerImportInfo ensureStandaloneServer(String opId, ImportProjectFromInfobaseRequest request,
            InfobaseReference infobase, String platformVersion, Path operationRoot) {
        IStandaloneServerService service = gateway.getStandaloneServerService();
        Optional<IRuntime> runtime = service.findRuntime(platformVersion, new NullProgressMonitor());
        if (runtime.isEmpty()) {
            throw new EdtToolException(EdtToolErrorCode.STANDALONE_RUNTIME_NOT_FOUND,
                    "Standalone runtime not found for version: " + platformVersion); //$NON-NLS-1$
        }

        String clusterRegistryDirectory = request.effectiveClusterRegistryDirectory(operationRoot);
        String publicationPath = request.effectivePublicationPath(operationRoot);
        ensureDirectory(clusterRegistryDirectory, EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED);
        ensureDirectory(publicationPath, EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED);

        try {
            Object pair = invokeCreateServerWithInfobase(service,
                    platformVersion,
                    request.normalizedTargetProjectName(),
                    infobase,
                    request.effectiveClusterPort(),
                    clusterRegistryDirectory,
                    publicationPath,
                    new NullProgressMonitor());
            IServer server = castServer(readPairValue(pair, "first", "getFirst")); //$NON-NLS-1$ //$NON-NLS-2$
            StandaloneServerInfobase standaloneInfobase = castStandaloneInfobase(readPairValue(pair, "second", //$NON-NLS-1$
                    "getSecond")); //$NON-NLS-1$
            if (server == null || standaloneInfobase == null) {
                throw new EdtToolException(EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED,
                        "Standalone server creation returned incomplete result"); //$NON-NLS-1$
            }

            boolean started = false;
            String statusMessage = ""; //$NON-NLS-1$
            if (request.startServer()) {
                IStatus status = service.startServer(server, "run", new NullProgressMonitor()); //$NON-NLS-1$
                statusMessage = status == null || status.getMessage() == null ? "" : status.getMessage(); //$NON-NLS-1$
                if (status == null || status.matches(IStatus.ERROR | IStatus.CANCEL)) {
                    throw new EdtToolException(EdtToolErrorCode.STANDALONE_SERVER_START_FAILED,
                            "Failed to start standalone server: " + statusMessage); //$NON-NLS-1$
                }
                started = true;
            }

            return new StandaloneServerImportInfo(
                    true,
                    started,
                    statusMessage,
                    safe(server.getName()),
                    safe(service.getServerVersion(server)),
                    toPathString(service.getServerLocation(server)),
                    toPathString(service.getServerDataLocation(server)),
                    toUriString(service.getInfobaseUrl(standaloneInfobase)),
                    toUriString(service.getDesignerUrl(standaloneInfobase)));
        } catch (EdtToolException e) {
            throw e;
        } catch (StandaloneServerException e) {
            throw new EdtToolException(EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED,
                    "Standalone server operation failed: " + e.getMessage(), e); //$NON-NLS-1$
        } catch (ReflectiveOperationException e) {
            throw new EdtToolException(EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED,
                    "Failed to create standalone server: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    private Path exportConfiguration(String opId, InfobaseReference infobase, ThickClientInfo thickClientInfo,
            AccessSettings accessSettings, Path exportRoot) {
        try {
            Files.createDirectories(exportRoot);
            RuntimeExecutionArguments arguments = new RuntimeExecutionArguments();
            arguments.setMonitor(new NullProgressMonitor());
            applyAccessSettings(arguments, accessSettings);
            Path exported = thickClientInfo.launcher().exportFullXmlFromInfobase(
                    thickClientInfo.component(),
                    infobase,
                    ConfigurationFilesFormat.HIERARCHICAL,
                    ConfigurationFilesKind.PLAIN_FILES,
                    arguments,
                    exportRoot);
            LOG.info("[%s] Exported infobase configuration to %s", opId, exported); //$NON-NLS-1$
            return exported == null ? exportRoot : exported;
        } catch (Exception e) {
            throw new EdtToolException(EdtToolErrorCode.CONFIG_EXPORT_FAILED,
                    "Failed to export configuration from infobase: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    private void importConfiguration(ImportProjectFromInfobaseRequest request, Path exportPath, String platformVersion) {
        IImportConfigurationFilesApi importApi = gateway.getImportConfigurationFilesApi();
        try {
            Path projectLocation = request.resolveProjectLocation();
            if (projectLocation != null) {
                importApi.importProject(exportPath, projectLocation, platformVersion, request.normalizedBaseProjectName());
            } else {
                importApi.importProject(exportPath, request.normalizedTargetProjectName(), platformVersion,
                        request.normalizedBaseProjectName());
            }
        } catch (RuntimeException e) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_IMPORT_FAILED,
                    "Failed to import EDT project: " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    private IProject awaitImportedProject(String projectName) {
        for (int i = 0; i < 100; i++) {
            IProject project = gateway.resolveProject(projectName);
            if (project != null && project.exists()) {
                return project;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new EdtToolException(EdtToolErrorCode.PROJECT_IMPORT_FAILED,
                "Imported project did not appear in workspace: " + projectName); //$NON-NLS-1$
    }

    private void waitForModelSynchronization(String opId, IProject project) {
        try {
            gateway.waitForModelSynchronization(project);
        } catch (RuntimeException e) {
            LOG.warn("[%s] waitModelSynchronization skipped: %s", opId, e.getMessage()); //$NON-NLS-1$
        }
    }

    private void refreshProject(IProject project) {
        try {
            if (!project.isOpen()) {
                project.open(new NullProgressMonitor());
            }
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        } catch (Exception e) {
            LOG.warn("Project refresh failed for %s: %s", project.getName(), e.getMessage()); //$NON-NLS-1$
        }
    }

    private void applyAccessSettings(RuntimeExecutionArguments arguments, AccessSettings accessSettings) {
        if (arguments == null || accessSettings == null) {
            return;
        }
        if (accessSettings.isOsAuthentication()) {
            arguments.setAccess(InfobaseAccess.OS);
        } else if (accessSettings.isInfobaseAuthentication()) {
            arguments.setAccess(InfobaseAccess.INFOBASE);
            if (accessSettings.getUserName() != null && !accessSettings.getUserName().isBlank()) {
                arguments.setUsername(accessSettings.getUserName());
            }
            if (accessSettings.getPassword() != null && !accessSettings.getPassword().isBlank()) {
                arguments.setPassword(accessSettings.getPassword());
            }
        }
    }

    private Object invokeCreateServerWithInfobase(IStandaloneServerService service, String platformVersion,
            String projectName, InfobaseReference infobaseReference, int clusterPort,
            String clusterRegistryDirectory, String publicationPath, IProgressMonitor monitor)
            throws ReflectiveOperationException {
        Method method = service.getClass().getMethod(
                "createServerWithInfobase", //$NON-NLS-1$
                String.class,
                String.class,
                InfobaseReference.class,
                int.class,
                String.class,
                String.class,
                IProgressMonitor.class);
        try {
            return method.invoke(service, platformVersion, projectName, infobaseReference, Integer.valueOf(clusterPort),
                    clusterRegistryDirectory, publicationPath, monitor);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ReflectiveOperationException(cause);
        }
    }

    private Object readPairValue(Object pair, String methodName, String getterName) throws ReflectiveOperationException {
        if (pair == null) {
            return null;
        }
        try {
            Method method = pair.getClass().getMethod(methodName);
            return method.invoke(pair);
        } catch (NoSuchMethodException ignored) {
            // Try bean getter or public field fallback.
        }
        try {
            Method getter = pair.getClass().getMethod(getterName);
            return getter.invoke(pair);
        } catch (NoSuchMethodException ignored) {
            // Try public field fallback.
        }
        try {
            Field field = pair.getClass().getField(methodName);
            return field.get(pair);
        } catch (NoSuchFieldException e) {
            throw new ReflectiveOperationException("Pair accessor not found: " + methodName, e); //$NON-NLS-1$
        }
    }

    private IServer castServer(Object value) {
        return value instanceof IServer server ? server : null;
    }

    private StandaloneServerInfobase castStandaloneInfobase(Object value) {
        return value instanceof StandaloneServerInfobase infobase ? infobase : null;
    }

    private void ensureDirectory(String directory, EdtToolErrorCode code) {
        try {
            Files.createDirectories(Path.of(directory));
        } catch (Exception e) {
            throw new EdtToolException(code, "Failed to prepare directory: " + directory, e); //$NON-NLS-1$
        }
    }

    private static String effectiveTargetProjectPath(ImportProjectFromInfobaseRequest request, String targetProjectName) {
        Path projectLocation = request.resolveProjectLocation();
        return projectLocation == null ? targetProjectName : projectLocation.toAbsolutePath().normalize().toString();
    }

    private static String toPathString(Path path) {
        return path == null ? "" : path.toAbsolutePath().normalize().toString(); //$NON-NLS-1$
    }

    private static String toUriString(URI uri) {
        return uri == null ? "" : uri.toString(); //$NON-NLS-1$
    }

    private static String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
