package com.codepilot1c.core.edt.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/** Cross-version invocation of EDT's full reload API; no update-base fallback. */
public final class EdtExtensionReloadInvoker {
    private EdtExtensionReloadInvoker() {}

    public static boolean reload(Object manager, IProject extension, InfobaseReference infobase,
            Object callback, boolean keepConnected, IProgressMonitor monitor) throws Exception {
        Method selected = null;
        for (Method method : manager.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("reloadInfobase") && types.length == 5
                    && types[0] == IProject.class && types[1] == InfobaseReference.class
                    && types[2].isInstance(callback) && types[3] == boolean.class
                    && types[4] == IProgressMonitor.class) {
                selected = method;
                break;
            }
        }
        if (selected == null) {
            throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "This EDT synchronization service does not expose a compatible reloadInfobase API");
        }
        try {
            Object result = selected.invoke(manager, extension, infobase, callback, keepConnected, monitor);
            if (result instanceof Boolean value) {
                return value;
            }
            if (result instanceof IStatus status) {
                return status.isOK();
            }
            throw new EdtToolException(EdtToolErrorCode.UPDATE_FAILED,
                    "EDT reload returned no recognized completion result");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }
}
