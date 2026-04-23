package com.codepilot1c.core.edt.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.wst.server.core.IRuntime;
import org.eclipse.wst.server.core.IServer;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferenceException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.logging.VibeLogger;
import com.e1c.g5.v8.dt.platform.standaloneserver.core.StandaloneServerException;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase;

/**
 * Service that programmatically binds an infobase (file- or standalone-server-based) to an EDT project.
 *
 * <p>This encapsulates the EDT API calls used by the {@code connect_infobase} tool so the tool itself
 * stays easy to unit-test with a stub service.</p>
 */
public class EdtInfobaseConnectService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtInfobaseConnectService.class);

    private static final int DEFAULT_CLUSTER_PORT = 1541;

    public enum ConnectionKind {
        FILE,
        STANDALONE;

        public static ConnectionKind parse(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            if ("file".equalsIgnoreCase(trimmed)) { //$NON-NLS-1$
                return FILE;
            }
            if ("standalone".equalsIgnoreCase(trimmed)) { //$NON-NLS-1$
                return STANDALONE;
            }
            return null;
        }
    }

    public static final class ConnectRequest {
        private final String projectName;
        private final String databasePath;
        private final ConnectionKind kind;
        private final String login;
        private final String password;
        private final boolean setPrimary;
        private final Integer serverPort;
        private final String runtimeVersion;
        private final boolean force;

        public ConnectRequest(String projectName, String databasePath, ConnectionKind kind, String login,
                String password, boolean setPrimary, Integer serverPort, String runtimeVersion) {
            this(projectName, databasePath, kind, login, password, setPrimary, serverPort, runtimeVersion, false);
        }

        public ConnectRequest(String projectName, String databasePath, ConnectionKind kind, String login,
                String password, boolean setPrimary, Integer serverPort, String runtimeVersion, boolean force) {
            this.projectName = projectName;
            this.databasePath = databasePath;
            this.kind = kind;
            this.login = login;
            this.password = password;
            this.setPrimary = setPrimary;
            this.serverPort = serverPort;
            this.runtimeVersion = runtimeVersion;
            this.force = force;
        }

        public String projectName() { return projectName; }
        public String databasePath() { return databasePath; }
        public ConnectionKind kind() { return kind; }
        public String login() { return login; }
        public String password() { return password; }
        public boolean setPrimary() { return setPrimary; }
        public Integer serverPort() { return serverPort; }
        public String runtimeVersion() { return runtimeVersion; }
        public boolean force() { return force; }
    }

    public static final class ConnectResult {
        private final ConnectionKind kind;
        private final String resolvedPath;
        private final String infobaseName;
        private final String login;
        private final Integer serverPort;
        private final boolean primary;
        private final String replacedPrevious;

        public ConnectResult(ConnectionKind kind, String resolvedPath, String infobaseName, String login,
                Integer serverPort, boolean primary) {
            this(kind, resolvedPath, infobaseName, login, serverPort, primary, null);
        }

        public ConnectResult(ConnectionKind kind, String resolvedPath, String infobaseName, String login,
                Integer serverPort, boolean primary, String replacedPrevious) {
            this.kind = kind;
            this.resolvedPath = resolvedPath;
            this.infobaseName = infobaseName;
            this.login = login;
            this.serverPort = serverPort;
            this.primary = primary;
            this.replacedPrevious = replacedPrevious;
        }

        public ConnectionKind kind() { return kind; }
        public String resolvedPath() { return resolvedPath; }
        public String infobaseName() { return infobaseName; }
        public String login() { return login; }
        public Integer serverPort() { return serverPort; }
        public boolean primary() { return primary; }
        public String replacedPrevious() { return replacedPrevious; }
    }

    private final EdtRuntimeGateway gateway;

    public EdtInfobaseConnectService() {
        this(new EdtRuntimeGateway());
    }

    public EdtInfobaseConnectService(EdtRuntimeGateway gateway) {
        this.gateway = gateway;
    }

    public ConnectResult connect(ConnectRequest request) {
        if (request == null) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "request is required"); //$NON-NLS-1$
        }
        validate(request);

        IProject project = resolveProject(request.projectName());
        return switch (request.kind()) {
            case FILE -> connectFile(project, request);
            case STANDALONE -> connectStandalone(project, request);
        };
    }

    private void validate(ConnectRequest request) {
        if (request.projectName() == null || request.projectName().isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "project_name is required"); //$NON-NLS-1$
        }
        if (request.databasePath() == null || request.databasePath().isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "database_path is required"); //$NON-NLS-1$
        }
        if (request.kind() == null) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "kind is required and must be 'file' or 'standalone'"); //$NON-NLS-1$
        }
    }

    private IProject resolveProject(String projectName) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace() == null
                ? null : ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root == null ? null : root.getProject(projectName);
        if (project == null || !project.exists()) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_NOT_FOUND,
                    "EDT project not found: " + projectName); //$NON-NLS-1$
        }
        return project;
    }

    private ConnectResult connectFile(IProject project, ConnectRequest request) {
        Path resolvedPath = ensureFileInfobasePath(request.databasePath());
        String filePathArg = resolvedPath.toAbsolutePath().toString();

        String replacedPrevious = checkExistingPrimary(project, request);

        InfobaseReference reference = InfobaseReferences.newFileInfobaseReference(filePathArg);
        String infobaseName = reference.getName();
        if (infobaseName == null || infobaseName.isBlank()) {
            infobaseName = resolvedPath.getFileName() == null
                    ? "infobase" //$NON-NLS-1$
                    : resolvedPath.getFileName().toString();
            reference.setName(infobaseName);
        }
        persistReference(reference);
        storeAccessSettings(reference, request.login(), request.password());
        boolean primary = associate(project, reference, request.setPrimary());

        LOG.info("connect_infobase(file) project=%s path=%s primary=%s", //$NON-NLS-1$
                request.projectName(), filePathArg, Boolean.valueOf(primary));

        return new ConnectResult(ConnectionKind.FILE, filePathArg, infobaseName,
                sanitizeLogin(request.login()), null, primary, replacedPrevious);
    }

    private ConnectResult connectStandalone(IProject project, ConnectRequest request) {
        Path resolvedPath = ensureStandaloneDataPath(request.databasePath());
        String filePathArg = resolvedPath.toAbsolutePath().toString();
        int port = request.serverPort() != null && request.serverPort().intValue() > 0
                ? request.serverPort().intValue() : DEFAULT_CLUSTER_PORT;

        String replacedPrevious = checkExistingPrimary(project, request);

        InfobaseReference reference = InfobaseReferences.newFileInfobaseReference(filePathArg);
        String infobaseName = reference.getName();
        if (infobaseName == null || infobaseName.isBlank()) {
            infobaseName = resolvedPath.getFileName() == null
                    ? "infobase" //$NON-NLS-1$
                    : resolvedPath.getFileName().toString();
            reference.setName(infobaseName);
        }

        IStandaloneServerService service = gateway.getStandaloneServerService();
        String version = request.runtimeVersion();
        IRuntime runtime = findRuntime(service, version);
        if (runtime == null) {
            throw new EdtToolException(EdtToolErrorCode.STANDALONE_RUNTIME_NOT_FOUND,
                    "Standalone runtime not found for version: " //$NON-NLS-1$
                            + (version == null ? "<default>" : version)); //$NON-NLS-1$
        }

        Path operationRoot = resolvedPath.resolve(".codepilot-standalone"); //$NON-NLS-1$
        ensureDirectory(operationRoot);
        String clusterRegistryDirectory = operationRoot.resolve("cluster-registry").toString(); //$NON-NLS-1$
        String publicationPath = operationRoot.resolve("publication").toString(); //$NON-NLS-1$
        ensureDirectory(Path.of(clusterRegistryDirectory));
        ensureDirectory(Path.of(publicationPath));

        InfobaseReference boundReference;
        try {
            Object pair = invokeCreateServerWithInfobase(service,
                    version == null ? "" : version, //$NON-NLS-1$
                    request.projectName(),
                    reference,
                    port,
                    clusterRegistryDirectory,
                    publicationPath,
                    new NullProgressMonitor());
            Object first = readPairValue(pair, "first", "getFirst"); //$NON-NLS-1$ //$NON-NLS-2$
            Object second = readPairValue(pair, "second", "getSecond"); //$NON-NLS-1$ //$NON-NLS-2$
            IServer server = first instanceof IServer s ? s : null;
            StandaloneServerInfobase standaloneInfobase = second instanceof StandaloneServerInfobase sai ? sai : null;
            if (server == null || standaloneInfobase == null) {
                throw new EdtToolException(EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED,
                        "Standalone server creation returned incomplete result"); //$NON-NLS-1$
            }
            boundReference = resolveBoundReference(standaloneInfobase, reference);
        } catch (EdtToolException e) {
            throw e;
        } catch (StandaloneServerException e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED,
                    "Standalone server operation failed: " + detail, e); //$NON-NLS-1$
        } catch (ReflectiveOperationException e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.STANDALONE_SERVER_CREATE_FAILED,
                    "Failed to create standalone server: " + detail, e); //$NON-NLS-1$
        }

        if (boundReference.getName() == null || boundReference.getName().isBlank()) {
            boundReference.setName(infobaseName);
        }

        storeAccessSettings(boundReference, request.login(), request.password());
        boolean primary = associate(project, boundReference, request.setPrimary());

        LOG.info("connect_infobase(standalone) project=%s path=%s port=%d primary=%s", //$NON-NLS-1$
                request.projectName(), filePathArg, Integer.valueOf(port), Boolean.valueOf(primary));

        return new ConnectResult(ConnectionKind.STANDALONE, filePathArg, boundReference.getName(),
                sanitizeLogin(request.login()), Integer.valueOf(port), primary, replacedPrevious);
    }

    /**
     * When {@code set_primary=true} is requested and the project already has a primary infobase:
     * <ul>
     *   <li>If {@code force=false}, throws {@link EdtToolException} with
     *       {@link EdtToolErrorCode#PRIMARY_EXISTS} — callers surface this so the user can decide.</li>
     *   <li>If {@code force=true}, returns the name of the infobase being replaced so it can be
     *       included in the success payload.</li>
     * </ul>
     * Returns {@code null} when there is no existing primary or {@code set_primary=false}.
     */
    protected String checkExistingPrimary(IProject project, ConnectRequest request) {
        if (!request.setPrimary()) {
            return null;
        }
        IInfobaseAssociationManager associationManager;
        try {
            associationManager = gateway.getInfobaseAssociationManager();
        } catch (IllegalStateException e) {
            // Association manager not available — nothing to check; a later associate() call will fail
            // with its own error. Do not block the primary-exists check here.
            return null;
        }
        IInfobaseAssociation association;
        try {
            association = associationManager.getAssociation(project).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
        if (association == null) {
            return null;
        }
        InfobaseReference existing = association.getDefaultInfobase();
        if (existing == null) {
            return null;
        }
        String existingName = existing.getName();
        if (existingName == null || existingName.isBlank()) {
            existingName = "<unnamed>"; //$NON-NLS-1$
        }
        if (!request.force()) {
            throw new EdtToolException(EdtToolErrorCode.PRIMARY_EXISTS,
                    "primary_exists: current_primary=" + existingName //$NON-NLS-1$
                            + ", pass force=true to replace"); //$NON-NLS-1$
        }
        return existingName;
    }

    // -- EDT write-side operations --------------------------------------------------------------

    protected void persistReference(InfobaseReference reference) {
        IInfobaseManager manager;
        try {
            manager = gateway.getInfobaseManager();
        } catch (IllegalStateException e) {
            // Do NOT swallow: without the manager nothing is actually persisted and later reads fail.
            throw new IllegalStateException(
                    "IInfobaseManager service unavailable \u2014 EDT may not be fully initialized", e); //$NON-NLS-1$
        }
        if (manager == null) {
            throw new IllegalStateException(
                    "IInfobaseManager service unavailable \u2014 EDT may not be fully initialized"); //$NON-NLS-1$
        }
        // The downstream EDT call {@code IInfobaseAccessManager.storeSettings} NPEs when the
        // reference has no UUID. Ensure every return path below leaves the reference with a
        // non-null UUID. See GH issue #31.
        if (!manager.isPersistenceSupported()) {
            if (reference.getUuid() == null) {
                reference.setUuid(UUID.randomUUID());
            }
            return;
        }
        Optional<InfobaseReference> existing = findExisting(manager, reference);
        if (existing.isPresent()) {
            // Copy the existing entry's UUID onto the in-memory reference so downstream calls
            // (storeSettings, associate) target the already-registered row.
            UUID existingUuid = existing.get().getUuid();
            if (existingUuid != null && reference.getUuid() == null) {
                reference.setUuid(existingUuid);
            }
            if (reference.getUuid() == null) {
                // Defense-in-depth: existing entry had no UUID either — assign a fresh one so
                // storeSettings doesn't NPE.
                reference.setUuid(UUID.randomUUID());
            }
            return;
        }
        try {
            manager.add(reference, null);
        } catch (InfobaseReferenceException e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to register infobase reference: " + detail, e); //$NON-NLS-1$
        }
        // manager.add() normally populates the UUID; if it didn't, assign one locally so the
        // subsequent storeSettings call has a non-null key.
        if (reference.getUuid() == null) {
            reference.setUuid(UUID.randomUUID());
        }
    }

    private Optional<InfobaseReference> findExisting(IInfobaseManager manager, InfobaseReference reference) {
        try {
            if (reference.getUuid() != null) {
                Optional<InfobaseReference> byUuid = manager.findInfobaseByUuid(reference.getUuid());
                if (byUuid.isPresent()) {
                    return byUuid;
                }
            }
            String name = reference.getName();
            if (name != null && !name.isBlank()) {
                Optional<InfobaseReference> byName = manager.findInfobaseByName(name);
                if (byName.isPresent()) {
                    return byName;
                }
            }
        } catch (RuntimeException ignored) {
            // Best-effort lookup; caller falls through to manager.add() or a local UUID assignment.
        }
        return Optional.empty();
    }

    protected void storeAccessSettings(InfobaseReference reference, String login, String password) {
        // Guard against EDT's storeSettings NPE when the reference has no UUID. persistReference()
        // is responsible for assigning one; bail early with a clear diagnostic if it didn't so the
        // failure doesn't surface as an opaque ": null" message. See GH issue #31.
        if (reference.getUuid() == null) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot store access settings: infobase reference has no UUID " //$NON-NLS-1$
                            + "(persistReference did not assign one)"); //$NON-NLS-1$
        }
        IInfobaseAccessManager accessManager = gateway.getInfobaseAccessManager();
        InfobaseAccess access = (login != null && !login.isBlank())
                ? InfobaseAccess.INFOBASE : InfobaseAccess.OS;
        InfobaseAccessSettings settings = new InfobaseAccessSettings(
                access,
                access == InfobaseAccess.INFOBASE ? login : null,
                access == InfobaseAccess.INFOBASE ? (password == null ? "" : password) : null, //$NON-NLS-1$
                null);
        try {
            accessManager.storeSettings(reference, settings);
        } catch (Exception e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to store infobase access settings: " + detail, e); //$NON-NLS-1$
        }
    }

    protected boolean associate(IProject project, InfobaseReference reference, boolean setPrimary) {
        IInfobaseAssociationManager associationManager = gateway.getInfobaseAssociationManager();
        InfobaseAssociationSettings settings = InfobaseAssociationSettings.alreadySynchronized();
        try {
            associationManager.associate(project, reference, settings);
        } catch (InfobaseAssociationException e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to associate infobase with project: " + detail, e); //$NON-NLS-1$
        }
        if (setPrimary) {
            try {
                associationManager.setDefaultInfobase(project, reference, InfobaseAssociationContext.empty());
            } catch (InfobaseAssociationException e) {
                String detail = e.getMessage() != null && !e.getMessage().isBlank()
                        ? e.getMessage() : e.getClass().getSimpleName();
                throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Failed to set default infobase for project: " + detail, e); //$NON-NLS-1$
            }
            return true;
        }
        return false;
    }

    // -- Helpers --------------------------------------------------------------------------------

    public static Path ensureFileInfobasePath(String rawPath) {
        Path path = validateAndNormalizePath(rawPath);
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "database_path must be a directory: " + path); //$NON-NLS-1$
        }
        ensureDirectory(path);
        return path;
    }

    public static Path ensureStandaloneDataPath(String rawPath) {
        Path path = validateAndNormalizePath(rawPath);
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "database_path must be a directory: " + path); //$NON-NLS-1$
        }
        ensureDirectory(path);
        return path;
    }

    /**
     * Prevent path-traversal: reject raw {@code ..} segments, then require the resolved absolute
     * path to live inside the Eclipse workspace root or the current user's home directory.
     */
    public static Path validateAndNormalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "database_path is required"); //$NON-NLS-1$
        }
        Path raw;
        try {
            raw = Path.of(rawPath);
        } catch (RuntimeException e) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_PATH,
                    "invalid_path: database_path is not a valid filesystem path"); //$NON-NLS-1$
        }
        // Defense-in-depth: reject any '..' segment before normalization folds it away.
        for (Path segment : raw) {
            if ("..".equals(segment.toString())) { //$NON-NLS-1$
                throw new EdtToolException(EdtToolErrorCode.INVALID_PATH,
                        "invalid_path: database_path must not contain '..' segments"); //$NON-NLS-1$
            }
        }
        Path path = raw.toAbsolutePath().normalize();
        if (!isInsideAllowedRoot(path)) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_PATH,
                    "invalid_path: database_path must be inside workspace or home directory"); //$NON-NLS-1$
        }
        return path;
    }

    private static boolean isInsideAllowedRoot(Path candidate) {
        Path workspaceRoot = null;
        try {
            if (ResourcesPlugin.getWorkspace() != null
                    && ResourcesPlugin.getWorkspace().getRoot() != null
                    && ResourcesPlugin.getWorkspace().getRoot().getLocation() != null) {
                workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
                        .toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            workspaceRoot = null;
        }
        Path home = null;
        String userHome = System.getProperty("user.home"); //$NON-NLS-1$
        if (userHome != null && !userHome.isBlank()) {
            try {
                home = Path.of(userHome).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                home = null;
            }
        }
        return (workspaceRoot != null && candidate.startsWith(workspaceRoot))
                || (home != null && candidate.startsWith(home));
    }

    private static void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "Failed to create directory: " + path + " (" + detail + ")", e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    private static IRuntime findRuntime(IStandaloneServerService service, String version) {
        String normalized = version == null ? "" : version.trim(); //$NON-NLS-1$
        if (!normalized.isEmpty()) {
            Optional<IRuntime> explicit = service.findRuntime(normalized, new NullProgressMonitor());
            if (explicit.isPresent()) {
                return explicit.get();
            }
        }
        Collection<IRuntime> runtimes = service.getRuntimes();
        return runtimes == null || runtimes.isEmpty() ? null : runtimes.iterator().next();
    }

    private static Object invokeCreateServerWithInfobase(IStandaloneServerService service, String platformVersion,
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
            return method.invoke(service, platformVersion, projectName, infobaseReference,
                    Integer.valueOf(clusterPort), clusterRegistryDirectory, publicationPath, monitor);
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

    private static Object readPairValue(Object pair, String methodName, String getterName)
            throws ReflectiveOperationException {
        if (pair == null) {
            return null;
        }
        try {
            Method method = pair.getClass().getMethod(methodName);
            return method.invoke(pair);
        } catch (NoSuchMethodException ignored) {
            // Try bean getter fallback.
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

    private InfobaseReference resolveBoundReference(StandaloneServerInfobase standaloneInfobase,
            InfobaseReference fallback) {
        if (standaloneInfobase == null) {
            return fallback;
        }
        try {
            IInfobaseManager manager = gateway.getInfobaseManager();
            if (standaloneInfobase.getInfobaseId() != null) {
                return manager.findInfobaseByUuid(standaloneInfobase.getInfobaseId()).orElse(fallback);
            }
            if (standaloneInfobase.getName() != null) {
                return manager.findInfobaseByName(standaloneInfobase.getName()).orElse(fallback);
            }
        } catch (RuntimeException e) {
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
            LOG.warn("Failed to resolve bound standalone infobase reference: %s", detail); //$NON-NLS-1$
        }
        return fallback;
    }

    private static String sanitizeLogin(String login) {
        return login == null || login.isBlank() ? null : login;
    }
}
