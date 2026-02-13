package com.codepilot1c.core.edt.ast;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;

/**
 * Validates project readiness before EDT AST operations.
 */
public class ProjectReadinessChecker {

    public enum State {
        READY,
        BUILDING,
        NOT_AVAILABLE,
        UNKNOWN
    }

    public static class Result {
        private final State state;
        private final String message;

        public Result(State state, String message) {
            this.state = state;
            this.message = message;
        }

        public State getState() {
            return state;
        }

        public String getMessage() {
            return message;
        }

        public boolean isReady() {
            return state == State.READY;
        }
    }

    private final EdtServiceGateway gateway;

    public ProjectReadinessChecker(EdtServiceGateway gateway) {
        this.gateway = gateway;
    }

    public Result check(IProject project) {
        if (project == null || !project.exists()) {
            return new Result(State.NOT_AVAILABLE, "Project not found"); //$NON-NLS-1$
        }
        if (!project.isOpen()) {
            return new Result(State.NOT_AVAILABLE, "Project is closed"); //$NON-NLS-1$
        }

        IDtProjectManager dtProjectManager = gateway.getDtProjectManager();
        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null) {
            return new Result(State.NOT_AVAILABLE, "Not an EDT project"); //$NON-NLS-1$
        }

        IDerivedDataManagerProvider provider = gateway.getDerivedDataManagerProvider();
        IDerivedDataManager ddManager = provider.get(dtProject);
        if (ddManager == null) {
            return new Result(State.UNKNOWN, "Cannot determine derived-data status"); //$NON-NLS-1$
        }

        if (!ddManager.isIdle()) {
            DerivedDataStatus status = ddManager.getDerivedDataStatus();
            return new Result(State.BUILDING,
                    "Project is building: " + (status != null ? status : "computing")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (!ddManager.isAllComputed()) {
            return new Result(State.BUILDING, "Project derived data is not fully computed"); //$NON-NLS-1$
        }

        return new Result(State.READY, "Project is ready"); //$NON-NLS-1$
    }

    public void ensureReady(IProject project) {
        Result result = check(project);
        if (result.isReady()) {
            return;
        }

        EdtAstErrorCode code = result.getState() == State.NOT_AVAILABLE
                ? EdtAstErrorCode.PROJECT_NOT_FOUND
                : EdtAstErrorCode.PROJECT_NOT_READY;
        throw new EdtAstException(code, result.getMessage(), true);
    }
}
