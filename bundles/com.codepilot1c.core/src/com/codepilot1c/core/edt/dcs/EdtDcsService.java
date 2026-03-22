package com.codepilot1c.core.edt.dcs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * DCS projections and mutations over EDT metadata model.
 */
public class EdtDcsService {

    private final EdtMetadataGateway gateway;

    public EdtDcsService() {
        this(new EdtMetadataGateway());
    }

    EdtDcsService(EdtMetadataGateway gateway) {
        this.gateway = gateway;
    }

    public DcsSummaryResult getSummary(DcsGetSummaryRequest request) {
        request.validate();
        gateway.ensureValidationRuntimeAvailable();

        MdObject owner = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution schemaResolution = resolveSchema(owner);
        DataCompositionSchema schema = schemaResolution.schema();
        int templateCount = countDcsTemplates(owner);

        return new DcsSummaryResult(
                request.normalizedProjectName(),
                request.normalizedOwnerFqn(),
                owner.eClass().getName(),
                schema != null,
                schemaResolution.source(),
                schema != null ? schema.getDataSets().size() : 0,
                schema != null ? schema.getParameters().size() : 0,
                schema != null ? schema.getCalculatedFields().size() : 0,
                schema != null ? schema.getSettingsVariants().size() : 0,
                templateCount);
    }

    public DcsListNodesResult listNodes(DcsListNodesRequest request) {
        request.validate();
        gateway.ensureValidationRuntimeAvailable();

        MdObject owner = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution schemaResolution = resolveSchema(owner);
        DataCompositionSchema schema = schemaResolution.schema();
        if (schema == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_SCHEMA_NOT_FOUND,
                    "DCS schema is not configured for owner: " + request.normalizedOwnerFqn(),
                    false); //$NON-NLS-1$
        }

        String nodeKind = request.normalizedNodeKind();
        String nameFilter = request.normalizedNameContains();
        List<DcsNodeItem> all = new ArrayList<>();
        if ("all".equals(nodeKind) || "dataset".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (DataSet dataSet : schema.getDataSets()) {
                if (dataSet == null) {
                    continue;
                }
                String name = safe(dataSet.getName());
                String details = dataSet.eClass().getName();
                if (dataSet instanceof DataCompositionSchemaDataSetQuery queryDataSet) {
                    details = details + " query=" + compact(queryDataSet.getQuery(), 120); //$NON-NLS-1$
                }
                all.add(new DcsNodeItem("dataset", name, details)); //$NON-NLS-1$
            }
        }
        if ("all".equals(nodeKind) || "parameter".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (DataCompositionSchemaParameter parameter : schema.getParameters()) {
                if (parameter == null) {
                    continue;
                }
                String name = safe(parameter.getName());
                String details = "expression=" + compact(parameter.getExpression(), 100); //$NON-NLS-1$
                all.add(new DcsNodeItem("parameter", name, details)); //$NON-NLS-1$
            }
        }
        if ("all".equals(nodeKind) || "calculated".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (DataCompositionSchemaCalculatedField field : schema.getCalculatedFields()) {
                if (field == null) {
                    continue;
                }
                String name = safe(field.getDataPath());
                String details = "expression=" + compact(field.getExpression(), 100); //$NON-NLS-1$
                all.add(new DcsNodeItem("calculated", name, details)); //$NON-NLS-1$
            }
        }
        if ("all".equals(nodeKind) || "variant".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (SettingsVariant variant : schema.getSettingsVariants()) {
                if (variant == null) {
                    continue;
                }
                String name = safe(variant.getName());
                String details = variant.getSettings() != null ? "has_settings=true" : "has_settings=false"; //$NON-NLS-1$ //$NON-NLS-2$
                all.add(new DcsNodeItem("variant", name, details)); //$NON-NLS-1$
            }
        }

        if (nameFilter != null) {
            all = all.stream()
                    .filter(item -> normalize(item.name()).contains(nameFilter))
                    .toList();
        }
        all.sort(Comparator
                .comparing(DcsNodeItem::nodeKind, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DcsNodeItem::name, String.CASE_INSENSITIVE_ORDER));

        int total = all.size();
        int offset = request.effectiveOffset();
        int limit = request.effectiveLimit();
        int start = Math.min(offset, total);
        int end = Math.min(start + limit, total);
        List<DcsNodeItem> page = start >= end ? List.of() : new ArrayList<>(all.subList(start, end));

        return new DcsListNodesResult(
                request.normalizedProjectName(),
                request.normalizedOwnerFqn(),
                nodeKind,
                total,
                page.size(),
                start,
                limit,
                end < total,
                page);
    }

    public DcsCreateMainSchemaResult createMainSchema(DcsCreateMainSchemaRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        IProject project = resolveProject(request.normalizedProjectName());
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        Holder<DcsCreateMainSchemaResult> holder = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    configuration,
                    request.normalizedOwnerFqn());
            OwnerTemplates templates = resolveOwnerTemplates(owner);
            if (templates == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.DCS_OWNER_KIND_UNSUPPORTED,
                        "Owner does not support DCS templates: " + owner.eClass().getName(),
                        false); //$NON-NLS-1$
            }

            SchemaResolution existing = resolveSchema(owner);
            if (existing.schema() != null && !request.shouldForceReplace()) {
                holder.value = new DcsCreateMainSchemaResult(
                        request.normalizedProjectName(),
                        request.normalizedOwnerFqn(),
                        owner.eClass().getName(),
                        findTemplateName(existing.schema(), templates.templates()),
                        false,
                        false,
                        false,
                        existing.source());
                return null;
            }

            DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
            Template template = MdClassFactory.eINSTANCE.createTemplate();
            template.setName(request.effectiveTemplateName());
            template.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
            template.setTemplate(schema);
            templates.templates().add(template);

            boolean mainBindingUpdated = false;
            if (owner instanceof Report report) {
                report.setMainDataCompositionSchema(template);
                mainBindingUpdated = true;
            } else if (owner instanceof ExternalReport report) {
                report.setMainDataCompositionSchema(template);
                mainBindingUpdated = true;
            }

            holder.value = new DcsCreateMainSchemaResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    owner.eClass().getName(),
                    safe(template.getName()),
                    true,
                    true,
                    mainBindingUpdated,
                    mainBindingUpdated ? "main" : "templates"); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        });

        if (holder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to create DCS schema",
                    false); //$NON-NLS-1$
        }
        return holder.value;
    }

    public DcsUpsertQueryDatasetResult upsertQueryDataset(DcsUpsertQueryDatasetRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        IProject project = resolveProject(request.normalizedProjectName());
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        Holder<DcsUpsertQueryDatasetResult> holder = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    configuration,
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(owner, request.normalizedOwnerFqn());

            DataCompositionSchemaDataSetQuery dataset = findQueryDataset(schema, request.normalizedDatasetName());
            boolean created = false;
            if (dataset == null) {
                dataset = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
                dataset.setName(request.normalizedDatasetName());
                schema.getDataSets().add(dataset);
                created = true;
            }
            if (request.normalizedQuery() != null) {
                dataset.setQuery(request.normalizedQuery());
            }
            if (request.normalizedDataSource() != null) {
                dataset.setDataSource(request.normalizedDataSource());
            }
            if (request.autoFillAvailableFields() != null) {
                dataset.setAutoFillAvailableFields(request.autoFillAvailableFields().booleanValue());
            }
            if (request.useQueryGroupIfPossible() != null) {
                dataset.setUseQueryGroupIfPossible(request.useQueryGroupIfPossible().booleanValue());
            }

            holder.value = new DcsUpsertQueryDatasetResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    safe(dataset.getName()),
                    created,
                    safe(dataset.getQuery()),
                    safe(dataset.getDataSource()),
                    dataset.isAutoFillAvailableFields(),
                    dataset.isUseQueryGroupIfPossible());
            return null;
        });

        if (holder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to upsert DCS query dataset",
                    false); //$NON-NLS-1$
        }
        return holder.value;
    }

    public DcsUpsertParameterResult upsertParameter(DcsUpsertParameterRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        IProject project = resolveProject(request.normalizedProjectName());
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        Holder<DcsUpsertParameterResult> holder = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    configuration,
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(owner, request.normalizedOwnerFqn());

            DataCompositionSchemaParameter parameter = findParameter(schema, request.normalizedParameterName());
            boolean created = false;
            if (parameter == null) {
                parameter = DcsFactory.eINSTANCE.createDataCompositionSchemaParameter();
                parameter.setName(request.normalizedParameterName());
                schema.getParameters().add(parameter);
                created = true;
            }
            if (request.normalizedExpression() != null) {
                parameter.setExpression(request.normalizedExpression());
            }
            if (request.availableAsField() != null) {
                parameter.setAvailableAsField(request.availableAsField().booleanValue());
            }
            if (request.valueListAllowed() != null) {
                parameter.setValueListAllowed(request.valueListAllowed().booleanValue());
            }
            if (request.denyIncompleteValues() != null) {
                parameter.setDenyIncompleteValues(request.denyIncompleteValues().booleanValue());
            }
            if (request.useRestriction() != null) {
                parameter.setUseRestriction(request.useRestriction().booleanValue());
            }

            holder.value = new DcsUpsertParameterResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    safe(parameter.getName()),
                    created,
                    safe(parameter.getExpression()),
                    parameter.isAvailableAsField(),
                    parameter.isValueListAllowed(),
                    parameter.isDenyIncompleteValues(),
                    parameter.isUseRestriction());
            return null;
        });

        if (holder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to upsert DCS parameter",
                    false); //$NON-NLS-1$
        }
        return holder.value;
    }

    public DcsUpsertCalculatedFieldResult upsertCalculatedField(DcsUpsertCalculatedFieldRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        IProject project = resolveProject(request.normalizedProjectName());
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        Holder<DcsUpsertCalculatedFieldResult> holder = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    configuration,
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(owner, request.normalizedOwnerFqn());

            DataCompositionSchemaCalculatedField field = findCalculatedField(schema, request.normalizedDataPath());
            boolean created = false;
            if (field == null) {
                field = DcsFactory.eINSTANCE.createDataCompositionSchemaCalculatedField();
                field.setDataPath(request.normalizedDataPath());
                schema.getCalculatedFields().add(field);
                created = true;
            }
            if (request.normalizedExpression() != null) {
                field.setExpression(request.normalizedExpression());
            }
            if (request.normalizedPresentationExpression() != null) {
                field.setPresentationExpression(request.normalizedPresentationExpression());
            }

            holder.value = new DcsUpsertCalculatedFieldResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    safe(field.getDataPath()),
                    created,
                    safe(field.getExpression()),
                    safe(field.getPresentationExpression()));
            return null;
        });

        if (holder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to upsert DCS calculated field",
                    false); //$NON-NLS-1$
        }
        return holder.value;
    }

    private IProject resolveProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Project not found: " + projectName,
                    false); //$NON-NLS-1$
        }
        return project;
    }

    private MdObject resolveOwnerInTransaction(
            IBmPlatformTransaction transaction,
            Configuration configuration,
            String ownerFqn
    ) {
        // First try to resolve within the transaction context (reliable for new objects)
        if (configuration != null) {
            try {
                Configuration txConfiguration = transaction.toTransactionObject(configuration);
                if (txConfiguration != null) {
                    MdObject txOwner = findInConfiguration(txConfiguration, ownerFqn);
                    if (txOwner != null) {
                        return txOwner;
                    }
                }
            } catch (RuntimeException e) {
                // Fall through to non-transaction resolution
            }
        }
        // Fallback: resolve outside transaction and map
        MdObject owner = resolveOwner(configuration, ownerFqn);
        MdObject txOwner = castMdObject(transaction.toTransactionObject(owner));
        if (txOwner == null) {
            txOwner = resolveOwnerByUri(transaction, owner);
        }
        if (txOwner == null && isExternalOwner(owner)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_OWNER_KIND_UNSUPPORTED,
                    "External owner is not attached to BM transaction context: "
                            + owner.eClass().getName()
                            + "." + safe(owner.getName())
                            + " bmObject=" + (owner instanceof IBmObject), //$NON-NLS-1$
                    false);
        }
        return txOwner != null ? txOwner : owner;
    }

    private MdObject resolveOwnerByUri(IBmPlatformTransaction transaction, MdObject owner) {
        if (transaction == null || owner == null) {
            return null;
        }
        try {
            EObject byUri = transaction.getObjectByUri(EcoreUtil.getURI(owner));
            return castMdObject(byUri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private MdObject castMdObject(EObject object) {
        return object instanceof MdObject mdObject ? mdObject : null;
    }

    private MdObject resolveOwner(String projectName, String ownerFqn) {
        IProject project = resolveProject(projectName);
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        return resolveOwner(project, configuration, ownerFqn);
    }

    private MdObject resolveOwner(Configuration configuration, String ownerFqn) {
        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Configuration is unavailable",
                    false); //$NON-NLS-1$
        }
        MdObject object = findInConfiguration(configuration, ownerFqn);
        if (object == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Owner object not found: " + ownerFqn,
                    false); //$NON-NLS-1$
        }
        return object;
    }

    private MdObject resolveOwner(IProject project, Configuration configuration, String ownerFqn) {
        IExternalObjectProject externalProject = asExternalProject(project);
        if (externalProject != null) {
            MdObject external = findInExternalProject(externalProject, ownerFqn);
            if (external != null) {
                return external;
            }
        }

        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Configuration is unavailable",
                    false); //$NON-NLS-1$
        }
        MdObject object = findInConfiguration(configuration, ownerFqn);
        if (object == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Owner object not found: " + ownerFqn,
                    false); //$NON-NLS-1$
        }
        return object;
    }

    private IExternalObjectProject asExternalProject(IProject project) {
        try {
            var v8Project = gateway.getV8ProjectManager().getProject(project);
            if (v8Project instanceof IExternalObjectProject externalProject) {
                return externalProject;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private MdObject findInExternalProject(IExternalObjectProject project, String ownerFqn) {
        String normalizedRef = normalize(ownerFqn);
        for (MdObject object : project.getExternalObjects(MdObject.class)) {
            if (object == null) {
                continue;
            }
            String shortRef = object.eClass().getName() + "." + safe(object.getName()); //$NON-NLS-1$
            if (normalize(shortRef).equals(normalizedRef) || normalize(object.getName()).equals(normalizedRef)) {
                return object;
            }
        }
        return null;
    }

    private MdObject findInConfiguration(Configuration configuration, String ownerFqn) {
        String[] parts = ownerFqn != null ? ownerFqn.split("\\.") : new String[0]; //$NON-NLS-1$
        if (parts.length < 2) {
            return null;
        }
        String type = parts[0].trim().toLowerCase(Locale.ROOT);
        String name = parts[1].trim();
        List<? extends MdObject> topLevel = switch (type) {
            case "report", "отчет", "отчёт" -> configuration.getReports(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "dataprocessor", "обработка" -> configuration.getDataProcessors(); //$NON-NLS-1$ //$NON-NLS-2$
            default -> Collections.emptyList();
        };
        for (MdObject object : topLevel) {
            if (name.equalsIgnoreCase(object.getName())) {
                return object;
            }
        }
        return null;
    }

    private SchemaResolution resolveSchema(MdObject owner) {
        if (owner instanceof Report report) {
            DataCompositionSchema schema = extractSchema(report.getMainDataCompositionSchema());
            if (schema != null) {
                return new SchemaResolution(schema, "main"); //$NON-NLS-1$
            }
            return new SchemaResolution(findInTemplates(report.getTemplates()), "templates"); //$NON-NLS-1$
        }
        if (owner instanceof ExternalReport report) {
            DataCompositionSchema schema = extractSchema(report.getMainDataCompositionSchema());
            if (schema != null) {
                return new SchemaResolution(schema, "main"); //$NON-NLS-1$
            }
            return new SchemaResolution(findInTemplates(report.getTemplates()), "templates"); //$NON-NLS-1$
        }
        if (owner instanceof DataProcessor dataProcessor) {
            return new SchemaResolution(findInTemplates(dataProcessor.getTemplates()), "templates"); //$NON-NLS-1$
        }
        if (owner instanceof ExternalDataProcessor dataProcessor) {
            return new SchemaResolution(findInTemplates(dataProcessor.getTemplates()), "templates"); //$NON-NLS-1$
        }
        return new SchemaResolution(null, "none"); //$NON-NLS-1$
    }

    private OwnerTemplates resolveOwnerTemplates(MdObject owner) {
        if (owner instanceof Report report) {
            return new OwnerTemplates(report.getTemplates());
        }
        if (owner instanceof ExternalReport report) {
            return new OwnerTemplates(report.getTemplates());
        }
        if (owner instanceof DataProcessor dataProcessor) {
            return new OwnerTemplates(dataProcessor.getTemplates());
        }
        if (owner instanceof ExternalDataProcessor dataProcessor) {
            return new OwnerTemplates(dataProcessor.getTemplates());
        }
        return null;
    }

    private int countDcsTemplates(MdObject owner) {
        OwnerTemplates templates = resolveOwnerTemplates(owner);
        return templates == null ? 0 : countInTemplates(templates.templates());
    }

    private int countInTemplates(List<? extends Template> templates) {
        int count = 0;
        for (Template template : templates) {
            if (extractSchema(template) != null) {
                count++;
            }
        }
        return count;
    }

    private DataCompositionSchema requireSchema(MdObject owner, String ownerFqn) {
        SchemaResolution resolution = resolveSchema(owner);
        if (resolution.schema() == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_SCHEMA_NOT_FOUND,
                    "DCS schema is not configured for owner: " + ownerFqn,
                    false); //$NON-NLS-1$
        }
        return resolution.schema();
    }

    private String findTemplateName(DataCompositionSchema schema, List<Template> templates) {
        for (Template template : templates) {
            if (template != null && template.getTemplate() == schema) {
                return safe(template.getName());
            }
        }
        return ""; //$NON-NLS-1$
    }

    private DataCompositionSchemaDataSetQuery findQueryDataset(DataCompositionSchema schema, String name) {
        String token = normalize(name);
        for (DataSet dataSet : schema.getDataSets()) {
            if (dataSet instanceof DataCompositionSchemaDataSetQuery query
                    && normalize(query.getName()).equals(token)) {
                return query;
            }
        }
        return null;
    }

    private DataCompositionSchemaParameter findParameter(DataCompositionSchema schema, String name) {
        String token = normalize(name);
        for (DataCompositionSchemaParameter parameter : schema.getParameters()) {
            if (parameter != null && normalize(parameter.getName()).equals(token)) {
                return parameter;
            }
        }
        return null;
    }

    private DataCompositionSchemaCalculatedField findCalculatedField(DataCompositionSchema schema, String dataPath) {
        String token = normalize(dataPath);
        for (DataCompositionSchemaCalculatedField field : schema.getCalculatedFields()) {
            if (field != null && normalize(field.getDataPath()).equals(token)) {
                return field;
            }
        }
        return null;
    }

    private DataCompositionSchema findInTemplates(List<? extends Template> templates) {
        for (Template template : templates) {
            DataCompositionSchema schema = extractSchema(template);
            if (schema != null) {
                return schema;
            }
        }
        return null;
    }

    private DataCompositionSchema extractSchema(BasicTemplate template) {
        if (template == null) {
            return null;
        }
        TemplateType templateType = template.getTemplateType();
        if (templateType != TemplateType.DATA_COMPOSITION_SCHEMA) {
            return null;
        }
        EObject templateObject = template.getTemplate();
        if (templateObject instanceof DataCompositionSchema schema) {
            return schema;
        }
        return null;
    }

    private <T> T executeWrite(IProject project, PlatformTransactionTask<T> task) {
        try {
            return gateway.getGlobalEditingContext().execute(
                    "CodePilot1C.DcsWrite", //$NON-NLS-1$
                    project,
                    this,
                    task::execute);
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "DCS transaction failed: " + e.getMessage(),
                    false,
                    e); //$NON-NLS-1$
        }
    }

    private String compact(String value, int max) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "..."; //$NON-NLS-1$
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    private String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isExternalOwner(MdObject owner) {
        return owner instanceof ExternalReport || owner instanceof ExternalDataProcessor;
    }

    private record SchemaResolution(DataCompositionSchema schema, String source) {
    }

    private record OwnerTemplates(List<Template> templates) {
    }

    @FunctionalInterface
    private interface PlatformTransactionTask<T> {
        T execute(IBmPlatformTransaction transaction);
    }

    private static final class Holder<T> {
        private T value;
    }
}
