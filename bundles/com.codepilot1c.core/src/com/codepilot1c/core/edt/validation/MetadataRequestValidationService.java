package com.codepilot1c.core.edt.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

import com.codepilot1c.core.edt.forms.CreateFormRequest;
import com.codepilot1c.core.edt.forms.FormRecipeMode;
import com.codepilot1c.core.edt.forms.FormRecipeRequest;
import com.codepilot1c.core.edt.forms.FormUsage;
import com.codepilot1c.core.edt.forms.UpdateFormModelRequest;
import com.codepilot1c.core.edt.external.ExternalCreateProcessingRequest;
import com.codepilot1c.core.edt.external.ExternalCreateReportRequest;
import com.codepilot1c.core.edt.dcs.DcsCreateMainSchemaRequest;
import com.codepilot1c.core.edt.dcs.DcsUpsertCalculatedFieldRequest;
import com.codepilot1c.core.edt.dcs.DcsUpsertParameterRequest;
import com.codepilot1c.core.edt.dcs.DcsUpsertQueryDatasetRequest;
import com.codepilot1c.core.edt.metadata.AddMetadataChildRequest;
import com.codepilot1c.core.edt.metadata.CommandGroupResolver;
import com.codepilot1c.core.edt.metadata.CreateMetadataRequest;
import com.codepilot1c.core.edt.metadata.DeleteMetadataRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.EnsureModuleArtifactRequest;
import com.codepilot1c.core.edt.metadata.MetadataChildKind;
import com.codepilot1c.core.edt.metadata.MetadataKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.RenderTemplateRequest;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataProjectReadinessChecker;
import com.codepilot1c.core.edt.metadata.MetadataNameValidator;
import com.codepilot1c.core.edt.metadata.ModuleArtifactKind;
import com.codepilot1c.core.edt.metadata.UpdateMetadataRequest;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Pre-validates metadata mutation requests and issues one-time validation tokens.
 */
public class MetadataRequestValidationService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(MetadataRequestValidationService.class);
    private static final String EXTENSION_BASE_PROJECT_MISMATCH =
            "For extension mutations, project is the base EDT project and must equal base_project; " //$NON-NLS-1$
                    + "extension_project is the extension project."; //$NON-NLS-1$
    private static final Set<String> FORBIDDEN_FORM_ATTRIBUTE_TYPE_PREFIXES = Set.of(
            "array", "map", "массив", "соответствие"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    private static final Set<String> TYPE_KEY_CANDIDATES = Set.of(
            "type", "types", "value", "name", "nameRu", "code", "codeRu",
            "catalog", "document", "enumeration", "enum", "fqn"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$
    private static final Set<String> SIMPLE_TYPE_TOKENS = Set.of(
            "string", "строка", //$NON-NLS-1$ //$NON-NLS-2$
            "number", "число", //$NON-NLS-1$ //$NON-NLS-2$
            "date", "дата", //$NON-NLS-1$ //$NON-NLS-2$
            "boolean", "булево", "bool", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "valuestorage", "хранилищезначения", //$NON-NLS-1$ //$NON-NLS-2$
            "uniqueidentifier", "уникальныйидентификатор", "uuid", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "binarydata", "двоичныеданные" //$NON-NLS-1$ //$NON-NLS-2$
    );

    private final EdtMetadataGateway gateway;
    private final MetadataProjectReadinessChecker readinessChecker;
    private final ValidationTokenStore tokenStore;
    private final CommandGroupResolver commandGroupResolver;

    public MetadataRequestValidationService() {
        this(new EdtMetadataGateway(), ValidationTokenStore.getInstance(), new CommandGroupResolver());
    }

    MetadataRequestValidationService(EdtMetadataGateway gateway, ValidationTokenStore tokenStore) {
        this(gateway, tokenStore, new CommandGroupResolver());
    }

    MetadataRequestValidationService(EdtMetadataGateway gateway, ValidationTokenStore tokenStore,
            CommandGroupResolver commandGroupResolver) {
        this.gateway = gateway == null ? new EdtMetadataGateway() : gateway;
        this.tokenStore = tokenStore == null ? ValidationTokenStore.getInstance() : tokenStore;
        this.readinessChecker = new MetadataProjectReadinessChecker(this.gateway);
        this.commandGroupResolver = commandGroupResolver == null ? new CommandGroupResolver() : commandGroupResolver;
    }

    public boolean isEdtAvailable() {
        return gateway.isEdtAvailable();
    }

    public ValidationResult validateAndIssueToken(ValidationRequest request) {
        String opId = LogSanitizer.newId("validate-md"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        LOG.info("[%s] validateAndIssueToken START operation=%s project=%s", // $NON-NLS-1$
                opId, request.operation(), request.projectName());
        LOG.debug("[%s] Raw validation payload: %s", opId, // $NON-NLS-1$
                LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(request.payload())), 4000));
        request.validate();
        ensureRuntimeReady(request.projectName());

        List<String> checks = new ArrayList<>();
        Map<String, Object> normalizedPayload = normalizePayload(request, checks);
        LOG.debug("[%s] Normalized validation payload: %s", opId, // $NON-NLS-1$
                LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(normalizedPayload)), 4000));
        ValidationTokenStore.TokenIssue tokenIssue = tokenStore.issueToken(
                request.operation(), request.projectName(), normalizedPayload);
        LOG.info("[%s] Token issued in %s, expiresAt=%d", opId, // $NON-NLS-1$
                LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                tokenIssue.expiresAtEpochMs());

        return new ValidationResult(
                true,
                request.projectName(),
                request.operation().getToolName(),
                checks,
                normalizedPayload,
                tokenIssue.token(),
                tokenIssue.expiresAtEpochMs());
    }

    public Map<String, Object> consumeToken(
            String token,
            ValidationOperation operation,
            String projectName
    ) {
        String opId = LogSanitizer.newId("consume-md"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        LOG.info("[%s] consumeToken START operation=%s project=%s token=%s", // $NON-NLS-1$
                opId, operation, projectName, LogSanitizer.truncate(token, 80));
        ensureRuntimeReady(projectName);
        Map<String, Object> validatedPayload = tokenStore.consumeToken(token, operation, projectName);
        LOG.debug("[%s] consumeToken validated payload: %s", opId, // $NON-NLS-1$
                LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(validatedPayload)), 4000));
        LOG.info("[%s] consumeToken SUCCESS in %s", opId, // $NON-NLS-1$
                LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt));
        return validatedPayload;
    }

    public Map<String, Object> normalizeCreatePayload(
            String projectName,
            String kindValue,
            String name,
            String synonym,
            String comment,
            Map<String, Object> properties
    ) {
        return normalizeCreatePayload(projectName, kindValue, name, synonym, comment, properties, null);
    }

    public Map<String, Object> normalizeCreatePayload(
            String projectName,
            String kindValue,
            String name,
            String synonym,
            String comment,
            Map<String, Object> properties,
            Boolean allowAutoPrefix
    ) {
        MetadataKind kind = MetadataKind.fromString(kindValue);
        CreateMetadataRequest request = new CreateMetadataRequest(projectName, kind, name, synonym, comment, properties);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("kind", kind.name()); //$NON-NLS-1$
        payload.put("name", name); //$NON-NLS-1$
        payload.put("requestedName", name); //$NON-NLS-1$
        payload.put("requestedFqn", kind.getFqnPrefix() + "." + name); //$NON-NLS-1$ //$NON-NLS-2$
        EffectiveName effective = resolveEffectiveName(projectName, name);
        boolean autoPrefixed = !safeEquals(name, effective.name());
        Boolean propertyFlag = asOptionalBoolean(getMapValueIgnoreCase(properties, "allow_auto_prefix")); //$NON-NLS-1$
        Boolean effectiveAllowAutoPrefix = allowAutoPrefix != null ? allowAutoPrefix : propertyFlag;
        if (autoPrefixed && Boolean.FALSE.equals(effectiveAllowAutoPrefix)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Extension project would auto-prefix name '" + name + "' to '" + effective.name() //$NON-NLS-1$ //$NON-NLS-2$
                            + "'; pass allow_auto_prefix=true or use the effective name explicitly", //$NON-NLS-1$
                    false);
        }
        payload.put("effectiveName", effective.name()); //$NON-NLS-1$
        payload.put("effectiveFqn", kind.getFqnPrefix() + "." + effective.name()); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("autoPrefixed", Boolean.valueOf(autoPrefixed)); //$NON-NLS-1$
        if (synonym != null && !synonym.isBlank()) {
            payload.put("synonym", synonym); //$NON-NLS-1$
        }
        if (comment != null && !comment.isBlank()) {
            payload.put("comment", comment); //$NON-NLS-1$
        }
        if (properties != null && !properties.isEmpty()) {
            validateTypeDescriptionValue(getMapValueIgnoreCase(properties, "type"), "properties.type"); //$NON-NLS-1$ //$NON-NLS-2$
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
            validateTypeDescriptionValue(getMapValueIgnoreCase(properties, "type"), "properties.type"); //$NON-NLS-1$ //$NON-NLS-2$
            payload.put("properties", properties); //$NON-NLS-1$
        }
        return payload;
    }

    public Map<String, Object> normalizeCreateFormPayload(
            String projectName,
            String ownerFqn,
            String name,
            String usageValue,
            Boolean managed,
            Boolean setAsDefault,
            String synonym,
            String comment,
            Long waitMs
    ) {
        FormUsage usage = FormUsage.fromOptionalString(usageValue);
        CreateFormRequest request = new CreateFormRequest(
                projectName,
                ownerFqn,
                name,
                usage,
                managed,
                setAsDefault,
                synonym,
                comment,
                waitMs);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("owner_fqn", ownerFqn); //$NON-NLS-1$
        payload.put("name", name); //$NON-NLS-1$
        if (usage != null) {
            payload.put("usage", usage.name()); //$NON-NLS-1$
        }
        if (managed != null) {
            payload.put("managed", managed); //$NON-NLS-1$
        }
        if (setAsDefault != null) {
            payload.put("set_as_default", setAsDefault); //$NON-NLS-1$
        }
        if (synonym != null && !synonym.isBlank()) {
            payload.put("synonym", synonym); //$NON-NLS-1$
        }
        if (comment != null && !comment.isBlank()) {
            payload.put("comment", comment); //$NON-NLS-1$
        }
        if (waitMs != null) {
            payload.put("wait_ms", waitMs); //$NON-NLS-1$
        }
        return payload;
    }

    public Map<String, Object> normalizeExtensionCreateProjectPayload(
            String projectName,
            String extensionProject,
            String baseProject,
            String projectPath,
            String version,
            String configurationName,
            String purpose,
            String compatibilityMode
    ) {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "project name is required", false); //$NON-NLS-1$
        }
        String effectiveBaseProject = baseProject == null || baseProject.isBlank() ? projectName : baseProject.trim();
        if (!projectName.equals(effectiveBaseProject)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    EXTENSION_BASE_PROJECT_MISMATCH, false);
        }

        com.codepilot1c.core.edt.extension.ExtensionCreateProjectRequest request =
                new com.codepilot1c.core.edt.extension.ExtensionCreateProjectRequest(
                        effectiveBaseProject,
                        extensionProject,
                        projectPath,
                        version,
                        configurationName,
                        purpose,
                        compatibilityMode);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("base_project", request.normalizedBaseProjectName()); //$NON-NLS-1$
        payload.put("extension_project", request.normalizedExtensionProjectName()); //$NON-NLS-1$
        if (projectPath != null && !projectPath.isBlank()) {
            payload.put("project_path", projectPath.trim()); //$NON-NLS-1$
        }
        if (version != null && !version.isBlank()) {
            payload.put("version", version.trim()); //$NON-NLS-1$
        }
        if (configurationName != null && !configurationName.isBlank()) {
            payload.put("configuration_name", configurationName.trim()); //$NON-NLS-1$
        }
        payload.put("purpose", request.effectivePurpose().name()); //$NON-NLS-1$
        if (request.effectiveCompatibilityMode() != null) {
            payload.put("compatibility_mode", request.effectiveCompatibilityMode().name()); //$NON-NLS-1$
        }
        return payload;
    }

    public Map<String, Object> normalizeExternalCreateReportPayload(
            String projectName,
            String externalProject,
            String name,
            String projectPath,
            String version,
            String synonym,
            String comment
    ) {
        ExternalCreateReportRequest request = new ExternalCreateReportRequest(
                projectName,
                externalProject,
                name,
                projectPath,
                version,
                synonym,
                comment);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", request.normalizedProjectName()); //$NON-NLS-1$
        payload.put("external_project", request.normalizedExternalProjectName()); //$NON-NLS-1$
        payload.put("name", request.normalizedObjectName()); //$NON-NLS-1$
        if (projectPath != null && !projectPath.isBlank()) {
            payload.put("project_path", projectPath.trim()); //$NON-NLS-1$
        }
        if (version != null && !version.isBlank()) {
            payload.put("version", version.trim()); //$NON-NLS-1$
        }
        if (request.normalizedSynonym() != null) {
            payload.put("synonym", request.normalizedSynonym()); //$NON-NLS-1$
        }
        if (request.normalizedComment() != null) {
            payload.put("comment", request.normalizedComment()); //$NON-NLS-1$
        }
        return payload;
    }

    public Map<String, Object> normalizeExternalCreateProcessingPayload(
            String projectName,
            String externalProject,
            String name,
            String projectPath,
            String version,
            String synonym,
            String comment
    ) {
        ExternalCreateProcessingRequest request = new ExternalCreateProcessingRequest(
                projectName,
                externalProject,
                name,
                projectPath,
                version,
                synonym,
                comment);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", request.normalizedProjectName()); //$NON-NLS-1$
        payload.put("external_project", request.normalizedExternalProjectName()); //$NON-NLS-1$
        payload.put("name", request.normalizedObjectName()); //$NON-NLS-1$
        if (projectPath != null && !projectPath.isBlank()) {
            payload.put("project_path", projectPath.trim()); //$NON-NLS-1$
        }
        if (version != null && !version.isBlank()) {
            payload.put("version", version.trim()); //$NON-NLS-1$
        }
        if (request.normalizedSynonym() != null) {
            payload.put("synonym", request.normalizedSynonym()); //$NON-NLS-1$
        }
        if (request.normalizedComment() != null) {
            payload.put("comment", request.normalizedComment()); //$NON-NLS-1$
        }
        return payload;
    }

    public Map<String, Object> normalizeExtensionAdoptPayload(
            String projectName,
            String extensionProject,
            String baseProject,
            String sourceObjectFqn,
            Boolean updateIfExists
    ) {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "project name is required", false); //$NON-NLS-1$
        }
        String effectiveBaseProject = baseProject == null || baseProject.isBlank() ? projectName : baseProject.trim();
        if (!projectName.equals(effectiveBaseProject)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    EXTENSION_BASE_PROJECT_MISMATCH, false);
        }
        com.codepilot1c.core.edt.extension.ExtensionAdoptObjectRequest request =
                new com.codepilot1c.core.edt.extension.ExtensionAdoptObjectRequest(
                        effectiveBaseProject,
                        extensionProject,
                        sourceObjectFqn,
                        updateIfExists);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("base_project", effectiveBaseProject); //$NON-NLS-1$
        payload.put("extension_project", request.normalizedExtensionProjectName()); //$NON-NLS-1$
        payload.put("source_object_fqn", request.normalizedSourceObjectFqn()); //$NON-NLS-1$
        payload.put("update_if_exists", Boolean.valueOf(request.shouldUpdateIfExists())); //$NON-NLS-1$
        return payload;
    }

    public Map<String, Object> normalizeExtensionSetPropertyStatePayload(
            String projectName,
            String extensionProject,
            String baseProject,
            String sourceObjectFqn,
            String propertyName,
            String state
    ) {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "project name is required", false); //$NON-NLS-1$
        }
        String effectiveBaseProject = baseProject == null || baseProject.isBlank() ? projectName : baseProject.trim();
        if (!projectName.equals(effectiveBaseProject)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    EXTENSION_BASE_PROJECT_MISMATCH, false);
        }

        com.codepilot1c.core.edt.extension.ExtensionSetPropertyStateRequest request =
                new com.codepilot1c.core.edt.extension.ExtensionSetPropertyStateRequest(
                        extensionProject,
                        effectiveBaseProject,
                        sourceObjectFqn,
                        propertyName,
                        state);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("base_project", effectiveBaseProject); //$NON-NLS-1$
        payload.put("extension_project", request.normalizedExtensionProjectName()); //$NON-NLS-1$
        payload.put("source_object_fqn", request.normalizedSourceObjectFqn()); //$NON-NLS-1$
        payload.put("property_name", request.normalizedPropertyName()); //$NON-NLS-1$
        payload.put("state", request.parseState().name()); //$NON-NLS-1$
        return payload;
    }

    public Map<String, Object> normalizeDcsCreateMainSchemaPayload(
            String projectName,
            String ownerFqn,
            String templateName,
            Boolean forceReplace
    ) {
        DcsCreateMainSchemaRequest request = new DcsCreateMainSchemaRequest(
                projectName,
                ownerFqn,
                templateName,
                forceReplace);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", request.normalizedProjectName()); //$NON-NLS-1$
        payload.put("owner_fqn", request.normalizedOwnerFqn()); //$NON-NLS-1$
        payload.put("template_name", request.effectiveTemplateName()); //$NON-NLS-1$
        payload.put("force_replace", Boolean.valueOf(request.shouldForceReplace())); //$NON-NLS-1$
        return payload;
    }

    public Map<String, Object> normalizeDcsUpsertQueryDatasetPayload(
            String projectName,
            String ownerFqn,
            String datasetName,
            String query,
            String dataSource,
            Boolean autoFillAvailableFields,
            Boolean useQueryGroupIfPossible
    ) {
        DcsUpsertQueryDatasetRequest request = new DcsUpsertQueryDatasetRequest(
                projectName,
                ownerFqn,
                datasetName,
                query,
                dataSource,
                autoFillAvailableFields,
                useQueryGroupIfPossible);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", request.normalizedProjectName()); //$NON-NLS-1$
        payload.put("owner_fqn", request.normalizedOwnerFqn()); //$NON-NLS-1$
        payload.put("dataset_name", request.normalizedDatasetName()); //$NON-NLS-1$
        if (request.normalizedQuery() != null) {
            payload.put("query", request.normalizedQuery()); //$NON-NLS-1$
        }
        if (request.normalizedDataSource() != null) {
            payload.put("data_source", request.normalizedDataSource()); //$NON-NLS-1$
        }
        if (request.autoFillAvailableFields() != null) {
            payload.put("auto_fill_available_fields", request.autoFillAvailableFields()); //$NON-NLS-1$
        }
        if (request.useQueryGroupIfPossible() != null) {
            payload.put("use_query_group_if_possible", request.useQueryGroupIfPossible()); //$NON-NLS-1$
        }
        return payload;
    }

    public Map<String, Object> normalizeDcsUpsertParameterPayload(
            String projectName,
            String ownerFqn,
            String parameterName,
            String expression,
            Boolean availableAsField,
            Boolean valueListAllowed,
            Boolean denyIncompleteValues,
            Boolean useRestriction
    ) {
        DcsUpsertParameterRequest request = new DcsUpsertParameterRequest(
                projectName,
                ownerFqn,
                parameterName,
                expression,
                availableAsField,
                valueListAllowed,
                denyIncompleteValues,
                useRestriction);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", request.normalizedProjectName()); //$NON-NLS-1$
        payload.put("owner_fqn", request.normalizedOwnerFqn()); //$NON-NLS-1$
        payload.put("parameter_name", request.normalizedParameterName()); //$NON-NLS-1$
        if (request.normalizedExpression() != null) {
            payload.put("expression", request.normalizedExpression()); //$NON-NLS-1$
        }
        if (request.availableAsField() != null) {
            payload.put("available_as_field", request.availableAsField()); //$NON-NLS-1$
        }
        if (request.valueListAllowed() != null) {
            payload.put("value_list_allowed", request.valueListAllowed()); //$NON-NLS-1$
        }
        if (request.denyIncompleteValues() != null) {
            payload.put("deny_incomplete_values", request.denyIncompleteValues()); //$NON-NLS-1$
        }
        if (request.useRestriction() != null) {
            payload.put("use_restriction", request.useRestriction()); //$NON-NLS-1$
        }
        return payload;
    }

    public Map<String, Object> normalizeDcsUpsertCalculatedFieldPayload(
            String projectName,
            String ownerFqn,
            String dataPath,
            String expression,
            String presentationExpression
    ) {
        DcsUpsertCalculatedFieldRequest request = new DcsUpsertCalculatedFieldRequest(
                projectName,
                ownerFqn,
                dataPath,
                expression,
                presentationExpression);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", request.normalizedProjectName()); //$NON-NLS-1$
        payload.put("owner_fqn", request.normalizedOwnerFqn()); //$NON-NLS-1$
        payload.put("data_path", request.normalizedDataPath()); //$NON-NLS-1$
        if (request.normalizedExpression() != null) {
            payload.put("expression", request.normalizedExpression()); //$NON-NLS-1$
        }
        if (request.normalizedPresentationExpression() != null) {
            payload.put("presentation_expression", request.normalizedPresentationExpression()); //$NON-NLS-1$
        }
        return payload;
    }

    public Map<String, Object> normalizeUpdatePayload(
            String projectName,
            String targetFqn,
            Map<String, Object> changes
    ) {
        UpdateMetadataRequest request = new UpdateMetadataRequest(projectName, targetFqn, changes);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("target_fqn", targetFqn); //$NON-NLS-1$
        if (changes != null && !changes.isEmpty()) {
            Map<String, Object> normalizedChanges = normalizeEventSubscriptionSourceChildOps(changes, targetFqn);
            validateUpdateTypeDescriptionValues(normalizedChanges);
            payload.put("changes", normalizeStandardCommandGroupChanges(normalizedChanges)); //$NON-NLS-1$
        }
        return payload;
    }

    private Map<String, Object> normalizeEventSubscriptionSourceChildOps(
            Map<String, Object> changes,
            String targetFqn
    ) {
        Map<String, Object> normalized = new LinkedHashMap<>(changes);
        List<Map<String, Object>> childOps = asListOfMaps(normalized.get("children_ops")); //$NON-NLS-1$
        if (childOps.isEmpty()) {
            return normalized;
        }

        List<Map<String, Object>> remaining = new ArrayList<>();
        Object sourceValue = null;
        for (Map<String, Object> op : childOps) {
            if (isEventSubscriptionSourceTypeDescriptionOp(targetFqn, op)) {
                if (sourceValue != null) {
                    throw new MetadataOperationException(
                            MetadataOperationCode.INVALID_METADATA_CHANGE,
                            "EventSubscription source is specified more than once in children_ops", false); //$NON-NLS-1$
                }
                sourceValue = extractEventSubscriptionSourceValue(op);
                continue;
            }

            String childFqn = asOptionalString(getMapValueIgnoreCase(op, "child_fqn", "childFqn")); //$NON-NLS-1$ //$NON-NLS-2$
            if (childFqn == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "children_ops item must contain child_fqn", false); //$NON-NLS-1$
            }
            remaining.add(new LinkedHashMap<>(op));
        }

        if (sourceValue != null) {
            Map<String, Object> set = new LinkedHashMap<>(asMap(normalized.get("set"))); //$NON-NLS-1$
            if (hasMapKeyIgnoreCase(set, "source")) { //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "EventSubscription source is specified both in set.source and children_ops", false); //$NON-NLS-1$
            }
            set.put("source", sourceValue); //$NON-NLS-1$
            normalized.put("set", set); //$NON-NLS-1$
        }
        if (remaining.isEmpty()) {
            normalized.remove("children_ops"); //$NON-NLS-1$
        } else {
            normalized.put("children_ops", remaining); //$NON-NLS-1$
        }
        return normalized;
    }

    private boolean isEventSubscriptionSourceTypeDescriptionOp(String targetFqn, Map<String, Object> op) {
        if (!isEventSubscriptionFqn(targetFqn) || op == null || op.isEmpty()) {
            return false;
        }
        String rawOp = asOptionalString(getMapValueIgnoreCase(op, "op")); //$NON-NLS-1$
        if (rawOp != null) {
            String normalizedOp = normalizeActionToken(rawOp);
            if (!normalizedOp.equals("upsert") && !normalizedOp.equals("set") //$NON-NLS-1$ //$NON-NLS-2$
                    && !normalizedOp.equals("update") && !normalizedOp.equals("replace")) { //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }
        String kind = asOptionalString(getMapValueIgnoreCase(op, "kind", "child_kind", "childKind")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (kind != null && !kind.equalsIgnoreCase("TypeDescription")) { //$NON-NLS-1$
            return false;
        }
        String name = asOptionalString(getMapValueIgnoreCase(op, "name")); //$NON-NLS-1$
        if ("source".equalsIgnoreCase(name)) { //$NON-NLS-1$
            return true;
        }
        String childFqn = asOptionalString(getMapValueIgnoreCase(op, "child_fqn", "childFqn")); //$NON-NLS-1$ //$NON-NLS-2$
        return isEventSubscriptionSourceChildFqn(targetFqn, childFqn);
    }

    private boolean isEventSubscriptionFqn(String targetFqn) {
        return targetFqn != null && targetFqn.regionMatches(true, 0,
                "EventSubscription.", 0, "EventSubscription.".length()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private boolean isEventSubscriptionSourceChildFqn(String targetFqn, String childFqn) {
        return targetFqn != null && childFqn != null
                && childFqn.equalsIgnoreCase(targetFqn + ".source"); //$NON-NLS-1$
    }

    private Object extractEventSubscriptionSourceValue(Map<String, Object> op) {
        Object setObj = getMapValueIgnoreCase(op, "set"); //$NON-NLS-1$
        if (setObj instanceof Map<?, ?> setMap) {
            return new LinkedHashMap<>(asMap(setMap));
        }

        Object types = getMapValueIgnoreCase(op, "types"); //$NON-NLS-1$
        if (types != null) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("types", types); //$NON-NLS-1$
            return source;
        }

        Object type = getMapValueIgnoreCase(op, "type"); //$NON-NLS-1$
        if (type != null) {
            return type;
        }

        throw new MetadataOperationException(
                MetadataOperationCode.INVALID_PROPERTY_VALUE,
                "Invalid TypeDescription for children_ops.source: map must include set.types or type", false); //$NON-NLS-1$
    }

    private Map<String, Object> normalizeStandardCommandGroupChanges(Map<String, Object> changes) {
        Map<String, Object> normalized = new LinkedHashMap<>(changes);
        Object rawSet = normalized.get("set"); //$NON-NLS-1$
        if (rawSet instanceof Map<?, ?> setMap) {
            Map<String, Object> set = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : setMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if ("group".equalsIgnoreCase(key) && value instanceof String group) { //$NON-NLS-1$
                    value = normalizeStandardCommandGroup(group);
                }
                set.put(key, value);
            }
            normalized.put("set", set); //$NON-NLS-1$
        }
        return normalized;
    }

    private void validateUpdateTypeDescriptionValues(Map<String, Object> changes) {
        Object rawSet = changes.get("set"); //$NON-NLS-1$
        if (!(rawSet instanceof Map<?, ?> setMap)) {
            return;
        }
        Map<String, Object> set = asMap(setMap);
        validateTypeDescriptionValue(getMapValueIgnoreCase(set, "type"), "changes.set.type"); //$NON-NLS-1$ //$NON-NLS-2$
        validateTypeDescriptionValue(getMapValueIgnoreCase(set, "commandParameterType"), //$NON-NLS-1$
                "changes.set.commandParameterType"); //$NON-NLS-1$
        validateTypeDescriptionValue(getMapValueIgnoreCase(set, "source"), "changes.set.source"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String normalizeStandardCommandGroup(String rawValue) {
        return commandGroupResolver.normalizeForValidation(rawValue);
    }

    private void validateTypeDescriptionValue(Object typeValue, String fieldName) {
        if (typeValue == null) {
            return;
        }
        if (typeValue instanceof List<?> list) {
            if (list.isEmpty()) {
                throw invalidType(fieldName, typeValue, "must contain at least one type"); //$NON-NLS-1$
            }
            for (Object item : list) {
                validateTypeDescriptionValue(item, fieldName);
            }
            return;
        }
        if (typeValue instanceof Map<?, ?> map) {
            Map<String, Object> typed = asMap(map);
            Object nested = getMapValueIgnoreCase(typed, "types"); //$NON-NLS-1$
            if (nested == null) {
                nested = getMapValueIgnoreCase(typed, "type"); //$NON-NLS-1$
            }
            if (nested == null) {
                nested = pickFirstTypeToken(typed);
            }
            if (nested == null) {
                throw invalidType(fieldName, typeValue, "map must include type or types"); //$NON-NLS-1$
            }
            validateTypeDescriptionValue(nested, fieldName);
            return;
        }
        String raw = String.valueOf(typeValue).trim();
        if (raw.isBlank()) {
            throw invalidType(fieldName, typeValue, "must be non-empty"); //$NON-NLS-1$
        }
        if (!isValidTypeLookupToken(raw)) {
            throw invalidType(fieldName, typeValue,
                    "unknown type; use a scalar platform type or a qualified metadata type such as CatalogRef.Name"); //$NON-NLS-1$
        }
    }

    private MetadataOperationException invalidType(String fieldName, Object value, String reason) {
        return new MetadataOperationException(
                MetadataOperationCode.INVALID_PROPERTY_VALUE,
                "Invalid TypeDescription for " + fieldName + ": " + value + " (" + reason + ")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                false);
    }

    private boolean isValidTypeLookupToken(String raw) {
        String value = raw.trim();
        int open = value.indexOf('(');
        if (open > 0) {
            value = value.substring(0, open).trim();
        }
        if (value.contains(".")) { //$NON-NLS-1$
            String[] parts = value.split("\\."); //$NON-NLS-1$
            return parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank();
        }
        return SIMPLE_TYPE_TOKENS.contains(normalizeTypeToken(value));
    }

    public Map<String, Object> normalizeEnsureModuleArtifactPayload(
            String projectName,
            String objectFqn,
            String moduleKindValue,
            Boolean createIfMissing,
            String initialContent
    ) {
        ModuleArtifactTarget target = normalizeModuleArtifactTarget(objectFqn, moduleKindValue);
        EnsureModuleArtifactRequest request = new EnsureModuleArtifactRequest(
                projectName,
                target.objectFqn(),
                target.moduleKind(),
                createIfMissing == null ? true : createIfMissing.booleanValue(),
                initialContent);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("object_fqn", target.objectFqn()); //$NON-NLS-1$
        payload.put("module_kind", target.moduleKind().name()); //$NON-NLS-1$
        payload.put("create_if_missing", Boolean.valueOf(request.createIfMissing())); //$NON-NLS-1$
        if (initialContent != null && !initialContent.isBlank()) {
            payload.put("initial_content", initialContent); //$NON-NLS-1$
        }
        return payload;
    }

    Map<String, Object> normalizeEnsureModuleArtifactPayload(
            String projectName,
            Map<String, Object> rawPayload
    ) {
        return normalizeEnsureModuleArtifactPayload(
                projectName,
                asString(firstValue(rawPayload, "object_fqn", "objectFqn")), //$NON-NLS-1$ //$NON-NLS-2$
                asOptionalString(firstValue(rawPayload, "module_kind", "moduleType", "moduleKind")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                asOptionalBoolean(firstValue(rawPayload, "create_if_missing", "createIfMissing")), //$NON-NLS-1$ //$NON-NLS-2$
                asOptionalString(firstValue(rawPayload, "initial_content", "initialContent"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public Map<String, Object> normalizeDeletePayload(
            String projectName,
            String targetFqn,
            boolean recursive
    ) {
        return normalizeDeletePayload(projectName, targetFqn, recursive, false);
    }

    public Map<String, Object> normalizeDeletePayload(
            String projectName,
            String targetFqn,
            boolean recursive,
            boolean force
    ) {
        DeleteMetadataRequest request = new DeleteMetadataRequest(projectName, targetFqn, recursive, force);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("target_fqn", targetFqn); //$NON-NLS-1$
        payload.put("recursive", Boolean.valueOf(recursive)); //$NON-NLS-1$
        payload.put("force", Boolean.valueOf(force)); //$NON-NLS-1$
        return payload;
    }

    public Map<String, Object> normalizeUpdateFormModelPayload(
            String projectName,
            String formFqn,
            List<Map<String, Object>> operations
    ) {
        UpdateFormModelRequest request = new UpdateFormModelRequest(projectName, formFqn, operations);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("form_fqn", formFqn); //$NON-NLS-1$
        payload.put("operations", operations == null ? List.of() : new ArrayList<>(operations)); //$NON-NLS-1$
        return payload;
    }

    public Map<String, Object> normalizeMutateRoleRightsPayload(
            String projectName,
            String role,
            List<Map<String, Object>> operations
    ) {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "project is required", false); //$NON-NLS-1$
        }
        if (role == null || role.isBlank()) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "role is required", false); //$NON-NLS-1$
        }
        if (operations == null || operations.isEmpty()) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "operations must contain at least one operation", false); //$NON-NLS-1$
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("role", role); //$NON-NLS-1$
        payload.put("operations", new ArrayList<>(operations)); //$NON-NLS-1$
        return payload;
    }

    public Map<String, Object> normalizeRenderTemplatePayload(
            String projectName,
            String templateFqn,
            List<Map<String, Object>> sections
    ) {
        RenderTemplateRequest request = new RenderTemplateRequest(projectName, templateFqn, sections);
        request.validate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        payload.put("template_fqn", templateFqn); //$NON-NLS-1$
        payload.put("sections", sections == null ? List.of() : new ArrayList<>(sections)); //$NON-NLS-1$
        return payload;
    }

    public Map<String, Object> normalizeApplyFormRecipePayload(
            String projectName,
            String mode,
            String formFqn,
            String ownerFqn,
            String name,
            String usageValue,
            Boolean managed,
            Boolean setAsDefault,
            String synonym,
            String comment,
            Long waitMs,
            List<Map<String, Object>> attributes,
            List<Map<String, Object>> layoutOperations
    ) {
        FormRecipeRequest request = new FormRecipeRequest(
                projectName,
                mode,
                formFqn,
                ownerFqn,
                name,
                usageValue,
                managed,
                setAsDefault,
                synonym,
                comment,
                waitMs,
                attributes,
                layoutOperations);
        request.validate();
        if (managed != null && !managed.booleanValue()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_FORM_USAGE,
                    "Only managed forms are supported in MVP", false); //$NON-NLS-1$
        }
        validateFormRecipeAttributes(attributes);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", projectName); //$NON-NLS-1$
        FormRecipeMode recipeMode = FormRecipeMode.fromOptionalString(mode);
        if (recipeMode != null) {
            payload.put("mode", recipeMode.name()); //$NON-NLS-1$
        }
        if (formFqn != null && !formFqn.isBlank()) {
            payload.put("form_fqn", formFqn); //$NON-NLS-1$
        }
        if (ownerFqn != null && !ownerFqn.isBlank()) {
            payload.put("owner_fqn", ownerFqn); //$NON-NLS-1$
        }
        if (name != null && !name.isBlank()) {
            payload.put("name", name); //$NON-NLS-1$
        }
        FormUsage usage = FormUsage.fromOptionalString(usageValue);
        if (usage != null) {
            payload.put("usage", usage.name()); //$NON-NLS-1$
        }
        if (managed != null) {
            payload.put("managed", managed); //$NON-NLS-1$
        }
        if (setAsDefault != null) {
            payload.put("set_as_default", setAsDefault); //$NON-NLS-1$
        }
        if (synonym != null && !synonym.isBlank()) {
            payload.put("synonym", synonym); //$NON-NLS-1$
        }
        if (comment != null && !comment.isBlank()) {
            payload.put("comment", comment); //$NON-NLS-1$
        }
        if (waitMs != null) {
            payload.put("wait_ms", waitMs); //$NON-NLS-1$
        }
        if (attributes != null && !attributes.isEmpty()) {
            payload.put("attributes", new ArrayList<>(attributes)); //$NON-NLS-1$
        }
        if (layoutOperations != null && !layoutOperations.isEmpty()) {
            payload.put("layout", new ArrayList<>(layoutOperations)); //$NON-NLS-1$
        }
        return payload;
    }

    private void validateFormRecipeAttributes(List<Map<String, Object>> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        for (Map<String, Object> descriptor : attributes) {
            if (descriptor == null || descriptor.isEmpty()) {
                continue;
            }
            String action = resolveFormAttributeAction(descriptor);
            Integer id = asOptionalInteger(
                    getMapValueIgnoreCase(descriptor, "id", "attribute_id", "attributeId"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "attribute.id"); //$NON-NLS-1$
            String name = asOptionalString(getMapValueIgnoreCase(descriptor, "name", "attribute_name", "attribute")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            if ("create".equals(action) || "upsert".equals(action)) { //$NON-NLS-1$ //$NON-NLS-2$
                if (name == null || name.isBlank()) {
                    throw new MetadataOperationException(
                            MetadataOperationCode.INVALID_METADATA_NAME,
                            "Form attribute name is required for action: " + action, false); //$NON-NLS-1$
                }
            } else if ("update".equals(action) || "remove".equals(action)) { //$NON-NLS-1$ //$NON-NLS-2$
                if ((name == null || name.isBlank()) && id == null) {
                    throw new MetadataOperationException(
                            MetadataOperationCode.INVALID_METADATA_NAME,
                            "Form attribute name or id is required for action: " + action, false); //$NON-NLS-1$
                }
            }

            if (name != null && !name.isBlank() && !MetadataNameValidator.isValidName(name)) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_NAME,
                        "Invalid form attribute name: " + name, false); //$NON-NLS-1$
            }

            Object typeValue = extractTypeValue(descriptor);
            if (typeValue != null) {
                validateFormAttributeTypeValue(typeValue);
            }
        }
    }

    private String resolveFormAttributeAction(Map<String, Object> descriptor) {
        Object raw = getMapValueIgnoreCase(descriptor, "action", "op", "mode"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Boolean remove = asOptionalBoolean(getMapValueIgnoreCase(descriptor, "remove")); //$NON-NLS-1$
        if (remove != null && remove.booleanValue()) {
            return "remove"; //$NON-NLS-1$
        }
        if (raw == null) {
            return "upsert"; //$NON-NLS-1$
        }
        String token = normalizeActionToken(String.valueOf(raw));
        return switch (token) {
            case "add", "create", "new" -> "create"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "update", "set", "patch", "modify" -> "update"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "upsert", "ensure", "apply", "merge" -> "upsert"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "remove", "delete", "drop" -> "remove"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Unsupported form attribute action: " + raw, false); //$NON-NLS-1$
        };
    }

    private Object extractTypeValue(Map<String, Object> descriptor) {
        Object direct = getMapValueIgnoreCase(descriptor, "type", "field_type", "fieldType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (direct != null) {
            return direct;
        }
        Map<String, Object> set = asMap(getMapValueIgnoreCase(descriptor, "set")); //$NON-NLS-1$
        Object setType = getMapValueIgnoreCase(set, "type", "field_type", "fieldType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (setType != null) {
            return setType;
        }
        Map<String, Object> props = asMap(getMapValueIgnoreCase(descriptor, "properties")); //$NON-NLS-1$
        return getMapValueIgnoreCase(props, "type", "field_type", "fieldType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private void validateFormAttributeTypeValue(Object typeValue) {
        if (typeValue instanceof List<?> list) {
            for (Object item : list) {
                validateFormAttributeTypeValue(item);
            }
            return;
        }
        if (typeValue instanceof Map<?, ?> map) {
            Map<String, Object> typed = asMap(map);
            if (typed.isEmpty()) {
                return;
            }
            Object innerType = getMapValueIgnoreCase(typed, "type"); //$NON-NLS-1$
            Object innerTypes = getMapValueIgnoreCase(typed, "types"); //$NON-NLS-1$
            if (innerType != null) {
                validateFormAttributeTypeValue(innerType);
                return;
            }
            if (innerTypes != null) {
                validateFormAttributeTypeValue(innerTypes);
                return;
            }
            Object namedType = pickFirstTypeToken(typed);
            if (namedType != null) {
                validateFormAttributeTypeValue(namedType);
                return;
            }
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Form attribute type map must include 'type' or 'types'", false); //$NON-NLS-1$
        }
        if (typeValue instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isBlank()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_PROPERTY_VALUE,
                        "Form attribute type must be non-empty", false); //$NON-NLS-1$
            }
            String normalized = normalizeTypeToken(trimmed);
            for (String forbidden : FORBIDDEN_FORM_ATTRIBUTE_TYPE_PREFIXES) {
                if (normalized.startsWith(forbidden)) {
                    throw new MetadataOperationException(
                            MetadataOperationCode.INVALID_PROPERTY_VALUE,
                            "Form attribute type is not supported: " + trimmed
                                    + ". Use FixedArray/FixedMap or a supported scalar type.", false); //$NON-NLS-1$
                }
            }
        }
    }

    private Object pickFirstTypeToken(Map<String, Object> map) {
        for (String key : TYPE_KEY_CANDIDATES) {
            Object value = getMapValueIgnoreCase(map, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String normalizeActionToken(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeTypeToken(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int cut = normalized.indexOf('(');
        if (cut > 0) {
            normalized = normalized.substring(0, cut);
        }
        cut = normalized.indexOf('.');
        if (cut > 0) {
            normalized = normalized.substring(0, cut);
        }
        return normalized;
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
                        asMap(request.payload().get("properties")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("allow_auto_prefix"))); //$NON-NLS-1$
                checks.add("Операция create_metadata валидирована по обязательным полям и имени."); //$NON-NLS-1$
                yield payload;
            }
            case CREATE_FORM -> {
                Map<String, Object> payload = normalizeCreateFormPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("owner_fqn")), //$NON-NLS-1$
                        asString(request.payload().get("name")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("usage")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("managed")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("set_as_default")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("synonym")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("comment")), //$NON-NLS-1$
                        asOptionalLong(request.payload().get("wait_ms"))); //$NON-NLS-1$
                checks.add("Операция create_form валидирована по обязательным полям и имени."); //$NON-NLS-1$
                yield payload;
            }
            case APPLY_FORM_RECIPE -> {
                Map<String, Object> payload = normalizeApplyFormRecipePayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asOptionalString(request.payload().get("mode")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("form_fqn")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("owner_fqn")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("name")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("usage")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("managed")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("set_as_default")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("synonym")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("comment")), //$NON-NLS-1$
                        asOptionalLong(request.payload().get("wait_ms")), //$NON-NLS-1$
                        asListOfMaps(request.payload().get("attributes")), //$NON-NLS-1$
                        asListOfMaps(request.payload().get("layout"))); //$NON-NLS-1$
                checks.add("Операция apply_form_recipe валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case EXTERNAL_CREATE_REPORT -> {
                Map<String, Object> payload = normalizeExternalCreateReportPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("external_project")), //$NON-NLS-1$
                        asString(request.payload().get("name")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("project_path")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("version")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("synonym")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("comment"))); //$NON-NLS-1$
                checks.add("Операция external_create_report валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case EXTERNAL_CREATE_PROCESSING -> {
                Map<String, Object> payload = normalizeExternalCreateProcessingPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("external_project")), //$NON-NLS-1$
                        asString(request.payload().get("name")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("project_path")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("version")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("synonym")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("comment"))); //$NON-NLS-1$
                checks.add("Операция external_create_processing валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case EXTENSION_CREATE_PROJECT -> {
                Map<String, Object> payload = normalizeExtensionCreateProjectPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("extension_project")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("base_project")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("project_path")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("version")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("configuration_name")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("purpose")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("compatibility_mode"))); //$NON-NLS-1$
                checks.add("Операция extension_create_project валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case EXTENSION_ADOPT_OBJECT -> {
                Map<String, Object> payload = normalizeExtensionAdoptPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("extension_project")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("base_project")), //$NON-NLS-1$
                        asString(request.payload().get("source_object_fqn")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("update_if_exists"))); //$NON-NLS-1$
                checks.add("Операция extension_adopt_object валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case EXTENSION_SET_PROPERTY_STATE -> {
                Map<String, Object> payload = normalizeExtensionSetPropertyStatePayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("extension_project")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("base_project")), //$NON-NLS-1$
                        asString(request.payload().get("source_object_fqn")), //$NON-NLS-1$
                        asString(request.payload().get("property_name")), //$NON-NLS-1$
                        asString(request.payload().get("state"))); //$NON-NLS-1$
                checks.add("Операция extension_set_property_state валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case DCS_CREATE_MAIN_SCHEMA -> {
                Map<String, Object> payload = normalizeDcsCreateMainSchemaPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("owner_fqn")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("template_name")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("force_replace"))); //$NON-NLS-1$
                checks.add("Операция dcs_create_main_schema валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case DCS_UPSERT_QUERY_DATASET -> {
                Map<String, Object> payload = normalizeDcsUpsertQueryDatasetPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("owner_fqn")), //$NON-NLS-1$
                        asString(request.payload().get("dataset_name")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("query")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("data_source")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("auto_fill_available_fields")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("use_query_group_if_possible"))); //$NON-NLS-1$
                checks.add("Операция dcs_upsert_query_dataset валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case DCS_UPSERT_PARAMETER -> {
                Map<String, Object> payload = normalizeDcsUpsertParameterPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("owner_fqn")), //$NON-NLS-1$
                        asString(request.payload().get("parameter_name")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("expression")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("available_as_field")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("value_list_allowed")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("deny_incomplete_values")), //$NON-NLS-1$
                        asOptionalBoolean(request.payload().get("use_restriction"))); //$NON-NLS-1$
                checks.add("Операция dcs_upsert_parameter валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case DCS_UPSERT_CALCULATED_FIELD -> {
                Map<String, Object> payload = normalizeDcsUpsertCalculatedFieldPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("owner_fqn")), //$NON-NLS-1$
                        asString(request.payload().get("data_path")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("expression")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("presentation_expression"))); //$NON-NLS-1$
                checks.add("Операция dcs_upsert_calculated_field валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case ADD_METADATA_CHILD -> {
                Map<String, Object> childProps = new java.util.LinkedHashMap<>(asMap(request.payload().get("properties"))); //$NON-NLS-1$
                // Merge template_type from top-level payload into properties if not already present
                Object templateTypeVal = request.payload().get("template_type"); //$NON-NLS-1$
                if (templateTypeVal != null && !childProps.containsKey("template_type")) { //$NON-NLS-1$
                    childProps.put("template_type", templateTypeVal); //$NON-NLS-1$
                }
                Map<String, Object> payload = normalizeAddChildPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("parent_fqn")), //$NON-NLS-1$
                        asString(request.payload().get("child_kind")), //$NON-NLS-1$
                        asString(request.payload().get("name")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("synonym")), //$NON-NLS-1$
                        asOptionalString(request.payload().get("comment")), //$NON-NLS-1$
                        childProps);
                checks.add("Операция add_metadata_child валидирована по обязательным полям и имени."); //$NON-NLS-1$
                yield payload;
            }
            case UPDATE_METADATA -> {
                Map<String, Object> payload = normalizeUpdatePayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("target_fqn")), //$NON-NLS-1$
                        asMap(request.payload().get("changes"))); //$NON-NLS-1$
                checks.add("Операция update_metadata валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case ENSURE_MODULE_ARTIFACT -> {
                Map<String, Object> payload = normalizeEnsureModuleArtifactPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        request.payload());
                checks.add("Операция ensure_module_artifact валидирована по проекту, FQN и типу модуля."); //$NON-NLS-1$
                yield payload;
            }
            case DELETE_METADATA -> {
                Object recursiveObj = request.payload().get("recursive"); //$NON-NLS-1$
                boolean recursive = recursiveObj instanceof Boolean b ? b.booleanValue() : Boolean.parseBoolean(String.valueOf(recursiveObj));
                Object forceObj = request.payload().get("force"); //$NON-NLS-1$
                boolean force = forceObj instanceof Boolean b ? b.booleanValue() : Boolean.parseBoolean(String.valueOf(forceObj));
                Map<String, Object> payload = normalizeDeletePayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("target_fqn")), //$NON-NLS-1$
                        recursive,
                        force);
                checks.add("Операция delete_metadata валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case MUTATE_FORM_MODEL -> {
                Map<String, Object> payload = normalizeUpdateFormModelPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("form_fqn")), //$NON-NLS-1$
                        asListOfMaps(request.payload().get("operations"))); //$NON-NLS-1$
                checks.add("Операция mutate_form_model валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case MUTATE_ROLE_RIGHTS -> {
                Map<String, Object> payload = normalizeMutateRoleRightsPayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("role")), //$NON-NLS-1$
                        asListOfMaps(request.payload().get("operations"))); //$NON-NLS-1$
                checks.add("Операция mutate_role_rights валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
            case RENDER_TEMPLATE -> {
                Map<String, Object> payload = normalizeRenderTemplatePayload(
                        coalesceProject(request.projectName(), request.payload()),
                        asString(request.payload().get("template_fqn")), //$NON-NLS-1$
                        asListOfMaps(request.payload().get("sections"))); //$NON-NLS-1$
                checks.add("Операция render_template валидирована по обязательным полям."); //$NON-NLS-1$
                yield payload;
            }
        };
    }

    private void ensureRuntimeReady(String projectName) {
        LOG.debug("ensureRuntimeReady(project=%s): checking EDT availability", projectName); //$NON-NLS-1$
        gateway.ensureValidationRuntimeAvailable();

        IProject project = gateway.resolveProject(projectName);
        LOG.debug("ensureRuntimeReady(project=%s): resolved project=%s", projectName, //$NON-NLS-1$
                project != null ? project.getName() : "null"); //$NON-NLS-1$
        if (project == null || !project.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Project not found: " + projectName, false); //$NON-NLS-1$
        }
        LOG.debug("ensureRuntimeReady(project=%s): checking derived-data readiness", projectName); //$NON-NLS-1$
        readinessChecker.ensureReady(project);
        LOG.debug("ensureRuntimeReady(project=%s): READY", projectName); //$NON-NLS-1$
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

    private Object firstValue(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            if (payload.containsKey(key)) {
                return payload.get(key);
            }
        }
        return null;
    }

    private ModuleArtifactTarget normalizeModuleArtifactTarget(String objectFqn, String moduleKindValue) {
        String normalizedObjectFqn = objectFqn == null ? null : objectFqn.trim().replace('/', '.'); //$NON-NLS-1$
        String effectiveModuleKind = moduleKindValue;
        if (normalizedObjectFqn != null) {
            ModuleSuffixMatch suffixMatch = ModuleSuffixMatch.match(normalizedObjectFqn);
            if (suffixMatch != null) {
                normalizedObjectFqn = suffixMatch.strip(normalizedObjectFqn);
                if (effectiveModuleKind == null || effectiveModuleKind.isBlank()) {
                    effectiveModuleKind = suffixMatch.moduleKindValue;
                }
            }
            if ((effectiveModuleKind == null || effectiveModuleKind.isBlank())
                    && normalizedObjectFqn.startsWith("CommonCommand.")) { //$NON-NLS-1$
                effectiveModuleKind = "command"; //$NON-NLS-1$
            }
        }
        return new ModuleArtifactTarget(
                normalizedObjectFqn,
                ModuleArtifactKind.fromString(effectiveModuleKind));
    }

    private record ModuleArtifactTarget(String objectFqn, ModuleArtifactKind moduleKind) {
    }

    private enum ModuleSuffixMatch {
        OBJECT(".ObjectModule", "object"), //$NON-NLS-1$ //$NON-NLS-2$
        MANAGER(".ManagerModule", "manager"), //$NON-NLS-1$ //$NON-NLS-2$
        COMMAND(".CommandModule", "command"), //$NON-NLS-1$ //$NON-NLS-2$
        MODULE(".FormModule", "module"), //$NON-NLS-1$ //$NON-NLS-2$
        GENERIC_MODULE(".Module", "module"); //$NON-NLS-1$ //$NON-NLS-2$

        private final String suffix;
        private final String moduleKindValue;

        ModuleSuffixMatch(String suffix, String moduleKindValue) {
            this.suffix = suffix;
            this.moduleKindValue = moduleKindValue;
        }

        static ModuleSuffixMatch match(String value) {
            for (ModuleSuffixMatch candidate : values()) {
                if (value.endsWith(candidate.suffix)) {
                    return candidate;
                }
            }
            return null;
        }

        String strip(String value) {
            return value.substring(0, value.length() - suffix.length());
        }
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

    private Integer asOptionalInteger(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return Integer.valueOf(number.intValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    fieldName + " must be numeric: " + value, false); //$NON-NLS-1$
        }
    }

    private Boolean asOptionalBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        if ("1".equals(text)) { //$NON-NLS-1$
            return Boolean.TRUE;
        }
        if ("0".equals(text)) { //$NON-NLS-1$
            return Boolean.FALSE;
        }
        return Boolean.valueOf(Boolean.parseBoolean(text));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private Long asOptionalLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return Long.valueOf(number.longValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "wait_ms must be numeric: " + value, false); //$NON-NLS-1$
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Object getMapValueIgnoreCase(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String entryKey) {
                for (String key : keys) {
                    if (entryKey.equalsIgnoreCase(key)) {
                        return entry.getValue();
                    }
                }
            }
        }
        return null;
    }

    private boolean hasMapKeyIgnoreCase(Map<?, ?> map, String key) {
        if (map == null || key == null || key.isBlank()) {
            return false;
        }
        if (map.containsKey(key)) {
            return true;
        }
        for (Object candidate : map.keySet()) {
            if (candidate instanceof String text && text.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private EffectiveName resolveEffectiveName(String projectName, String name) {
        if (name == null || name.isBlank()) {
            return new EffectiveName(name);
        }
        String prefix = resolveExtensionNamePrefix(projectName);
        if (prefix == null || prefix.isBlank() || name.startsWith(prefix)) {
            return new EffectiveName(name);
        }
        return new EffectiveName(prefix + name);
    }

    private String resolveExtensionNamePrefix(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            return null;
        }
        try {
            IProject project = gateway.resolveProject(projectName);
            if (project != null && project.exists()) {
                Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
                if (configuration != null && configuration.getNamePrefix() != null
                        && !configuration.getNamePrefix().isBlank()) {
                    return configuration.getNamePrefix();
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("Cannot resolve extension name prefix for %s: %s", projectName, e.getMessage()); //$NON-NLS-1$
        }
        return projectName.contains(".") ? "ар_" : null; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private record EffectiveName(String name) {
    }
}
