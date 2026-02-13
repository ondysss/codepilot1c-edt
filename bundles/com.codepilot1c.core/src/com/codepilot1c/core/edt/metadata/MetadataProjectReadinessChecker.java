package com.codepilot1c.core.edt.metadata;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;

/**
 * Validates EDT project readiness before metadata changes.
 */
public class MetadataProjectReadinessChecker {

    private final EdtMetadataGateway gateway;

    public MetadataProjectReadinessChecker(EdtMetadataGateway gateway) {
        this.gateway = gateway;
    }

    public void ensureReady(IProject project) {
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

        if (!ddManager.isIdle() || !ddManager.isAllComputed()) {
            DerivedDataStatus status = ddManager.getDerivedDataStatus();
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_READY,
                    "Project derived-data is not ready: " + (status != null ? status : "computing"), true); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
