package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.XtextSourceViewer;

/**
 * Content assist service.
 */
public class EdtContentAssistService {

    private final EdtServiceGateway gateway;
    private final ProjectReadinessChecker readinessChecker;
    private final UiThreadExecutor uiThreadExecutor = new UiThreadExecutor();

    public EdtContentAssistService(EdtServiceGateway gateway, ProjectReadinessChecker readinessChecker) {
        this.gateway = gateway;
        this.readinessChecker = readinessChecker;
    }

    public ContentAssistResult getContentAssist(ContentAssistRequest req) {
        req.validate();

        IProject project = gateway.resolveProject(req.getProjectName());
        readinessChecker.ensureReady(project);

        IFile file = gateway.resolveSourceFile(project, req.getFilePath());
        if (file == null || !file.exists()) {
            throw new EdtAstException(EdtAstErrorCode.FILE_NOT_FOUND,
                    "File not found: " + req.getFilePath(), false); //$NON-NLS-1$
        }

        return uiThreadExecutor.callSync(() -> executeUi(file, req));
    }

    private ContentAssistResult executeUi(IFile file, ContentAssistRequest req) {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) {
                throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "No active workbench window", false); //$NON-NLS-1$
            }
            IWorkbenchPage page = window.getActivePage();
            if (page == null) {
                throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "No active workbench page", false); //$NON-NLS-1$
            }

            IEditorPart editorPart = IDE.openEditor(page, file, true);
            if (editorPart == null) {
                throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Could not open editor", false); //$NON-NLS-1$
            }

            XtextEditor xtextEditor = editorPart.getAdapter(XtextEditor.class);
            if (xtextEditor == null) {
                throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "File is not Xtext editor", false); //$NON-NLS-1$
            }

            ISourceViewer sourceViewer = xtextEditor.getInternalSourceViewer();
            if (!(sourceViewer instanceof XtextSourceViewer xtextSourceViewer)) {
                throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Xtext source viewer unavailable", false); //$NON-NLS-1$
            }

            IDocument document = sourceViewer.getDocument();
            if (document == null) {
                throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Document unavailable", false); //$NON-NLS-1$
            }

            int offset = calculateOffset(document, req.getLine(), req.getColumn());
            xtextEditor.selectAndReveal(offset, 0);

            ContentAssistant assistant = (ContentAssistant) xtextSourceViewer.getContentAssistant();
            if (assistant == null) {
                throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Content assistant unavailable", false); //$NON-NLS-1$
            }

            String contentType = document.getContentType(offset);
            IContentAssistProcessor processor = assistant.getContentAssistProcessor(contentType);
            if (processor == null) {
                throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "Content assist processor unavailable", false); //$NON-NLS-1$
            }

            ICompletionProposal[] proposals = processor.computeCompletionProposals(sourceViewer, offset);
            if (proposals == null) {
                proposals = new ICompletionProposal[0];
            }

            List<ContentAssistResult.Item> filtered = new ArrayList<>();
            List<String> contains = parseContains(req.getContainsNormalized());

            for (ICompletionProposal proposal : proposals) {
                String label = proposal.getDisplayString();
                if (label == null || label.isBlank()) {
                    continue;
                }
                if (!matchesContains(label, contains)) {
                    continue;
                }

                String detail = null;
                if (req.isExtendedDocumentation() && proposal instanceof ICompletionProposalExtension5 ext5) {
                    Object info = ext5.getAdditionalProposalInfo(null);
                    if (info != null) {
                        detail = String.valueOf(info);
                    }
                }
                filtered.add(new ContentAssistResult.Item(label, "proposal", detail)); //$NON-NLS-1$
            }

            int total = filtered.size();
            int start = Math.min(req.getOffset(), total);
            int end = Math.min(start + req.getLimit(), total);
            List<ContentAssistResult.Item> pageItems = filtered.subList(start, end);

            return new ContentAssistResult("edt_xtext", total, end < total, pageItems); //$NON-NLS-1$
        } catch (EdtAstException e) {
            throw e;
        } catch (Exception e) {
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "Failed to compute content assist: " + e.getMessage(), false, e); //$NON-NLS-1$
        }
    }

    private int calculateOffset(IDocument document, int line, int column) {
        try {
            int lineOffset = document.getLineOffset(line - 1);
            int offset = lineOffset + column - 1;
            if (offset < 0 || offset > document.getLength()) {
                throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                        "Position outside document bounds", false); //$NON-NLS-1$
            }
            return offset;
        } catch (BadLocationException e) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_POSITION,
                    "Invalid line/column", false, e); //$NON-NLS-1$
        }
    }

    private List<String> parseContains(String contains) {
        if (contains == null || contains.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : contains.split(",")) { //$NON-NLS-1$
            String val = part.trim();
            if (!val.isEmpty()) {
                values.add(val.toLowerCase(Locale.ROOT));
            }
        }
        return values;
    }

    private boolean matchesContains(String label, List<String> contains) {
        if (contains.isEmpty()) {
            return true;
        }
        String lowered = label.toLowerCase(Locale.ROOT);
        for (String part : contains) {
            if (!lowered.contains(part)) {
                return false;
            }
        }
        return true;
    }
}
