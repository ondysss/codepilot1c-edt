package com.codepilot1c.ui.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import com.codepilot1c.core.remote.IdeSnapshot;
import com.codepilot1c.core.remote.IRemoteWorkbenchBridge;
import com.codepilot1c.core.remote.RemoteCommandResult;
import com.codepilot1c.core.remote.RemoteWorkbenchCommandPolicy;
import com.codepilot1c.ui.editor.CodeApplicationService;

/**
 * SWT/workbench-backed implementation of the remote bridge.
 */
public class RemoteWorkbenchBridge implements IRemoteWorkbenchBridge {

    private static final int DIAGNOSTIC_LIMIT = 200;
    private static final int COMMAND_LIMIT = 500;

    @Override
    public IdeSnapshot captureSnapshot() {
        return withWorkbenchResult(() -> {
            IWorkbenchWindow window = activeWindow();
            IWorkbenchPage page = activePage(window);
            CodeApplicationService.SelectionInfo selection = CodeApplicationService.getInstance().getCurrentSelection();

            Map<String, Object> workbench = new LinkedHashMap<>();
            workbench.put("available", Boolean.valueOf(window != null)); //$NON-NLS-1$
            workbench.put("workspaceRoot", workspaceRootPath()); //$NON-NLS-1$
            workbench.put("activePartId", activePartId(page)); //$NON-NLS-1$
            workbench.put("activePartTitle", activePartTitle(page)); //$NON-NLS-1$

            Map<String, Object> editor = new LinkedHashMap<>();
            if (selection != null) {
                editor.put("available", Boolean.TRUE); //$NON-NLS-1$
                editor.put("filePath", safe(selection.getFileName())); //$NON-NLS-1$
                editor.put("offset", Integer.valueOf(selection.getOffset())); //$NON-NLS-1$
                editor.put("length", Integer.valueOf(selection.getLength())); //$NON-NLS-1$
                editor.put("selectedText", safe(selection.getSelectedText())); //$NON-NLS-1$
                populateSelectionLines(editor);
            } else {
                editor.put("available", Boolean.FALSE); //$NON-NLS-1$
            }

            return new IdeSnapshot(
                    java.time.Instant.now(),
                    workbench,
                    editor,
                    collectDiagnostics(),
                    collectOpenViews(page),
                    collectCommands(window));
        }, IdeSnapshot.unavailable("Workbench недоступен")); //$NON-NLS-1$
    }

    @Override
    public RemoteCommandResult openFile(String path) {
        return withWorkbenchResult(() -> {
            IWorkbenchPage page = requirePage();
            IFile file = resolveWorkspaceFile(path);
            if (file == null || !file.exists()) {
                return RemoteCommandResult.error("file_not_found", "Файл workspace не найден: " + safe(path)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            IDE.openEditor(page, file, true);
            return RemoteCommandResult.ok("Редактор открыт", Map.of("path", file.getFullPath().toString())); //$NON-NLS-1$ //$NON-NLS-2$
        }, RemoteCommandResult.error("workbench_unavailable", "Workbench недоступен")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public RemoteCommandResult revealRange(String path, int startLine, int startColumn, int endLine, int endColumn) {
        return withWorkbenchResult(() -> {
            IWorkbenchPage page = requirePage();
            IFile file = resolveWorkspaceFile(path);
            if (file == null || !file.exists()) {
                return RemoteCommandResult.error("file_not_found", "Файл workspace не найден: " + safe(path)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            IEditorPart editorPart = IDE.openEditor(page, file, true);
            ITextEditor textEditor = adaptTextEditor(editorPart);
            if (textEditor == null) {
                return RemoteCommandResult.error("not_text_editor", "Активный редактор не поддерживает текстовое выделение"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
            if (document == null) {
                return RemoteCommandResult.error("document_unavailable", "Документ редактора недоступен"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            int offset = document.getLineOffset(Math.max(0, startLine - 1)) + Math.max(0, startColumn - 1);
            int endOffset = document.getLineOffset(Math.max(0, endLine - 1)) + Math.max(0, endColumn - 1);
            int length = Math.max(0, endOffset - offset);
            textEditor.selectAndReveal(offset, length);
            return RemoteCommandResult.ok("Диапазон показан", Map.of("offset", Integer.valueOf(offset), "length", Integer.valueOf(length))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }, RemoteCommandResult.error("workbench_unavailable", "Workbench недоступен")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public RemoteCommandResult getSelection() {
        return withWorkbenchResult(() -> {
            CodeApplicationService.SelectionInfo selection = CodeApplicationService.getInstance().getCurrentSelection();
            if (selection == null) {
                return RemoteCommandResult.error("no_selection", "Нет активного текстового выделения"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("filePath", safe(selection.getFileName())); //$NON-NLS-1$
            payload.put("offset", Integer.valueOf(selection.getOffset())); //$NON-NLS-1$
            payload.put("length", Integer.valueOf(selection.getLength())); //$NON-NLS-1$
            payload.put("selectedText", safe(selection.getSelectedText())); //$NON-NLS-1$
            return RemoteCommandResult.ok("Снимок выделения получен", payload); //$NON-NLS-1$
        }, RemoteCommandResult.error("workbench_unavailable", "Workbench недоступен")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public RemoteCommandResult replaceSelection(String text) {
        return withWorkbenchResult(() -> CodeApplicationService.getInstance().replaceSelection(defaultString(text))
                ? RemoteCommandResult.ok("Выделение заменено") //$NON-NLS-1$
                : RemoteCommandResult.error("replace_failed", "Не удалось заменить текущее выделение"), //$NON-NLS-1$ //$NON-NLS-2$
                RemoteCommandResult.error("workbench_unavailable", "Workbench недоступен")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public RemoteCommandResult insertAtCursor(String text) {
        return withWorkbenchResult(() -> CodeApplicationService.getInstance().insertAtCursor(defaultString(text))
                ? RemoteCommandResult.ok("Текст вставлен в позицию курсора") //$NON-NLS-1$
                : RemoteCommandResult.error("insert_failed", "Не удалось вставить текст в позицию курсора"), //$NON-NLS-1$ //$NON-NLS-2$
                RemoteCommandResult.error("workbench_unavailable", "Workbench недоступен")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public RemoteCommandResult applyGeneratedCode(String response, boolean replaceSelection) {
        return withWorkbenchResult(() -> CodeApplicationService.getInstance().applyFromResponse(defaultString(response), replaceSelection)
                ? RemoteCommandResult.ok("Сгенерированный код применен", Map.of("replaceSelection", Boolean.valueOf(replaceSelection))) //$NON-NLS-1$ //$NON-NLS-2$
                : RemoteCommandResult.error("apply_failed", "Не удалось применить сгенерированный код"), //$NON-NLS-1$ //$NON-NLS-2$
                RemoteCommandResult.error("workbench_unavailable", "Workbench недоступен")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public RemoteCommandResult showView(String viewId) {
        return withWorkbenchResult(() -> {
            IWorkbenchPage page = requirePage();
            page.showView(viewId);
            return RemoteCommandResult.ok("Представление открыто", Map.of("viewId", safe(viewId))); //$NON-NLS-1$ //$NON-NLS-2$
        }, RemoteCommandResult.error("workbench_unavailable", "Workbench недоступен")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public RemoteCommandResult activatePart(String partId) {
        return withWorkbenchResult(() -> {
            IWorkbenchPage page = requirePage();
            for (IViewReference reference : page.getViewReferences()) {
                if (partId.equals(reference.getId())) {
                    IWorkbenchPart part = reference.getPart(true);
                    if (part != null) {
                        page.activate(part);
                        return RemoteCommandResult.ok("Часть интерфейса активирована", Map.of("partId", safe(partId))); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
            for (IEditorReference reference : page.getEditorReferences()) {
                if (partId.equals(reference.getId())) {
                    IWorkbenchPart part = reference.getPart(true);
                    if (part != null) {
                        page.activate(part);
                        return RemoteCommandResult.ok("Часть интерфейса активирована", Map.of("partId", safe(partId))); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
            return RemoteCommandResult.error("part_not_found", "Часть workbench не найдена: " + safe(partId)); //$NON-NLS-1$ //$NON-NLS-2$
        }, RemoteCommandResult.error("workbench_unavailable", "Workbench недоступен")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public RemoteCommandResult executeCommand(String commandId, Map<String, Object> parameters) {
        return withWorkbenchResult(() -> {
            if (commandId == null || commandId.isBlank()) {
                return RemoteCommandResult.error("missing_command", "Нужно указать commandId"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (RemoteWorkbenchCommandPolicy.isDenied(commandId)) {
                return RemoteCommandResult.error("command_denied", "Команда заблокирована политикой удаленного доступа: " + commandId); //$NON-NLS-1$ //$NON-NLS-2$
            }
            IWorkbenchWindow window = requireWindow();
            ICommandService commandService = window.getService(ICommandService.class);
            IHandlerService handlerService = window.getService(IHandlerService.class);
            if (commandService == null || handlerService == null) {
                return RemoteCommandResult.error("command_service_unavailable", "Сервисы команд workbench недоступны"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Command command = commandService.getCommand(commandId);
            if (command == null || !command.isDefined()) {
                return RemoteCommandResult.error("invalid_command", "Неизвестная команда Eclipse: " + commandId); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Map<String, String> stringParams = new LinkedHashMap<>();
            if (parameters != null) {
                parameters.forEach((key, value) -> {
                    if (key != null && value != null) {
                        stringParams.put(key, String.valueOf(value));
                    }
                });
            }
            ParameterizedCommand parameterized = ParameterizedCommand.generateCommand(command, stringParams.isEmpty() ? null : stringParams);
            Object result = handlerService.executeCommand(parameterized, null);
            return RemoteCommandResult.ok("Команда выполнена", Map.of( //$NON-NLS-1$
                    "commandId", commandId, //$NON-NLS-1$
                    "result", result != null ? String.valueOf(result) : "")); //$NON-NLS-1$ //$NON-NLS-2$
        }, RemoteCommandResult.error("workbench_unavailable", "Workbench недоступен")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private <T> T withWorkbenchResult(WorkbenchCallable<T> callable, T fallback) {
        if (!PlatformUI.isWorkbenchRunning()) {
            return fallback;
        }
        AtomicReference<T> result = new AtomicReference<>(fallback);
        AtomicReference<Throwable> error = new AtomicReference<>();
        PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
            try {
                result.set(callable.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null) {
            Throwable failure = error.get();
            if (fallback instanceof RemoteCommandResult) {
                @SuppressWarnings("unchecked")
                T converted = (T) RemoteCommandResult.error("workbench_error", failure.getMessage() != null ? failure.getMessage() : "Действие workbench завершилось ошибкой"); //$NON-NLS-1$ //$NON-NLS-2$
                return converted;
            }
            return fallback;
        }
        return result.get();
    }

    private IWorkbenchWindow requireWindow() {
        IWorkbenchWindow window = activeWindow();
        if (window == null) {
            throw new IllegalStateException("Окно workbench недоступно"); //$NON-NLS-1$
        }
        return window;
    }

    private IWorkbenchPage requirePage() {
        IWorkbenchPage page = activePage(activeWindow());
        if (page == null) {
            throw new IllegalStateException("Страница workbench недоступна"); //$NON-NLS-1$
        }
        return page;
    }

    private IWorkbenchWindow activeWindow() {
        return PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getActiveWorkbenchWindow() : null;
    }

    private IWorkbenchPage activePage(IWorkbenchWindow window) {
        return window != null ? window.getActivePage() : null;
    }

    private String activePartId(IWorkbenchPage page) {
        IWorkbenchPart part = page != null ? page.getActivePart() : null;
        return part != null && part.getSite() != null ? safe(part.getSite().getId()) : ""; //$NON-NLS-1$
    }

    private String activePartTitle(IWorkbenchPage page) {
        IWorkbenchPart part = page != null ? page.getActivePart() : null;
        return part != null ? safe(part.getTitle()) : ""; //$NON-NLS-1$
    }

    private void populateSelectionLines(Map<String, Object> editor) {
        ITextEditor textEditor = activeTextEditor();
        if (textEditor == null) {
            return;
        }
        ITextSelection selection = currentTextSelection(textEditor);
        if (selection == null) {
            return;
        }
        editor.put("startLine", Integer.valueOf(selection.getStartLine() + 1)); //$NON-NLS-1$
        editor.put("endLine", Integer.valueOf(selection.getEndLine() + 1)); //$NON-NLS-1$
    }

    private ITextEditor activeTextEditor() {
        IWorkbenchPage page = activePage(activeWindow());
        return page != null ? adaptTextEditor(page.getActiveEditor()) : null;
    }

    private ITextEditor adaptTextEditor(IEditorPart editorPart) {
        if (editorPart instanceof ITextEditor textEditor) {
            return textEditor;
        }
        return editorPart != null ? editorPart.getAdapter(ITextEditor.class) : null;
    }

    private ITextSelection currentTextSelection(ITextEditor editor) {
        if (editor == null || editor.getSelectionProvider() == null) {
            return null;
        }
        var selection = editor.getSelectionProvider().getSelection();
        return selection instanceof ITextSelection textSelection ? textSelection : null;
    }

    private List<Map<String, Object>> collectOpenViews(IWorkbenchPage page) {
        if (page == null) {
            return List.of();
        }
        List<Map<String, Object>> views = new ArrayList<>();
        for (IViewReference reference : page.getViewReferences()) {
            LinkedHashMap<String, Object> view = new LinkedHashMap<>();
            view.put("id", safe(reference.getId())); //$NON-NLS-1$
            view.put("title", safe(reference.getPartName())); //$NON-NLS-1$
            views.add(view);
        }
        return views;
    }

    private List<Map<String, Object>> collectCommands(IWorkbenchWindow window) {
        if (window == null) {
            return List.of();
        }
        ICommandService commandService = window.getService(ICommandService.class);
        if (commandService == null) {
            return List.of();
        }
        List<Map<String, Object>> commands = new ArrayList<>();
        for (Command command : commandService.getDefinedCommands()) {
            if (command == null || !command.isDefined()) {
                continue;
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("id", safe(command.getId())); //$NON-NLS-1$
            item.put("name", commandName(command)); //$NON-NLS-1$
            item.put("description", commandDescription(command)); //$NON-NLS-1$
            item.put("denied", Boolean.valueOf(RemoteWorkbenchCommandPolicy.isDenied(command.getId()))); //$NON-NLS-1$
            commands.add(item);
            if (commands.size() >= COMMAND_LIMIT) {
                break;
            }
        }
        return commands;
    }

    private List<Map<String, Object>> collectDiagnostics() {
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        try {
            IMarker[] markers = ResourcesPlugin.getWorkspace().getRoot().findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            int count = 0;
            for (IMarker marker : markers) {
                if (count++ >= DIAGNOSTIC_LIMIT) {
                    break;
                }
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("path", safe(marker.getResource() != null ? marker.getResource().getFullPath().toString() : "")); //$NON-NLS-1$ //$NON-NLS-2$
                item.put("severity", Integer.valueOf(marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO))); //$NON-NLS-1$
                item.put("line", Integer.valueOf(marker.getAttribute(IMarker.LINE_NUMBER, -1))); //$NON-NLS-1$
                item.put("message", safe(marker.getAttribute(IMarker.MESSAGE, ""))); //$NON-NLS-1$ //$NON-NLS-2$
                diagnostics.add(item);
            }
        } catch (Exception e) {
            return List.of(Map.of("message", safe(e.getMessage()))); //$NON-NLS-1$
        }
        return diagnostics;
    }

    private IFile resolveWorkspaceFile(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmedPath = path.trim();
        IFile file = resolveWorkspacePath(trimmedPath);
        if (file.exists()) {
            return file;
        }
        IPath osPath = Path.fromOSString(trimmedPath);
        if (osPath.isAbsolute()) {
            IFile direct = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(osPath);
            if (direct != null && direct.exists()) {
                return direct;
            }
            IFile[] matches = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(osPath.toFile().toURI());
            if (matches != null && matches.length > 0) {
                return matches[0];
            }
            IFile projectMatch = resolveProjectLocationMatch(osPath);
            if (projectMatch != null && projectMatch.exists()) {
                return projectMatch;
            }
        }
        return null;
    }

    private IFile resolveWorkspacePath(String path) {
        IPath candidatePath = new Path(path);
        if (!candidatePath.isAbsolute()) {
            candidatePath = new Path("/" + path); //$NON-NLS-1$
        }
        IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(candidatePath);
        if (file.exists()) {
            return file;
        }
        if (candidatePath.segmentCount() < 2) {
            return file;
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(candidatePath.segment(0));
        if (project == null || !project.exists()) {
            return file;
        }
        IFile projectFile = project.getFile(candidatePath.removeFirstSegments(1).makeRelative());
        return projectFile != null ? projectFile : file;
    }

    private IFile resolveProjectLocationMatch(IPath osPath) {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project == null || !project.exists()) {
                continue;
            }
            IPath location = project.getLocation();
            if (location == null || !location.isPrefixOf(osPath)) {
                continue;
            }
            IPath relative = osPath.makeRelativeTo(location);
            if (relative == null || relative.segmentCount() == 0) {
                continue;
            }
            return project.getFile(relative);
        }
        return null;
    }

    private String workspaceRootPath() {
        IPath location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        return location != null ? location.toOSString() : ""; //$NON-NLS-1$
    }

    private String commandName(Command command) {
        if (command == null || !command.isDefined()) {
            return ""; //$NON-NLS-1$
        }
        try {
            return safe(command.getName());
        } catch (Exception e) {
            return ""; //$NON-NLS-1$
        }
    }

    private String commandDescription(Command command) {
        if (command == null || !command.isDefined()) {
            return ""; //$NON-NLS-1$
        }
        try {
            return safe(command.getDescription());
        } catch (Exception e) {
            return ""; //$NON-NLS-1$
        }
    }

    private String safe(String value) {
        return value != null ? value : ""; //$NON-NLS-1$
    }

    private String defaultString(String value) {
        return Objects.requireNonNullElse(value, ""); //$NON-NLS-1$
    }

    @FunctionalInterface
    private interface WorkbenchCallable<T> {
        T call() throws PartInitException, BadLocationException, Exception;
    }
}
