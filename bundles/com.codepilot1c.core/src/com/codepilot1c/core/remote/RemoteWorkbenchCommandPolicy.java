package com.codepilot1c.core.remote;

/**
 * Shared policy for remote workbench command access.
 */
public final class RemoteWorkbenchCommandPolicy {

    private RemoteWorkbenchCommandPolicy() {
    }

    public static boolean isDenied(String commandId) {
        String id = commandId != null ? commandId.trim().toLowerCase() : ""; //$NON-NLS-1$
        return id.equals("org.eclipse.ui.file.exit") //$NON-NLS-1$
                || id.contains("restart") //$NON-NLS-1$
                || id.contains("update") //$NON-NLS-1$
                || id.contains("install") //$NON-NLS-1$
                || id.contains("preference") //$NON-NLS-1$
                || id.contains("password") //$NON-NLS-1$
                || id.contains("credential") //$NON-NLS-1$
                || id.contains("delete") //$NON-NLS-1$
                || id.contains("quit"); //$NON-NLS-1$
    }
}
