package com.codepilot1c.core.tools.diagnostics;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.codepilot1c.core.edt.BmObjectHelper;
import com.codepilot1c.core.edt.extension.EdtExtensionService;
import com.codepilot1c.core.edt.extension.ExtensionAdoptObjectRequest;
import com.codepilot1c.core.edt.extension.ExtensionAdoptObjectResult;
import com.codepilot1c.core.edt.extension.ExtensionCreateProjectRequest;
import com.codepilot1c.core.edt.extension.ExtensionCreateProjectResult;
import com.codepilot1c.core.edt.extension.ExtensionListObjectsRequest;
import com.codepilot1c.core.edt.extension.ExtensionListProjectsRequest;
import com.codepilot1c.core.edt.extension.ExtensionObjectSummary;
import com.codepilot1c.core.edt.extension.ExtensionObjectsResult;
import com.codepilot1c.core.edt.extension.ExtensionProjectSummary;
import com.codepilot1c.core.edt.extension.ExtensionProjectsResult;
import com.codepilot1c.core.edt.extension.ExtensionSetPropertyStateRequest;
import com.codepilot1c.core.edt.extension.ExtensionSetPropertyStateResult;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * End-to-end smoke flow for EDT extension APIs.
 */
@ToolMeta(name = "edt_extension_smoke", category = "extension", tags = {"workspace", "edt"})
public class EdtExtensionSmokeTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "Имя базового EDT проекта, на котором проверяется extension runtime"},
                "extension_project": {"type": "string", "description": "Имя проекта расширения для smoke; если не указано, создается временный проект"},
                "source_object_fqn": {"type": "string", "description": "FQN объекта основной конфигурации для adopt; если не указан, smoke выберет подходящий top-level объект"},
                "property_name": {"type": "string", "description": "Свойство для extension_set_property_state; если не указано, smoke подберет подходящее writable property"},
                "state": {"type": "string", "description": "Состояние свойства при set_property_state; по умолчанию CHECKED"},
                "cleanup_created": {"type": "boolean", "description": "Удалить временно созданный проект расширения после smoke (default=true)"}
              },
              "required": ["project"]
            }
            """; //$NON-NLS-1$

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmss"); //$NON-NLS-1$

    private final EdtExtensionService extensionService;
    private final EdtMetadataGateway gateway;

    public EdtExtensionSmokeTool() {
        this(new EdtExtensionService(), new EdtMetadataGateway());
    }

    EdtExtensionSmokeTool(EdtExtensionService extensionService, EdtMetadataGateway gateway) {
        this.extensionService = extensionService;
        this.gateway = gateway;
    }

    @Override
    public String getDescription() {
        return "Прогоняет end-to-end smoke для EDT extension runtime: create, list, adopt, set_property_state и cleanup. Используй для проверки работоспособности extension API или после изменений в extension tooling. Не используй для обычной разработки расширения; для нее есть extension_manage."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            List<StepResult> steps = new ArrayList<>();
            String baseProjectName = asString(parameters.get("project")); //$NON-NLS-1$
            if (baseProjectName == null || baseProjectName.isBlank()) {
                return ToolResult.failure("[INVALID_ARGUMENT] project is required"); //$NON-NLS-1$
            }
            baseProjectName = baseProjectName.trim();
            String requestedExtensionProject = asOptionalString(parameters.get("extension_project")); //$NON-NLS-1$
            String sourceRef = asOptionalString(parameters.get("source_object_fqn")); //$NON-NLS-1$
            String explicitPropertyName = asOptionalString(parameters.get("property_name")); //$NON-NLS-1$
            String state = asOptionalString(parameters.get("state")); //$NON-NLS-1$
            boolean cleanupCreated = asBoolean(parameters.get("cleanup_created"), true); //$NON-NLS-1$

            String generatedName = "SmokeExt" + LocalDateTime.now().format(SUFFIX); //$NON-NLS-1$
            String extensionProjectName = requestedExtensionProject != null ? requestedExtensionProject : generatedName;
            boolean createdBySmoke = false;

            try {
                gateway.ensureExtensionRuntimeAvailable();
                steps.add(StepResult.ok("runtime_precheck", "Extension runtime is available")); //$NON-NLS-1$ //$NON-NLS-2$

                IProject baseProject = gateway.resolveProject(baseProjectName);
                if (baseProject == null || !baseProject.exists()) {
                    steps.add(StepResult.failed("base_project_exists", MetadataOperationCode.PROJECT_NOT_FOUND.name(), //$NON-NLS-1$
                            "Base project not found: " + baseProjectName)); //$NON-NLS-1$
                    return ToolResult.failure(renderReport(baseProjectName, extensionProjectName, steps));
                }
                steps.add(StepResult.ok("base_project_exists", baseProjectName)); //$NON-NLS-1$

                ExtensionProjectsResult before = extensionService.listProjects(new ExtensionListProjectsRequest(baseProjectName));
                boolean existsBefore = containsExtensionProject(before, extensionProjectName);
                if (existsBefore) {
                    steps.add(StepResult.ok("extension_exists_before", extensionProjectName)); //$NON-NLS-1$
                } else {
                    ExtensionCreateProjectResult created = extensionService.createProject(new ExtensionCreateProjectRequest(
                            baseProjectName,
                            extensionProjectName,
                            null,
                            null,
                            extensionProjectName,
                            null,
                            null));
                    extensionProjectName = created.extensionProject();
                    createdBySmoke = true;
                    steps.add(StepResult.ok("create_extension_project", extensionProjectName)); //$NON-NLS-1$
                }

                ExtensionProjectsResult projectsAfterCreate = extensionService
                        .listProjects(new ExtensionListProjectsRequest(baseProjectName));
                boolean listed = containsExtensionProject(projectsAfterCreate, extensionProjectName);
                if (!listed) {
                    steps.add(StepResult.failed("list_projects_after_create", "ASSERTION_FAILED", //$NON-NLS-1$ //$NON-NLS-2$
                            "Created extension project is absent in extension_list_projects")); //$NON-NLS-1$
                    return ToolResult.failure(renderReport(baseProjectName, extensionProjectName, steps));
                }
                steps.add(StepResult.ok("list_projects_after_create", "Extension is listed")); //$NON-NLS-1$ //$NON-NLS-2$

                MdObject sourceObject = resolveSourceObject(baseProject, sourceRef);
                if (sourceObject == null) {
                    steps.add(StepResult.failed("resolve_source_object", MetadataOperationCode.METADATA_NOT_FOUND.name(), //$NON-NLS-1$
                            sourceRef == null
                                    ? "No top-level metadata object found in base configuration" //$NON-NLS-1$
                                    : "Source object not found: " + sourceRef)); //$NON-NLS-1$
                    return ToolResult.failure(renderReport(baseProjectName, extensionProjectName, steps));
                }
                String sourceFqn = safeFqn(sourceObject, sourceObject.eClass().getName(), safe(sourceObject.getName()));
                steps.add(StepResult.ok("resolve_source_object", sourceFqn)); //$NON-NLS-1$

                ExtensionAdoptObjectResult adoptResult = extensionService.adoptObject(new ExtensionAdoptObjectRequest(
                        baseProjectName,
                        extensionProjectName,
                        sourceFqn,
                        Boolean.TRUE));
                steps.add(StepResult.ok("adopt_object", adoptResult.adoptedObjectFqn())); //$NON-NLS-1$

                String propertyName = choosePropertyName(sourceObject, explicitPropertyName);
                if (propertyName == null) {
                    steps.add(StepResult.failed("resolve_property_name", MetadataOperationCode.EXTENSION_PROPERTY_STATE_INVALID.name(), //$NON-NLS-1$
                            "Could not resolve writable property for set_property_state")); //$NON-NLS-1$
                    return ToolResult.failure(renderReport(baseProjectName, extensionProjectName, steps));
                }
                steps.add(StepResult.ok("resolve_property_name", propertyName)); //$NON-NLS-1$

                String stateValue = state == null ? "CHECKED" : state; //$NON-NLS-1$
                ExtensionSetPropertyStateResult setResult = extensionService
                        .setPropertyState(new ExtensionSetPropertyStateRequest(
                                extensionProjectName,
                                baseProjectName,
                                sourceFqn,
                                propertyName,
                                stateValue));
                steps.add(StepResult.ok("set_property_state", setResult.state())); //$NON-NLS-1$

                String kindFilter = sourceObject.eClass().getName();
                ExtensionObjectsResult objectsResult = extensionService.listObjects(new ExtensionListObjectsRequest(
                        extensionProjectName,
                        kindFilter,
                        safe(sourceObject.getName()),
                        Integer.valueOf(200),
                        Integer.valueOf(0)));
                boolean found = objectsResult.items().stream().map(ExtensionObjectSummary::fqn)
                        .anyMatch(fqn -> fqn != null && fqn.equalsIgnoreCase(adoptResult.adoptedObjectFqn()));
                if (!found) {
                    steps.add(StepResult.failed("list_objects_after_adopt", "ASSERTION_FAILED", //$NON-NLS-1$ //$NON-NLS-2$
                            "Adopted object is absent in extension_list_objects result")); //$NON-NLS-1$
                    return ToolResult.failure(renderReport(baseProjectName, extensionProjectName, steps));
                }
                steps.add(StepResult.ok("list_objects_after_adopt", "Adopted object is listed")); //$NON-NLS-1$ //$NON-NLS-2$

                return ToolResult.success(renderReport(baseProjectName, extensionProjectName, steps));
            } catch (MetadataOperationException e) {
                steps.add(StepResult.failed("smoke_error", e.getCode().name(), e.getMessage())); //$NON-NLS-1$
                return ToolResult.failure(renderReport(baseProjectName, extensionProjectName, steps));
            } catch (Exception e) {
                steps.add(StepResult.failed("smoke_error", "INTERNAL_ERROR", e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure(renderReport(baseProjectName, extensionProjectName, steps));
            } finally {
                cleanupCreatedExtension(extensionProjectName, createdBySmoke, cleanupCreated, steps);
            }
        });
    }

    private MdObject resolveSourceObject(IProject baseProject, String sourceRef) {
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(baseProject);
        if (configuration == null) {
            return null;
        }
        String normalizedRef = normalize(sourceRef);
        for (EReference reference : configuration.eClass().getEAllReferences()) {
            if (!reference.isContainment() || !reference.isMany()) {
                continue;
            }
            if (reference.isDerived() || reference.isTransient() || reference.isVolatile()) {
                continue;
            }
            Object value = configuration.eGet(reference);
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof MdObject object)) {
                    continue;
                }
                if (normalizedRef == null) {
                    return object;
                }
                String kind = object.eClass().getName();
                String name = safe(object.getName());
                String fqn = safeFqn(object, kind, name);
                String shortRef = kind + "." + name; //$NON-NLS-1$
                if (normalizedRef.equals(normalize(fqn)) || normalizedRef.equals(normalize(shortRef))) {
                    return object;
                }
            }
        }
        return null;
    }

    private String choosePropertyName(MdObject sourceObject, String explicitPropertyName) {
        if (explicitPropertyName != null && !explicitPropertyName.isBlank()) {
            return explicitPropertyName.trim();
        }
        List<String> preferred = List.of("synonym", "comment", "explanation", "tooltip"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (String candidate : preferred) {
            EStructuralFeature feature = sourceObject.eClass().getEStructuralFeature(candidate);
            if (isFeatureUsable(feature)) {
                return candidate;
            }
        }
        for (EStructuralFeature feature : sourceObject.eClass().getEAllStructuralFeatures()) {
            if (isFeatureUsable(feature)) {
                return feature.getName();
            }
        }
        return null;
    }

    private boolean isFeatureUsable(EStructuralFeature feature) {
        if (feature == null) {
            return false;
        }
        if (feature.isDerived() || feature.isTransient() || feature.isVolatile()) {
            return false;
        }
        return feature.isChangeable();
    }

    private void cleanupCreatedExtension(
            String extensionProjectName,
            boolean createdBySmoke,
            boolean cleanupCreated,
            List<StepResult> steps
    ) {
        if (!createdBySmoke) {
            return;
        }
        if (!cleanupCreated) {
            steps.add(StepResult.ok("cleanup_skipped", "cleanup_created=false")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        IProject createdProject = gateway.resolveProject(extensionProjectName);
        if (createdProject == null || !createdProject.exists()) {
            steps.add(StepResult.ok("cleanup_created_extension", "Project already absent")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        try {
            createdProject.delete(true, true, new NullProgressMonitor());
            steps.add(StepResult.ok("cleanup_created_extension", "Deleted " + extensionProjectName)); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (CoreException e) {
            steps.add(StepResult.failed("cleanup_created_extension", MetadataOperationCode.EDT_TRANSACTION_FAILED.name(), //$NON-NLS-1$
                    e.getMessage()));
        }
    }

    private boolean containsExtensionProject(ExtensionProjectsResult projects, String extensionProjectName) {
        if (projects == null || projects.items() == null || extensionProjectName == null) {
            return false;
        }
        for (ExtensionProjectSummary item : projects.items()) {
            if (item == null || item.extensionProject() == null) {
                continue;
            }
            if (item.extensionProject().equalsIgnoreCase(extensionProjectName)) {
                return true;
            }
        }
        return false;
    }

    private String renderReport(String baseProjectName, String extensionProjectName, List<StepResult> steps) {
        boolean failed = steps.stream().anyMatch(step -> !step.success);
        StringBuilder out = new StringBuilder();
        out.append("EDT Extension Smoke Report\n"); //$NON-NLS-1$
        out.append("project: ").append(baseProjectName).append('\n'); //$NON-NLS-1$
        out.append("extension_project: ").append(extensionProjectName).append('\n'); //$NON-NLS-1$
        out.append("result: ").append(failed ? "FAILED" : "SUCCESS").append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (StepResult step : steps) {
            out.append(step.success ? "[OK] " : "[FAIL] "); //$NON-NLS-1$ //$NON-NLS-2$
            out.append(step.name);
            if (step.code != null && !step.code.isBlank()) {
                out.append(" code=").append(step.code); //$NON-NLS-1$
            }
            if (step.message != null && !step.message.isBlank()) {
                out.append(" message=").append(step.message); //$NON-NLS-1$
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String safeFqn(Object object, String kind, String name) {
        if (object instanceof IBmObject bmObject) {
            String fqn = BmObjectHelper.safeTopFqn(bmObject);
            if (!fqn.isBlank()) {
                return fqn;
            }
        }
        return kind + "." + name; //$NON-NLS-1$
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String asOptionalString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue.booleanValue();
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    private static final class StepResult {
        private final boolean success;
        private final String name;
        private final String code;
        private final String message;

        private StepResult(boolean success, String name, String code, String message) {
            this.success = success;
            this.name = name;
            this.code = code;
            this.message = message;
        }

        private static StepResult ok(String name, String message) {
            return new StepResult(true, name, "", message); //$NON-NLS-1$
        }

        private static StepResult failed(String name, String code, String message) {
            return new StepResult(false, name, code, message);
        }
    }
}
