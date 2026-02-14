package com.codepilot1c.core.edt.metadata;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Validates EDT project readiness before metadata changes.
 */
public class MetadataProjectReadinessChecker {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(MetadataProjectReadinessChecker.class);
    private static final long WAIT_TIMEOUT_MS = Long.getLong("codepilot1c.edt.readiness.wait.ms", 90_000L); //$NON-NLS-1$
    private static final long WAIT_POLL_MS = Long.getLong("codepilot1c.edt.readiness.poll.ms", 500L); //$NON-NLS-1$
    private static final boolean REQUIRE_ALL_COMPUTED = Boolean
            .getBoolean("codepilot1c.edt.readiness.require_all_computed"); //$NON-NLS-1$
    private static final long PROGRESS_LOG_STEP_MS = 5_000L;

    private final EdtMetadataGateway gateway;

    public MetadataProjectReadinessChecker(EdtMetadataGateway gateway) {
        this.gateway = gateway;
    }

    public void ensureReady(IProject project) {
        String opId = LogSanitizer.newId("readiness"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        if (project == null || !project.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Project not found", false); //$NON-NLS-1$
        }
        if (!project.isOpen()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_READY,
                    "Project is closed", true); //$NON-NLS-1$
        }

        IDtProjectManager dtProjectManager = gateway.getDtProjectManager();
        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_READY,
                    "Not an EDT project", false); //$NON-NLS-1$
        }

        IDerivedDataManagerProvider provider = gateway.getDerivedDataManagerProvider();
        IDerivedDataManager ddManager = provider.get(dtProject);
        if (ddManager == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_READY,
                    "Cannot determine derived-data status", true); //$NON-NLS-1$
        }

        if (isReady(ddManager)) {
            LOG.debug("[%s] Project %s is ready without wait (idle=%s allComputed=%s strict=%s)", opId, // $NON-NLS-1$
                    project.getName(), ddManager.isIdle(), ddManager.isAllComputed(), REQUIRE_ALL_COMPUTED);
            return;
        }

        LOG.info("[%s] Waiting for project readiness: project=%s timeout=%dms poll=%dms strict=%s", // $NON-NLS-1$
                opId, project.getName(), WAIT_TIMEOUT_MS, WAIT_POLL_MS, REQUIRE_ALL_COMPUTED);
        long deadline = startedAt + WAIT_TIMEOUT_MS;
        long nextProgressLogAt = startedAt + PROGRESS_LOG_STEP_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isReady(ddManager)) {
                LOG.info("[%s] Project %s became ready in %s", opId, project.getName(), // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt));
                return;
            }
            if (System.currentTimeMillis() >= nextProgressLogAt) {
                DerivedDataStatus progressStatus = ddManager.getDerivedDataStatus();
                LOG.debug("[%s] Still waiting readiness: elapsed=%s idle=%s allComputed=%s status=%s", // $NON-NLS-1$
                        opId,
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        ddManager.isIdle(),
                        ddManager.isAllComputed(),
                        describeStatus(progressStatus));
                nextProgressLogAt += PROGRESS_LOG_STEP_MS;
            }
            try {
                Thread.sleep(WAIT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MetadataOperationException(
                        MetadataOperationCode.PROJECT_NOT_READY,
                        "Readiness wait interrupted", true, e); //$NON-NLS-1$
            }
        }

        DerivedDataStatus status = ddManager.getDerivedDataStatus();
        LOG.warn("[%s] Project %s is still not ready after %s: idle=%s computed=%s strict=%s status=%s", // $NON-NLS-1$
                opId,
                project.getName(),
                LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                ddManager.isIdle(),
                ddManager.isAllComputed(),
                REQUIRE_ALL_COMPUTED,
                describeStatus(status));
        throw new MetadataOperationException(
                MetadataOperationCode.PROJECT_NOT_READY,
                "Project derived-data is not ready after wait: idle=" + ddManager.isIdle() //$NON-NLS-1$
                        + ", allComputed=" + ddManager.isAllComputed() //$NON-NLS-1$
                        + ", strict=" + REQUIRE_ALL_COMPUTED //$NON-NLS-1$
                        + ", status=" + describeStatus(status), //$NON-NLS-1$
                true);
    }

    private boolean isReady(IDerivedDataManager ddManager) {
        return ddManager.isIdle() && (!REQUIRE_ALL_COMPUTED || ddManager.isAllComputed());
    }

    private String describeStatus(DerivedDataStatus status) {
        if (status == null) {
            return "null"; //$NON-NLS-1$
        }
        StringBuilder out = new StringBuilder(status.getClass().getSimpleName());
        out.append('{');
        boolean hasData = false;
        for (var method : status.getClass().getMethods()) {
            String name = method.getName();
            if (method.getParameterCount() != 0 || name.equals("getClass")) { //$NON-NLS-1$
                continue;
            }
            boolean getter = name.startsWith("get") || name.startsWith("is"); //$NON-NLS-1$ //$NON-NLS-2$
            if (!getter || method.getReturnType() == Void.TYPE) {
                continue;
            }
            try {
                Object value = method.invoke(status);
                if (hasData) {
                    out.append(", "); //$NON-NLS-1$
                }
                out.append(name).append('=').append(value);
                hasData = true;
            } catch (ReflectiveOperationException ignored) {
                // Ignore reflection errors and continue with best-effort status dump.
            }
        }
        if (!hasData) {
            out.append("toString=").append(status);
        }
        out.append('}');
        return out.toString();
    }
}
