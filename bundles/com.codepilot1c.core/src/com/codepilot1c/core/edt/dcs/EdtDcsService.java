package com.codepilot1c.core.edt.dcs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmNamespace;
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
import com.codepilot1c.core.edt.metadata.MetadataProjectReadinessChecker;

/**
 * DCS projections and mutations over EDT metadata model.
 */
public class EdtDcsService {

    /**
     * Namespace of the DCS dialect the 1C:Enterprise platform accepts.
     *
     * <p>This is the dialect every schema produced by Designer or imported from
     * a configuration uses, and the only one the platform can read back when the
     * project is exported into an infobase.</p>
     */
    private static final String DCS_PLATFORM_NS = "http://v8.1c.ru/8.1/data-composition-system/schema"; //$NON-NLS-1$
    /**
     * Namespace of the EDT design-time dialect this service used to emit.
     *
     * <p>Kept for reading: schemas written by earlier builds are still on disk.
     * Never written for new schemas — the platform rejects the whole
     * configuration export with an XDTO exception when it meets one.</p>
     */
    private static final String DCS_EDT_DT_NS = "http://g5.1c.ru/v8/dt/data-composition-system/schema"; //$NON-NLS-1$
    private static final String XSI_NS = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;
    private static final String TEMPLATE_DCS_FILE = "Template.dcs"; //$NON-NLS-1$
    private static final String EMPTY_DCS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <DataCompositionSchema xmlns="http://v8.1c.ru/8.1/data-composition-system/schema" xmlns:dcscom="http://v8.1c.ru/8.1/data-composition-system/common" xmlns:dcscor="http://v8.1c.ru/8.1/data-composition-system/core" xmlns:dcsset="http://v8.1c.ru/8.1/data-composition-system/settings" xmlns:v8="http://v8.1c.ru/8.1/data/core" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"/>
            """; //$NON-NLS-1$
    /** Default data source name generated for a query data set that has none. */
    private static final String DEFAULT_DATA_SOURCE = "ИсточникДанных"; //$NON-NLS-1$

    /**
     * On-disk shape of a DCS template.
     *
     * <p>Two dialects exist and they differ in more than the namespace: the
     * platform spells collections in the singular and carries values in child
     * elements, while the EDT design-time form uses plural names and packs
     * everything into attributes. Reading has to accept both, because schemas
     * written by earlier builds are still on disk; writing new schemas must
     * always produce {@link #PLATFORM}.</p>
     */
    private enum DcsDialect {
        /** Platform dialect: {@code <dataSet><name>X</name></dataSet>}. */
        PLATFORM(DCS_PLATFORM_NS, "dataSet", "parameter", "calculatedField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "settingsVariant", "DataSetQuery", false), //$NON-NLS-1$ //$NON-NLS-2$
        /** EDT design-time dialect: {@code <dataSets name="X"/>}. */
        EDT_DT(DCS_EDT_DT_NS, "dataSets", "parameters", "calculatedFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "settingsVariants", "schema:DataCompositionSchemaDataSetQuery", true); //$NON-NLS-1$ //$NON-NLS-2$

        private final String namespaceUri;
        private final String dataSetElement;
        private final String parameterElement;
        private final String calculatedFieldElement;
        private final String settingsVariantElement;
        private final String queryDataSetType;
        private final boolean valuesInAttributes;

        DcsDialect(String namespaceUri, String dataSetElement, String parameterElement,
                String calculatedFieldElement, String settingsVariantElement,
                String queryDataSetType, boolean valuesInAttributes) {
            this.namespaceUri = namespaceUri;
            this.dataSetElement = dataSetElement;
            this.parameterElement = parameterElement;
            this.calculatedFieldElement = calculatedFieldElement;
            this.settingsVariantElement = settingsVariantElement;
            this.queryDataSetType = queryDataSetType;
            this.valuesInAttributes = valuesInAttributes;
        }

        /**
         * Detects the dialect of an already parsed schema.
         *
         * <p>Falls back to {@link #PLATFORM} for a namespace-less document: a
         * schema without a namespace is malformed either way, and guessing the
         * platform keeps a subsequent write on the dialect that can be exported.</p>
         */
        static DcsDialect of(Element root) {
            if (root == null) {
                return PLATFORM;
            }
            return DCS_EDT_DT_NS.equals(root.getNamespaceURI()) ? EDT_DT : PLATFORM;
        }

        /** Element name holding one node of the given kind. */
        String elementFor(DcsNodeKind kind) {
            return switch (kind) {
                case DATA_SET -> dataSetElement;
                case PARAMETER -> parameterElement;
                case CALCULATED_FIELD -> calculatedFieldElement;
                case SETTINGS_VARIANT -> settingsVariantElement;
            };
        }
    }

    /** Kinds of schema node this service reads and writes. */
    private enum DcsNodeKind {
        DATA_SET,
        PARAMETER,
        CALCULATED_FIELD,
        SETTINGS_VARIANT
    }

    /**
     * Order of top-level elements required by the platform DCS schema.
     *
     * <p>The platform validates the sequence, so a new element cannot simply be
     * appended to the end of the document: a data set added after a parameter
     * makes the whole file unreadable.</p>
     */
    private static final List<String> PLATFORM_ELEMENT_ORDER = List.of(
            "dataSource", "dataSet", "calculatedField", "totalField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "filterAvailableFields", "groupAvailableFields", "orderAvailableFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "parameter", "template", "nestedDataSet", "settingsVariant"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private final EdtMetadataGateway gateway;
    private final MetadataProjectReadinessChecker readinessChecker;

    public EdtDcsService() {
        this(new EdtMetadataGateway());
    }

    EdtDcsService(EdtMetadataGateway gateway) {
        this.gateway = gateway;
        this.readinessChecker = new MetadataProjectReadinessChecker(gateway);
    }

    public DcsSummaryResult getSummary(DcsGetSummaryRequest request) {
        request.validate();
        gateway.ensureValidationRuntimeAvailable();
        IProject project = resolveProject(request.normalizedProjectName());
        readinessChecker.ensureReady(project);

        MdObject owner = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution schemaResolution = resolveSchema(project, owner);
        DataCompositionSchema schema = schemaResolution.schema();
        int templateCount = countDcsTemplates(owner);

        return new DcsSummaryResult(
                request.normalizedProjectName(),
                request.normalizedOwnerFqn(),
                owner.eClass().getName(),
                schemaResolution.schemaPresent(),
                schemaResolution.source(),
                schemaResolution.dataSetsCount(),
                schemaResolution.parametersCount(),
                schemaResolution.calculatedFieldsCount(),
                schema != null ? schema.getSettingsVariants().size() : schemaResolution.settingsVariantsCount(),
                templateCount);
    }

    public DcsListNodesResult listNodes(DcsListNodesRequest request) {
        request.validate();
        gateway.ensureValidationRuntimeAvailable();
        IProject project = resolveProject(request.normalizedProjectName());
        readinessChecker.ensureReady(project);

        MdObject owner = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution schemaResolution = resolveSchema(project, owner);
        DataCompositionSchema schema = schemaResolution.schema();
        if (!schemaResolution.schemaPresent()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_SCHEMA_NOT_FOUND,
                    "DCS schema is not configured for owner: " + request.normalizedOwnerFqn(),
                    false); //$NON-NLS-1$
        }

        String nodeKind = request.normalizedNodeKind();
        String nameFilter = request.normalizedNameContains();
        List<DcsNodeItem> all = new ArrayList<>();
        if (schema == null && schemaResolution.externalSchema() != null) {
            all.addAll(schemaResolution.externalSchema().nodes(nodeKind));
            return pageNodes(request, nodeKind, nameFilter, all);
        }
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

        return pageNodes(request, nodeKind, nameFilter, all);
    }

    private DcsListNodesResult pageNodes(
            DcsListNodesRequest request,
            String nodeKind,
            String nameFilter,
            List<DcsNodeItem> all
    ) {
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
        readinessChecker.ensureReady(project);
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        MdObject ownerForPath = resolveOwner(project, configuration, request.normalizedOwnerFqn());
        SchemaResolution existingBefore = resolveSchema(project, ownerForPath);
        Holder<DcsCreateMainSchemaResult> holder = new Holder<>();
        if (existingBefore.schemaPresent() && !request.shouldForceReplace()) {
            OwnerTemplates existingTemplates = resolveOwnerTemplates(ownerForPath);
            holder.value = new DcsCreateMainSchemaResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    ownerForPath.eClass().getName(),
                    existingBefore.templateName() != null
                            ? existingBefore.templateName()
                            : firstDcsTemplateName(existingTemplates),
                    false,
                    false,
                    false,
                    existingBefore.source());
            return holder.value;
        }

        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    project,
                    configuration,
                    request.normalizedOwnerFqn());
            OwnerTemplates templates = resolveOwnerTemplates(owner);
            if (templates == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.DCS_OWNER_KIND_UNSUPPORTED,
                        "Owner does not support DCS templates: " + owner.eClass().getName(),
                        false); //$NON-NLS-1$
            }

            Template template = findDcsTemplateByName(templates.templates(), request.effectiveTemplateName());
            boolean templateCreated = false;
            if (template == null && request.shouldForceReplace()) {
                template = firstDcsTemplate(templates.templates());
            }
            if (template == null) {
                template = MdClassFactory.eINSTANCE.createTemplate();
                // A metadata child without a uuid fails EDT validation (SU106)
                // and the whole project then refuses to export into the
                // platform format — the schema itself is beside the point.
                template.setUuid(UUID.randomUUID());
                template.setName(request.effectiveTemplateName());
                template.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
                templates.templates().add(template);
                templateCreated = true;
            } else if (template.getTemplateType() != TemplateType.DATA_COMPOSITION_SCHEMA) {
                template.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
            }

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
                    templateCreated,
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
        ensureExternalSchemaFile(project, ownerForPath, holder.value.templateName(), request.shouldForceReplace());
        return holder.value;
    }

    public DcsUpsertQueryDatasetResult upsertQueryDataset(DcsUpsertQueryDatasetRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        IProject project = resolveProject(request.normalizedProjectName());
        readinessChecker.ensureReady(project);
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        MdObject readOwner = resolveOwner(project, configuration, request.normalizedOwnerFqn());
        SchemaResolution readResolution = resolveSchema(project, readOwner);
        if (readResolution.schema() == null && readResolution.externalSchema() != null) {
            return upsertExternalQueryDataset(request, readResolution.externalSchema());
        }

        Holder<DcsUpsertQueryDatasetResult> holder = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    project,
                    configuration,
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(project, owner, request.normalizedOwnerFqn());

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
        readinessChecker.ensureReady(project);
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        MdObject readOwner = resolveOwner(project, configuration, request.normalizedOwnerFqn());
        SchemaResolution readResolution = resolveSchema(project, readOwner);
        if (readResolution.schema() == null && readResolution.externalSchema() != null) {
            return upsertExternalParameter(request, readResolution.externalSchema());
        }

        Holder<DcsUpsertParameterResult> holder = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    project,
                    configuration,
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(project, owner, request.normalizedOwnerFqn());

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
        readinessChecker.ensureReady(project);
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        MdObject readOwner = resolveOwner(project, configuration, request.normalizedOwnerFqn());
        SchemaResolution readResolution = resolveSchema(project, readOwner);
        if (readResolution.schema() == null && readResolution.externalSchema() != null) {
            return upsertExternalCalculatedField(request, readResolution.externalSchema());
        }

        Holder<DcsUpsertCalculatedFieldResult> holder = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    project,
                    configuration,
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(project, owner, request.normalizedOwnerFqn());

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
            IProject project,
            Configuration configuration,
            String ownerFqn
    ) {
        MdObject txOwnerFromConfiguration = resolveOwnerFromTransactionConfiguration(
                transaction, configuration, ownerFqn);
        if (txOwnerFromConfiguration != null) {
            return txOwnerFromConfiguration;
        }

        try {
            IBmNamespace namespace = gateway.getBmModelManager().getBmNamespace(project);
            MdObject txOwnerByFqn = castMdObject(transaction.getTopObjectByFqn(namespace, ownerFqn));
            if (txOwnerByFqn != null) {
                return txOwnerByFqn;
            }
        } catch (RuntimeException e) {
            // Fall through to outside-transaction resolution.
        }

        MdObject owner = resolveOwner(project, configuration, ownerFqn);
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

    private MdObject resolveOwnerFromTransactionConfiguration(
            IBmPlatformTransaction transaction,
            Configuration configuration,
            String ownerFqn
    ) {
        if (configuration == null) {
            return null;
        }
        try {
            EObject txConfiguration = transaction.toTransactionObject(configuration);
            if (txConfiguration instanceof Configuration configurationInTransaction) {
                return findInConfiguration(configurationInTransaction, ownerFqn);
            }
        } catch (RuntimeException e) {
            // Fall through to alternative transaction lookup.
        }
        return null;
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
        return findTopLevelOwner(configuration, ownerFqn);
    }

    private MdObject findTopLevelOwner(Configuration configuration, String ownerFqn) {
        if (configuration == null || ownerFqn == null || ownerFqn.isBlank()) {
            return null;
        }
        String[] parts = ownerFqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2) {
            return null;
        }
        String type = normalizeToken(parts[0]);
        String name = parts[1];
        List<? extends MdObject> candidates = switch (type) {
            case "report", "отчет", "отчёт" -> configuration.getReports(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "dataprocessor", "обработка" -> configuration.getDataProcessors(); //$NON-NLS-1$ //$NON-NLS-2$
            default -> List.of();
        };
        for (MdObject candidate : candidates) {
            if (candidate != null && name.equalsIgnoreCase(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private SchemaResolution resolveSchema(IProject project, MdObject owner) {
        if (owner instanceof Report report) {
            Template mainTemplate = asTemplate(report.getMainDataCompositionSchema());
            ExternalDcsSchema external = readExternalSchema(project, owner, mainTemplate);
            if (external != null) {
                return new SchemaResolution(null, "main", external.templateName(), external); //$NON-NLS-1$
            }
            DataCompositionSchema schema = extractSchema(mainTemplate);
            if (schema != null) {
                return new SchemaResolution(schema, "main", safe(mainTemplate.getName()), null); //$NON-NLS-1$
            }
            return resolveFromTemplateList(project, owner, report.getTemplates());
        }
        if (owner instanceof ExternalReport report) {
            Template mainTemplate = asTemplate(report.getMainDataCompositionSchema());
            ExternalDcsSchema external = readExternalSchema(project, owner, mainTemplate);
            if (external != null) {
                return new SchemaResolution(null, "main", external.templateName(), external); //$NON-NLS-1$
            }
            DataCompositionSchema schema = extractSchema(mainTemplate);
            if (schema != null) {
                return new SchemaResolution(schema, "main", safe(mainTemplate.getName()), null); //$NON-NLS-1$
            }
            return resolveFromTemplateList(project, owner, report.getTemplates());
        }
        if (owner instanceof DataProcessor dataProcessor) {
            return resolveFromTemplateList(project, owner, dataProcessor.getTemplates());
        }
        if (owner instanceof ExternalDataProcessor dataProcessor) {
            return resolveFromTemplateList(project, owner, dataProcessor.getTemplates());
        }
        return new SchemaResolution(null, "none", null, null); //$NON-NLS-1$
    }

    private SchemaResolution resolveFromTemplateList(
            IProject project,
            MdObject owner,
            List<? extends Template> templates
    ) {
        ExternalDcsSchema external = findExternalInTemplates(project, owner, templates);
        if (external != null) {
            return new SchemaResolution(null, "templates", external.templateName(), external); //$NON-NLS-1$
        }
        DataCompositionSchema schema = findInTemplates(templates);
        if (schema != null) {
            return new SchemaResolution(schema, "templates", findTemplateName(schema, templates), null); //$NON-NLS-1$
        }
        return new SchemaResolution(null, "templates", firstDcsTemplateName(templates), null); //$NON-NLS-1$
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
            if (isDcsTemplate(template)) {
                count++;
            }
        }
        return count;
    }

    private DataCompositionSchema requireSchema(IProject project, MdObject owner, String ownerFqn) {
        SchemaResolution resolution = resolveSchema(project, owner);
        if (resolution.schema() == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_SCHEMA_NOT_FOUND,
                    "DCS schema is not configured for owner: " + ownerFqn,
                    false); //$NON-NLS-1$
        }
        return resolution.schema();
    }

    private String findTemplateName(DataCompositionSchema schema, List<? extends Template> templates) {
        for (Template template : templates) {
            if (template != null && template.getTemplate() == schema) {
                return safe(template.getName());
            }
        }
        return ""; //$NON-NLS-1$
    }

    private String firstDcsTemplateName(OwnerTemplates templates) {
        return templates == null ? "" : firstDcsTemplateName(templates.templates()); //$NON-NLS-1$
    }

    private String firstDcsTemplateName(List<? extends Template> templates) {
        Template template = firstDcsTemplate(templates);
        return template == null ? "" : safe(template.getName()); //$NON-NLS-1$
    }

    private Template firstDcsTemplate(List<? extends Template> templates) {
        if (templates == null) {
            return null;
        }
        for (Template template : templates) {
            if (isDcsTemplate(template)) {
                return template;
            }
        }
        return null;
    }

    private Template findDcsTemplateByName(List<? extends Template> templates, String name) {
        if (templates == null) {
            return null;
        }
        String token = normalize(name);
        for (Template template : templates) {
            if (isDcsTemplate(template) && normalize(template.getName()).equals(token)) {
                return template;
            }
        }
        return null;
    }

    private boolean isDcsTemplate(BasicTemplate template) {
        return template != null && template.getTemplateType() == TemplateType.DATA_COMPOSITION_SCHEMA;
    }

    private Template asTemplate(BasicTemplate template) {
        return template instanceof Template concrete ? concrete : null;
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
        if (templates == null) {
            return null;
        }
        for (Template template : templates) {
            DataCompositionSchema schema = extractSchema(template);
            if (schema != null) {
                return schema;
            }
        }
        return null;
    }

    private ExternalDcsSchema findExternalInTemplates(
            IProject project,
            MdObject owner,
            List<? extends Template> templates
    ) {
        if (templates == null) {
            return null;
        }
        for (Template template : templates) {
            ExternalDcsSchema schema = readExternalSchema(project, owner, template);
            if (schema != null) {
                return schema;
            }
        }
        return null;
    }

    private ExternalDcsSchema readExternalSchema(IProject project, MdObject owner, Template template) {
        if (project == null || owner == null || !isDcsTemplate(template)) {
            return null;
        }
        IFile file = resolveExternalSchemaFile(project, owner, safe(template.getName()));
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            Document document = readDcsDocument(file);
            Element root = document.getDocumentElement();
            if (root == null || !"DataCompositionSchema".equals(localName(root))) { //$NON-NLS-1$
                return null;
            }
            DcsDialect dialect = DcsDialect.of(root);
            return new ExternalDcsSchema(file, safe(template.getName()), dialect,
                    nodesFrom(root, DcsNodeKind.DATA_SET, dialect),
                    nodesFrom(root, DcsNodeKind.PARAMETER, dialect),
                    nodesFrom(root, DcsNodeKind.CALCULATED_FIELD, dialect),
                    nodesFrom(root, DcsNodeKind.SETTINGS_VARIANT, dialect).size());
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to read external DCS schema: " + file.getProjectRelativePath(), //$NON-NLS-1$
                    false,
                    e);
        }
    }

    private List<ExternalDcsNode> nodesFrom(Element root, DcsNodeKind kind, DcsDialect dialect) {
        String elementName = dialect.elementFor(kind);
        List<ExternalDcsNode> result = new ArrayList<>();
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && elementName.equals(localName(element))) {
                result.add(new ExternalDcsNode(
                        kind,
                        value(element, "name", dialect), //$NON-NLS-1$
                        value(element, "dataPath", dialect), //$NON-NLS-1$
                        value(element, "expression", dialect), //$NON-NLS-1$
                        value(element, "query", dialect), //$NON-NLS-1$
                        value(element, "dataSource", dialect), //$NON-NLS-1$
                        value(element, "presentationExpression", dialect), //$NON-NLS-1$
                        value(element, "autoFillAvailableFields", dialect), //$NON-NLS-1$
                        value(element, "useQueryGroupIfPossible", dialect), //$NON-NLS-1$
                        value(element, "availableAsField", dialect), //$NON-NLS-1$
                        value(element, "valueListAllowed", dialect), //$NON-NLS-1$
                        value(element, "denyIncompleteValues", dialect), //$NON-NLS-1$
                        value(element, "useRestriction", dialect), //$NON-NLS-1$
                        value(element, "type", dialect))); //$NON-NLS-1$
            }
        }
        return result;
    }

    /** Finds a schema node of the given kind by its identifying value. */
    private Element findNode(Element root, DcsNodeKind kind, DcsDialect dialect, String key, String wanted) {
        String elementName = dialect.elementFor(kind);
        String token = normalize(wanted);
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element
                    && elementName.equals(localName(element))
                    && normalize(value(element, key, dialect)).equals(token)) {
                return element;
            }
        }
        return null;
    }

    private DcsUpsertQueryDatasetResult upsertExternalQueryDataset(
            DcsUpsertQueryDatasetRequest request,
            ExternalDcsSchema externalSchema
    ) {
        try {
            Document document = readDcsDocument(externalSchema.file());
            Element root = document.getDocumentElement();
            DcsDialect dialect = DcsDialect.of(root);
            Element dataset = findNode(root, DcsNodeKind.DATA_SET, dialect, "name", //$NON-NLS-1$
                    request.normalizedDatasetName());
            boolean created = false;
            // A platform query data set must point at a data source, and the
            // schema must declare it. Missing either makes the file unreadable.
            String dataSource = ensureDataSource(document, root, dialect, request.normalizedDataSource());
            if (dataset == null) {
                dataset = createNode(document, dialect, DcsNodeKind.DATA_SET);
                dataset.setAttributeNS(XSI_NS, "xsi:type", dialect.queryDataSetType); //$NON-NLS-1$
                setValue(dataset, "name", request.normalizedDatasetName(), dialect); //$NON-NLS-1$
                appendInOrder(root, dataset, dialect);
                created = true;
            }
            setValue(dataset, "query", request.normalizedQuery(), dialect); //$NON-NLS-1$
            setValue(dataset, "dataSource", dataSource, dialect); //$NON-NLS-1$
            setValue(dataset, "autoFillAvailableFields", request.autoFillAvailableFields(), dialect); //$NON-NLS-1$
            setValue(dataset, "useQueryGroupIfPossible", request.useQueryGroupIfPossible(), dialect); //$NON-NLS-1$
            writeDcsDocument(externalSchema.file(), document);
            return new DcsUpsertQueryDatasetResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    value(dataset, "name", dialect), //$NON-NLS-1$
                    created,
                    value(dataset, "query", dialect), //$NON-NLS-1$
                    value(dataset, "dataSource", dialect), //$NON-NLS-1$
                    boolValue(dataset, "autoFillAvailableFields", dialect, true), //$NON-NLS-1$
                    boolValue(dataset, "useQueryGroupIfPossible", dialect, true)); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw externalDcsMutationFailed(externalSchema.file(), e);
        }
    }

    private DcsUpsertParameterResult upsertExternalParameter(
            DcsUpsertParameterRequest request,
            ExternalDcsSchema externalSchema
    ) {
        try {
            Document document = readDcsDocument(externalSchema.file());
            Element root = document.getDocumentElement();
            DcsDialect dialect = DcsDialect.of(root);
            Element parameter = findNode(root, DcsNodeKind.PARAMETER, dialect, "name", //$NON-NLS-1$
                    request.normalizedParameterName());
            boolean created = false;
            if (parameter == null) {
                parameter = createNode(document, dialect, DcsNodeKind.PARAMETER);
                setValue(parameter, "name", request.normalizedParameterName(), dialect); //$NON-NLS-1$
                appendInOrder(root, parameter, dialect);
                created = true;
            }
            setValue(parameter, "expression", request.normalizedExpression(), dialect); //$NON-NLS-1$
            setValue(parameter, "availableAsField", request.availableAsField(), dialect); //$NON-NLS-1$
            setValue(parameter, "valueListAllowed", request.valueListAllowed(), dialect); //$NON-NLS-1$
            setValue(parameter, "denyIncompleteValues", request.denyIncompleteValues(), dialect); //$NON-NLS-1$
            setValue(parameter, "useRestriction", request.useRestriction(), dialect); //$NON-NLS-1$
            writeDcsDocument(externalSchema.file(), document);
            return new DcsUpsertParameterResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    value(parameter, "name", dialect), //$NON-NLS-1$
                    created,
                    value(parameter, "expression", dialect), //$NON-NLS-1$
                    boolValue(parameter, "availableAsField", dialect, true), //$NON-NLS-1$
                    boolValue(parameter, "valueListAllowed", dialect, false), //$NON-NLS-1$
                    boolValue(parameter, "denyIncompleteValues", dialect, false), //$NON-NLS-1$
                    boolValue(parameter, "useRestriction", dialect, false)); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw externalDcsMutationFailed(externalSchema.file(), e);
        }
    }

    private DcsUpsertCalculatedFieldResult upsertExternalCalculatedField(
            DcsUpsertCalculatedFieldRequest request,
            ExternalDcsSchema externalSchema
    ) {
        try {
            Document document = readDcsDocument(externalSchema.file());
            Element root = document.getDocumentElement();
            DcsDialect dialect = DcsDialect.of(root);
            Element field = findNode(root, DcsNodeKind.CALCULATED_FIELD, dialect, "dataPath", //$NON-NLS-1$
                    request.normalizedDataPath());
            boolean created = false;
            if (field == null) {
                field = createNode(document, dialect, DcsNodeKind.CALCULATED_FIELD);
                setValue(field, "dataPath", request.normalizedDataPath(), dialect); //$NON-NLS-1$
                appendInOrder(root, field, dialect);
                created = true;
            }
            setValue(field, "expression", request.normalizedExpression(), dialect); //$NON-NLS-1$
            setValue(field, "presentationExpression", request.normalizedPresentationExpression(), dialect); //$NON-NLS-1$
            writeDcsDocument(externalSchema.file(), document);
            return new DcsUpsertCalculatedFieldResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    value(field, "dataPath", dialect), //$NON-NLS-1$
                    created,
                    value(field, "expression", dialect), //$NON-NLS-1$
                    value(field, "presentationExpression", dialect)); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw externalDcsMutationFailed(externalSchema.file(), e);
        }
    }

    private MetadataOperationException externalDcsMutationFailed(IFile file, RuntimeException e) {
        return new MetadataOperationException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "Failed to update external DCS schema: " + file.getProjectRelativePath(), //$NON-NLS-1$
                false,
                e);
    }

    private void ensureExternalSchemaFile(IProject project, MdObject owner, String templateName, boolean forceReplace) {
        IFile file = resolveExternalSchemaFile(project, owner, templateName);
        if (file == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_OWNER_KIND_UNSUPPORTED,
                    "Cannot resolve external DCS path for owner: " + owner.eClass().getName(), //$NON-NLS-1$
                    false);
        }
        if (file.exists() && !forceReplace) {
            return;
        }
        writeDcsText(file, EMPTY_DCS_XML);
    }

    private IFile resolveExternalSchemaFile(IProject project, MdObject owner, String templateName) {
        String folder = topFolderForOwner(owner);
        if (folder == null || templateName == null || templateName.isBlank()) {
            return null;
        }
        String path = "src/" + folder + "/" + safe(owner.getName()) //$NON-NLS-1$ //$NON-NLS-2$
                + "/Templates/" + templateName + "/" + TEMPLATE_DCS_FILE; //$NON-NLS-1$ //$NON-NLS-2$
        return project.getFile(path);
    }

    private String topFolderForOwner(MdObject owner) {
        if (owner instanceof Report || owner instanceof ExternalReport) {
            return "Reports"; //$NON-NLS-1$
        }
        if (owner instanceof DataProcessor || owner instanceof ExternalDataProcessor) {
            return "DataProcessors"; //$NON-NLS-1$
        }
        return null;
    }

    private Document readDcsDocument(IFile file) {
        try (InputStream input = file.getContents()) {
            DocumentBuilderFactory factory = newDocumentBuilderFactory();
            return factory.newDocumentBuilder().parse(input);
        } catch (IOException | CoreException | ParserConfigurationException | SAXException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to parse external DCS schema: " + file.getProjectRelativePath(), //$NON-NLS-1$
                    false,
                    e);
        }
    }

    private DocumentBuilderFactory newDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory;
    }

    private void writeDcsDocument(IFile file, Document document) {
        try {
            Element root = document.getDocumentElement();
            if (root != null) {
                // Declare only the prefixes the document's own dialect uses:
                // stamping the EDT design-time prefix onto a platform schema is
                // what made xsi:type unresolvable and the export fail.
                if (DcsDialect.of(root) == DcsDialect.EDT_DT) {
                    root.setAttribute("xmlns:schema", DCS_EDT_DT_NS); //$NON-NLS-1$
                }
                root.setAttribute("xmlns:xsi", XSI_NS); //$NON-NLS-1$
            }
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(OutputKeys.INDENT, "yes"); //$NON-NLS-1$
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            writeDcsText(file, writer.toString());
        } catch (TransformerException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to serialize external DCS schema: " + file.getProjectRelativePath(), //$NON-NLS-1$
                    false,
                    e);
        }
    }

    private void writeDcsText(IFile file, String content) {
        try {
            createParentsIfMissing(file);
            try (ByteArrayInputStream input = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
                if (file.exists()) {
                    file.setContents(input, IResource.FORCE, null);
                } else {
                    file.create(input, IResource.FORCE, null);
                }
            }
            file.refreshLocal(IResource.DEPTH_ZERO, null);
        } catch (IOException | CoreException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to write external DCS schema: " + file.getProjectRelativePath(), //$NON-NLS-1$
                    false,
                    e);
        }
    }

    private void createParentsIfMissing(IFile file) throws CoreException {
        IContainer parent = file.getParent();
        if (parent instanceof org.eclipse.core.resources.IFolder folder && !folder.exists()) {
            createFolderIfMissing(folder);
        }
    }

    private void createFolderIfMissing(org.eclipse.core.resources.IFolder folder) throws CoreException {
        IContainer parent = folder.getParent();
        if (parent instanceof org.eclipse.core.resources.IFolder parentFolder && !parentFolder.exists()) {
            createFolderIfMissing(parentFolder);
        }
        if (!folder.exists()) {
            folder.create(true, true, null);
        }
    }

    private String attr(Element element, String name) {
        if ("type".equals(name) && element.hasAttributeNS(XSI_NS, "type")) { //$NON-NLS-1$ //$NON-NLS-2$
            return element.getAttributeNS(XSI_NS, "type"); //$NON-NLS-1$
        }
        return element.hasAttribute(name) ? element.getAttribute(name) : ""; //$NON-NLS-1$
    }

    private String localName(Node node) {
        String local = node.getLocalName();
        return local != null ? local : node.getNodeName();
    }

    // ─── dialect-aware DOM access ───────────────────────────────────────────

    /**
     * Reads a named value from a schema node in whichever dialect it is written.
     *
     * @param element schema node such as a data set or a parameter
     * @param name    logical value name, for example {@code query}
     * @param dialect dialect the owning document is written in
     * @return the value, or an empty string when absent; never {@code null}
     */
    private String value(Element element, String name, DcsDialect dialect) {
        if (element == null) {
            return ""; //$NON-NLS-1$
        }
        if (dialect.valuesInAttributes) {
            return attr(element, name);
        }
        if ("type".equals(name) && element.hasAttributeNS(XSI_NS, "type")) { //$NON-NLS-1$ //$NON-NLS-2$
            return element.getAttributeNS(XSI_NS, "type"); //$NON-NLS-1$
        }
        Element child = childElement(element, name);
        return child == null ? "" : safe(child.getTextContent()); //$NON-NLS-1$
    }

    private boolean boolValue(Element element, String name, DcsDialect dialect, boolean defaultValue) {
        String raw = value(element, name, dialect);
        return raw == null || raw.isBlank() ? defaultValue : Boolean.parseBoolean(raw);
    }

    /** Writes a named value in the dialect of the owning document; {@code null} is a no-op. */
    private void setValue(Element element, String name, String newValue, DcsDialect dialect) {
        if (newValue == null) {
            return;
        }
        if (dialect.valuesInAttributes) {
            element.setAttribute(name, newValue);
            return;
        }
        Element child = childElement(element, name);
        if (child == null) {
            child = element.getOwnerDocument().createElementNS(dialect.namespaceUri, name);
            element.appendChild(child);
        }
        child.setTextContent(newValue);
    }

    private void setValue(Element element, String name, Boolean newValue, DcsDialect dialect) {
        if (newValue != null) {
            setValue(element, name, Boolean.toString(newValue.booleanValue()), dialect);
        }
    }

    private Element childElement(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    /** Creates an empty schema node of the given kind in the document's dialect. */
    private Element createNode(Document document, DcsDialect dialect, DcsNodeKind kind) {
        return document.createElementNS(dialect.namespaceUri, dialect.elementFor(kind));
    }

    /**
     * Appends a top-level element where the platform schema expects it.
     *
     * <p>The platform validates the element sequence of a schema, so appending
     * to the end works only by accident. The EDT dialect is order-agnostic and
     * keeps the plain append.</p>
     */
    private void appendInOrder(Element root, Element child, DcsDialect dialect) {
        if (dialect != DcsDialect.PLATFORM) {
            root.appendChild(child);
            return;
        }
        int position = PLATFORM_ELEMENT_ORDER.indexOf(localName(child));
        if (position < 0) {
            root.appendChild(child);
            return;
        }
        for (Node sibling = root.getFirstChild(); sibling != null; sibling = sibling.getNextSibling()) {
            if (!(sibling instanceof Element element)) {
                continue;
            }
            int siblingPosition = PLATFORM_ELEMENT_ORDER.indexOf(localName(element));
            if (siblingPosition > position) {
                root.insertBefore(child, sibling);
                return;
            }
        }
        root.appendChild(child);
    }

    /**
     * Guarantees the schema has the data source a platform query data set must
     * reference, and returns its name.
     *
     * <p>The EDT dialect carries no separate data source element, so the caller
     * keeps whatever it was given there.</p>
     */
    private String ensureDataSource(Document document, Element root, DcsDialect dialect, String requested) {
        String name = requested == null || requested.isBlank() ? DEFAULT_DATA_SOURCE : requested;
        if (dialect != DcsDialect.PLATFORM) {
            return name;
        }
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && "dataSource".equals(localName(element))) { //$NON-NLS-1$
                Element existing = childElement(element, "name"); //$NON-NLS-1$
                return existing == null ? name : safe(existing.getTextContent());
            }
        }
        Element source = document.createElementNS(dialect.namespaceUri, "dataSource"); //$NON-NLS-1$
        Element sourceName = document.createElementNS(dialect.namespaceUri, "name"); //$NON-NLS-1$
        sourceName.setTextContent(name);
        source.appendChild(sourceName);
        Element sourceType = document.createElementNS(dialect.namespaceUri, "dataSourceType"); //$NON-NLS-1$
        sourceType.setTextContent("Local"); //$NON-NLS-1$
        source.appendChild(sourceType);
        appendInOrder(root, source, dialect);
        return name;
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

    private String normalizeToken(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim()
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                .toLowerCase(Locale.ROOT);
    }

    private boolean isExternalOwner(MdObject owner) {
        return owner instanceof ExternalReport || owner instanceof ExternalDataProcessor;
    }

    private record SchemaResolution(
            DataCompositionSchema schema,
            String source,
            String templateName,
            ExternalDcsSchema externalSchema
    ) {
        private boolean schemaPresent() {
            return schema != null || externalSchema != null;
        }

        private int dataSetsCount() {
            return schema != null ? schema.getDataSets().size() : externalSchemaCount(NodeKind.DATASET);
        }

        private int parametersCount() {
            return schema != null ? schema.getParameters().size() : externalSchemaCount(NodeKind.PARAMETER);
        }

        private int calculatedFieldsCount() {
            return schema != null ? schema.getCalculatedFields().size() : externalSchemaCount(NodeKind.CALCULATED);
        }

        private int settingsVariantsCount() {
            return externalSchema == null ? 0 : externalSchema.settingsVariantsCount();
        }

        private int externalSchemaCount(NodeKind kind) {
            if (externalSchema == null) {
                return 0;
            }
            return switch (kind) {
                case DATASET -> externalSchema.dataSets().size();
                case PARAMETER -> externalSchema.parameters().size();
                case CALCULATED -> externalSchema.calculatedFields().size();
            };
        }
    }

    private record ExternalDcsSchema(
            IFile file,
            String templateName,
            DcsDialect dialect,
            List<ExternalDcsNode> dataSets,
            List<ExternalDcsNode> parameters,
            List<ExternalDcsNode> calculatedFields,
            int settingsVariantsCount
    ) {
        private List<DcsNodeItem> nodes(String nodeKind) {
            List<DcsNodeItem> result = new ArrayList<>();
            if ("all".equals(nodeKind) || "dataset".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
                dataSets.forEach(node -> result.add(node.toNodeItem()));
            }
            if ("all".equals(nodeKind) || "parameter".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
                parameters.forEach(node -> result.add(node.toNodeItem()));
            }
            if ("all".equals(nodeKind) || "calculated".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
                calculatedFields.forEach(node -> result.add(node.toNodeItem()));
            }
            return result;
        }
    }

    private record ExternalDcsNode(
            DcsNodeKind kind,
            String name,
            String dataPath,
            String expression,
            String query,
            String dataSource,
            String presentationExpression,
            String autoFillAvailableFields,
            String useQueryGroupIfPossible,
            String availableAsField,
            String valueListAllowed,
            String denyIncompleteValues,
            String useRestriction,
            String xsiType
    ) {
        private DcsNodeItem toNodeItem() {
            return switch (kind) {
                case DATA_SET -> new DcsNodeItem("dataset", name, //$NON-NLS-1$
                        (xsiType.isBlank() ? "DataCompositionSchemaDataSetQuery" : xsiType) //$NON-NLS-1$
                                + " query=" + query); //$NON-NLS-1$
                case PARAMETER -> new DcsNodeItem("parameter", name, "expression=" + expression); //$NON-NLS-1$ //$NON-NLS-2$
                case CALCULATED_FIELD -> new DcsNodeItem("calculated", dataPath, "expression=" + expression); //$NON-NLS-1$ //$NON-NLS-2$
                case SETTINGS_VARIANT -> new DcsNodeItem("variant", name, ""); //$NON-NLS-1$ //$NON-NLS-2$
            };
        }
    }

    private enum NodeKind {
        DATASET,
        PARAMETER,
        CALCULATED
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
