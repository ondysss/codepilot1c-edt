package com.codepilot1c.core.remote;

import java.util.Map;

/**
 * UI-facing SPI used by core remote APIs to inspect and drive the workbench.
 */
public interface IRemoteWorkbenchBridge {

    IdeSnapshot captureSnapshot();

    RemoteCommandResult openFile(String path);

    RemoteCommandResult revealRange(String path, int startLine, int startColumn, int endLine, int endColumn);

    RemoteCommandResult getSelection();

    RemoteCommandResult replaceSelection(String text);

    RemoteCommandResult insertAtCursor(String text);

    RemoteCommandResult applyGeneratedCode(String response, boolean replaceSelection);

    RemoteCommandResult showView(String viewId);

    RemoteCommandResult activatePart(String partId);

    RemoteCommandResult executeCommand(String commandId, Map<String, Object> parameters);
}
