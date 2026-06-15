package com.codepilot1c.core.edt.ql;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.swt.widgets.Display;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource;

import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtServiceGateway;

/**
 * Validates 1C:Enterprise query-language text against an EDT project.
 *
 * <p>The query text is parsed as a {@code qldcs} resource inside the project's
 * resource set obtained from the QlDcs language services, which lets table and
 * field names resolve against the configuration metadata. The {@code dcsMode}
 * flag is forwarded to the {@link QlDcsResource} so Data Composition System
 * syntax ({@code {...}} blocks and dataset fields) is accepted only on demand.</p>
 *
 * <p>Xtext resource access happens on the SWT UI thread, where the language
 * services and derived-state computation are safe to run.</p>
 */
public class QlValidationService {

    /** Lookup URI used to obtain the QlDcs language services from the registry. */
    private static final URI QLDCS_LANGUAGE_URI =
            URI.createURI("__codepilot_ql_lookup__.qldcs"); //$NON-NLS-1$

    private final EdtServiceGateway gateway;

    public QlValidationService() {
        this(new EdtServiceGateway());
    }

    public QlValidationService(EdtServiceGateway gateway) {
        this.gateway = gateway;
    }

    public QlValidationResult validate(QlValidationRequest request) {
        IProject project = gateway.resolveProject(request.getProjectName());
        if (project == null || !project.exists()) {
            throw new EdtAstException(EdtAstErrorCode.PROJECT_NOT_FOUND,
                    "Project not found: " + request.getProjectName(), false); //$NON-NLS-1$
        }
        if (!project.isOpen()) {
            throw new EdtAstException(EdtAstErrorCode.PROJECT_NOT_READY,
                    "Project is closed: " + request.getProjectName(), true); //$NON-NLS-1$
        }
        return onUiThread(() -> analyze(project, request.getQueryText(), request.isDcsMode()));
    }

    private QlValidationResult onUiThread(java.util.function.Supplier<QlValidationResult> body) {
        if (Display.getCurrent() != null) {
            return body.get();
        }
        AtomicReference<QlValidationResult> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            try {
                result.set(body.get());
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private QlValidationResult analyze(IProject project, String queryText, boolean dcsMode) {
        Resource resource = null;
        try {
            IResourceServiceProvider services = IResourceServiceProvider.Registry.INSTANCE
                    .getResourceServiceProvider(QLDCS_LANGUAGE_URI);
            if (services == null) {
                throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "QlDcs language support is unavailable (QL plugin not installed).", true); //$NON-NLS-1$
            }

            IResourceSetProvider resourceSetProvider = services.get(IResourceSetProvider.class);
            ResourceSet resourceSet = resourceSetProvider != null ? resourceSetProvider.get(project) : null;
            if (resourceSet == null) {
                throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "QlDcs resource set is unavailable for project " + project.getName(), true); //$NON-NLS-1$
            }

            resource = resourceSet.createResource(scratchUri(project));
            if (resource == null) {
                throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                        "No QlDcs resource factory is registered.", true); //$NON-NLS-1$
            }
            applyDcsMode(resource, dcsMode);

            try (InputStream input = new ByteArrayInputStream(queryText.getBytes(StandardCharsets.UTF_8))) {
                resource.load(input, null);
            }

            List<QlIssue> issues = new ArrayList<>();
            collect(resource.getErrors(), "ERROR", issues); //$NON-NLS-1$
            collect(resource.getWarnings(), "WARNING", issues); //$NON-NLS-1$
            collectSemantic(services, resource, issues);

            int errors = count(issues, "ERROR"); //$NON-NLS-1$
            int warnings = count(issues, "WARNING"); //$NON-NLS-1$
            int infos = count(issues, "INFO"); //$NON-NLS-1$
            return new QlValidationResult(errors == 0, dcsMode, errors, warnings, infos, issues);
        } catch (EdtAstException e) {
            throw e;
        } catch (IOException e) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to read query text: " + e.getMessage(), true, e); //$NON-NLS-1$
        } catch (RuntimeException e) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Query validation failed: " + e.getMessage(), true, e); //$NON-NLS-1$
        } finally {
            discard(resource);
        }
    }

    private URI scratchUri(IProject project) {
        // Throwaway, project-relative URI so the resource resolves within the project context.
        return URI.createPlatformResourceURI(
                "/" + project.getName() + "/codepilot_validate_" + System.nanoTime() + ".qldcs", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                true);
    }

    private void applyDcsMode(Resource resource, boolean dcsMode) {
        if (resource instanceof QlDcsResource qlDcsResource) {
            qlDcsResource.addOptions("DcsValidationModeOption", dcsMode); //$NON-NLS-1$
            qlDcsResource.setPreComputeAnnounceAlias(dcsMode);
        }
    }

    private void collectSemantic(IResourceServiceProvider services, Resource resource, List<QlIssue> issues) {
        IResourceValidator validator = services.get(IResourceValidator.class);
        if (validator == null) {
            return;
        }
        for (Issue issue : validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl)) {
            issues.add(new QlIssue(
                    severityOf(issue),
                    issue.getMessage(),
                    orMissing(issue.getLineNumber()),
                    orMissing(issue.getColumn()),
                    orMissing(issue.getOffset())));
        }
    }

    private void collect(List<Resource.Diagnostic> diagnostics, String severity, List<QlIssue> issues) {
        for (Resource.Diagnostic diagnostic : diagnostics) {
            issues.add(new QlIssue(severity, diagnostic.getMessage(),
                    diagnostic.getLine(), diagnostic.getColumn(), -1));
        }
    }

    private static int count(List<QlIssue> issues, String severity) {
        int n = 0;
        for (QlIssue issue : issues) {
            if (severity.equals(issue.severity())) {
                n++;
            }
        }
        return n;
    }

    private static int orMissing(Integer value) {
        return value != null ? value : -1;
    }

    private static String severityOf(Issue issue) {
        if (issue.getSeverity() == null) {
            return "WARNING"; //$NON-NLS-1$
        }
        switch (issue.getSeverity()) {
            case ERROR:
                return "ERROR"; //$NON-NLS-1$
            case INFO:
                return "INFO"; //$NON-NLS-1$
            default:
                return "WARNING"; //$NON-NLS-1$
        }
    }

    private static void discard(Resource resource) {
        if (resource == null) {
            return;
        }
        try {
            ResourceSet owner = resource.getResourceSet();
            resource.unload();
            if (owner != null) {
                owner.getResources().remove(resource);
            }
        } catch (RuntimeException ignored) {
            // best-effort cleanup of the throwaway validation resource
        }
    }
}
