package com.codepilot1c.core.edt.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com.codepilot1c.core.edt.metadata.AddMetadataChildRequest;
import com.codepilot1c.core.edt.metadata.CreateMetadataRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataChildKind;
import com.codepilot1c.core.edt.metadata.MetadataKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataProjectReadinessChecker;

/**
 * Pre-validates metadata mutation requests and issues one-time validation tokens.
 */
public class MetadataRequestValidationService {

    private final EdtMetadataGateway gateway;
    private final MetadataProjectReadinessChecker readinessChecker;
    private final ValidationTokenStore tokenStore;

    public MetadataRequestValidationService() {
        this(new EdtMetadataGateway(), ValidationTokenStore.getInstance());
    }

    MetadataRequestValidationService(EdtMetadataGateway gateway, ValidationTokenStore tokenStore) {
        this.gateway = gateway;
        this.tokenStore = tokenStore;
        this.readinessChecker = new MetadataProjectReadinessChecker(gateway);
    }

    public boolean isEdtAvailable() {
        return gateway.isEdtAvailable();
    }

    public ValidationResult validateAndIssueToken(ValidationRequest request) {
        request.validate();
        ensureRuntimeReady(request.projectName());

        List<String> checks = new ArrayList<>();
        Map<String, Object> normalizedPayload = normalizePayload(request, checks);
        ValidationTokenStore.TokenIssue tokenIssue = tokenStore.issueToken(
                request.operation(), request.projectName(), normalizedPayload);

        return new ValidationResult(
                true,
                request.projectName(),
                request.operation().getToolName(),
                checks,
                tokenIssue.token(),
                tokenIssue.expiresAtEpochMs());
    }

    public void consumeToken(
            String token,
            ValidationOperation operation,
            String projectName,
            Map<String, Object> normalizedPayload
    ) {
        ensureRuntimeReady(projectName);
        tokenStore.consumeToken(token, operation, projectName, normalizedPayload);
    }

    public Map<String, Object> normalizeCreatePayload(
            String projectName,
            String kindValue,
            String name,
            String synonym,
            String comment,
            Map<String, Object> properties
    ) {
        MetadataKind kind = MetadataKind.fromString(kindValue);
        CreateMetadataRequest request = new CreateMetadataRequest(projectName, kind, name, synonym, comment, properties);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("kind", kind.name()); //$NON-NLS-1$
        payload.put("name", name); //$NON-NLS-1$
        if (synonym != null && !synonym.isBlank()) {
            payload.put("synonym", synonym); //$NON-NLS-1$
        }
        if (comment != null && !comment.isBlank()) {
            payload.put("comment", comment); //$NON-NLS-1$
        }
        if (properties != null && !properties.isEmpty()) {
            payload.put("properties", properties); //$NON-NLS-1$
        }
        return payload;
    }

    public Map<String, Object> normalizeAddChildPayload(
            String projectName,
            String parentFqn,
            String childKindValue,
            String name,
            String synonym,
            String comment,
            Map<String, Object> properties
    ) {
        MetadataChildKind kind = MetadataChildKind.fromString(childKindValue);
        AddMetadataChildRequest request = new AddMetadataChildRequest(
                projectName, parentFqn, kind, name, synonym, comment, properties);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("parent_fqn", parentFqn); //$NON-NLS-1$
        payload.put("child_kind", kind.name()); //$NON-NLS-1$
        payload.put("name", name); //$NON-NLS-1$
        if (synonym != null && !synonym.isBlank()) {
            payload.put("synonym", synonym); //$NON-NLS-1$
        }
        if (comment != null && !comment.isBlank()) {
            payload.put("comment", comment); //$NON-NLS-1$
        }
        if (properties != null && !properties.isEmpty()) {
            payload.put("properties", properties); //$NON-NLS-1$
        }
        return payload;
    }

    private Map<String, Object> normalizePayload(ValidationRequest request, List<String> checks) {
        return switch (request.operation()) {
            case CREATE_METADATA -> {
                Map<String, Object> payload = normalizeCreatePayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("kind")), //$NON-NLS-1$
                        asString(request.payload().get("name")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("synonym")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("comment")), //$NON-NLS-1$
                        asMap(request.payload().get("properties"))); //$NON-NLS-1$
                checks.add("Операция create_metadata валидирована по обязательным полям и имени."); //$NON-NLS-1$
                yield payload;
            }
            case ADD_METADATA_CHILD -> {
                Map<String, Object> payload = normalizeAddChildPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("parent_fqn")), //$NON-NLS-1$
                        asString(request.payload().get("child_kind")), //$NON-NLS-1$
                        asString(request.payload().get("name")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("synonym")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("comment")), //$NON-NLS-1$
                        asMap(request.payload().get("properties"))); //$NON-NLS-1$
                checks.add("Операция add_metadata_child валидирована по обязательным полям и имени."); //$NON-NLS-1$
                yield payload;
            }
        };
    }

    private void ensureRuntimeReady(String projectName) {
        if (!gateway.isEdtAvailable()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "EDT runtime services are unavailable", false); //$NON-NLS-1$
        }

        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Project not found: " + projectName, false); //$NON-NLS-1$
        }
        readinessChecker.ensureReady(project);
    }

    private String coalesceProject(String topLevelProject, Map<String, Object> payload) {
        String payloadProject = asOptionalString(payload.get("project")); //$NON-NLS-1$
        if (payloadProject != null && !payloadProject.equals(topLevelProject)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "payload.project must match project", false); //$NON-NLS-1$
        }
        return topLevelProject;
    }

    private String asString(Object value) {
        String str = value == null ? null : String.valueOf(value);
        if (str == null || str.isBlank()) {
            return null;
        }
        return str;
    }

    private String asOptionalString(Object value) {
        String str = asString(value);
        return str == null || str.isBlank() ? null : str;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
