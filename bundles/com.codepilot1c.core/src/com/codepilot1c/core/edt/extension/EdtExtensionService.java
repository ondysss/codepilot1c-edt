package com.codepilot1c.core.edt.extension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com._1c.g5.v8.dt.platform.version.Version;
import com.codepilot1c.core.edt.BmObjectHelper;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Service for EDT extension project read operations.
 */
public class EdtExtensionService {

    private final EdtMetadataGateway gateway;

    public EdtExtensionService() {
        this(new EdtMetadataGateway());
    }

    EdtExtensionService(EdtMetadataGateway gateway) {
        this.gateway = gateway;
    }

    public ExtensionAdoptObjectResult adoptObject(ExtensionAdoptObjectRequest request) {
        request.validate();
        gateway.ensureExtensionRuntimeAvailable();

        IExtensionProject extensionProject = resolveExtensionProject(request.normalizedExtensionProjectName());
        IProject extensionParent = extensionProject.getParentProject();
        String effectiveBaseProjectName = request.normalizedBaseProjectName();
        if (effectiveBaseProjectName == null && extensionParent != null) {
            effectiveBaseProjectName = extensionParent.getName();
        }
        if (effectiveBaseProjectName == null || effectiveBaseProjectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Cannot resolve base project for extension: " + extensionProject.getProject().getName(),
                    false); //$NON-NLS-1$
        }
        if (extensionParent != null && !extensionParent.getName().equalsIgnoreCase(effectiveBaseProjectName)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "base_project does not match extension parent project", false); //$NON-NLS-1$
        }

        IProject baseProject = gateway.resolveProject(effectiveBaseProjectName);
        if (baseProject == null || !baseProject.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Base project not found: " + effectiveBaseProjectName, false); //$NON-NLS-1$
        }
        Configuration baseConfiguration = gateway.getConfigurationProvider().getConfiguration(baseProject);
        if (baseConfiguration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Base configuration is unavailable for project: " + effectiveBaseProjectName, false); //$NON-NLS-1$
        }

        MdObject source = findSourceObject(baseConfiguration, request.normalizedSourceObjectFqn());
        if (source == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Source object not found: " + request.normalizedSourceObjectFqn(), false); //$NON-NLS-1$
        }

        boolean alreadyAdopted = gateway.getModelObjectAdopter().isAdopted(source, extensionProject);
        MdObject adopted = null;
        boolean updated = false;
        try {
            if (alreadyAdopted) {
                if (!request.shouldUpdateIfExists()) {
                    throw new MetadataOperationException(
                            MetadataOperationCode.EXTENSION_ADOPT_CONFLICT,
                            "Object already adopted to extension: " + request.normalizedSourceObjectFqn(), false); //$NON-NLS-1$
                }
                adopted = castMdObject(gateway.getModelObjectAdopter()
                        .updateAdopted(source, extensionProject, new NullProgressMonitor()));
                updated = true;
            } else {
                adopted = castMdObject(gateway.getModelObjectAdopter()
                        .adoptAndAttach(source, extensionProject, new NullProgressMonitor()));
            }
        } catch (CoreException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to adopt object: " + e.getMessage(), false, e); //$NON-NLS-1$
        } catch (RuntimeException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to adopt object: " + e.getMessage(), false, e); //$NON-NLS-1$
        }
        if (adopted == null) {
            adopted = castMdObject(gateway.getModelObjectAdopter().getAdopted(source, extensionProject));
        }
        if (adopted == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EXTENSION_API_UNAVAILABLE,
                    "ModelObjectAdopter returned null adopted object", false); //$NON-NLS-1$
        }

        String sourceFqn = safeFqn(source, source.eClass().getName(), safe(source.getName()));
        String adoptedFqn = safeFqn(adopted, adopted.eClass().getName(), safe(adopted.getName()));
        return new ExtensionAdoptObjectResult(
                extensionProject.getProject().getName(),
                effectiveBaseProjectName,
                sourceFqn,
                adoptedFqn,
                adopted.eClass().getName(),
                safe(adopted.getName()),
                alreadyAdopted,
                updated);
    }

    public ExtensionSetPropertyStateResult setPropertyState(ExtensionSetPropertyStateRequest request) {
        request.validate();
        gateway.ensureExtensionRuntimeAvailable();

        IExtensionProject extensionProject = resolveExtensionProject(request.normalizedExtensionProjectName());
        String effectiveBaseProjectName = resolveEffectiveBaseProjectName(extensionProject, request.normalizedBaseProjectName());
        IProject baseProject = gateway.resolveProject(effectiveBaseProjectName);
        if (baseProject == null || !baseProject.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Base project not found: " + effectiveBaseProjectName, false); //$NON-NLS-1$
        }
        Configuration baseConfiguration = gateway.getConfigurationProvider().getConfiguration(baseProject);
        if (baseConfiguration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Base configuration is unavailable for project: " + effectiveBaseProjectName, false); //$NON-NLS-1$
        }
        final MdPropertyState state = request.parseState();
        final String sourceRef = request.normalizedSourceObjectFqn();
        final String propertyName = request.normalizedPropertyName();
        final Holder<ExtensionSetPropertyStateResult> resultHolder = new Holder<>();

        executeWrite(baseProject, transaction -> {
            Configuration txConfiguration = transaction.toTransactionObject(baseConfiguration);
            if (txConfiguration == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot access configuration in BM transaction", false); //$NON-NLS-1$
            }

            MdObject source = findSourceObject(txConfiguration, sourceRef);
            if (source == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_NOT_FOUND,
                        "Source object not found: " + sourceRef, false); //$NON-NLS-1$
            }
            MdObject adopted = castMdObject(gateway.getModelObjectAdopter().getAdopted(source, extensionProject));
            if (adopted == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EXTENSION_NOT_FOUND,
                        "Source object is not adopted in extension: " + sourceRef, false); //$NON-NLS-1$
            }
            MdObject txAdopted = castMdObject(transaction.toTransactionObject(adopted));
            if (txAdopted != null) {
                adopted = txAdopted;
            }

            EStructuralFeature feature = adopted.eClass().getEStructuralFeature(propertyName);
            if (feature == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EXTENSION_PROPERTY_STATE_INVALID,
                        "Property not found on adopted object: " + propertyName, false); //$NON-NLS-1$
            }
            if (feature.isDerived() || feature.isTransient() || feature.isVolatile()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EXTENSION_PROPERTY_STATE_INVALID,
                        "Property is not eligible for extension state updates: " + propertyName,
                        false); //$NON-NLS-1$
            }
            gateway.getMdAdoptedPropertyAccess().setPropertyState(adopted, feature, state);

            resultHolder.value = new ExtensionSetPropertyStateResult(
                    extensionProject.getProject().getName(),
                    effectiveBaseProjectName,
                    safeFqn(source, source.eClass().getName(), safe(source.getName())),
                    safeFqn(adopted, adopted.eClass().getName(), safe(adopted.getName())),
                    feature.getName(),
                    state.name());
            return null;
        });

        if (resultHolder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to update extension property state", false); //$NON-NLS-1$
        }
        return resultHolder.value;
    }

    public ExtensionCreateProjectResult createProject(ExtensionCreateProjectRequest request) {
        request.validate();
        gateway.ensureExtensionRuntimeAvailable();

        String baseProjectName = request.normalizedBaseProjectName();
        IProject baseProject = gateway.resolveProject(baseProjectName);
        if (baseProject == null || !baseProject.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Base project not found: " + baseProjectName, false); //$NON-NLS-1$
        }
        String extensionProjectName = request.normalizedExtensionProjectName();
        IProject existingProject = gateway.resolveProject(extensionProjectName);
        if (existingProject != null && existingProject.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EXTENSION_ADOPT_CONFLICT,
                    "Project already exists: " + extensionProjectName, false); //$NON-NLS-1$
        }

        IV8ProjectManager v8ProjectManager = gateway.getV8ProjectManager();
        IV8Project baseV8Project = v8ProjectManager.getProject(baseProject);
        if (baseV8Project == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Base project is not a V8 project: " + baseProjectName, false); //$NON-NLS-1$
        }

        Version version = request.effectiveVersion(baseV8Project.getVersion());
        Path defaultContainer = Path.of(baseProject.getLocation().toOSString()).getParent();
        if (defaultContainer == null) {
            defaultContainer = Path.of(baseProject.getWorkspace().getRoot().getLocation().toOSString());
        }
        Path projectPath = request.effectiveProjectPath(defaultContainer);

        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setName(request.effectiveConfigurationName());
        configuration.setConfigurationExtensionPurpose(request.effectivePurpose());
        CompatibilityMode compatibilityMode = request.effectiveCompatibilityMode();
        if (compatibilityMode != null) {
            configuration.setConfigurationExtensionCompatibilityMode(compatibilityMode);
        }

        IProject createdProject;
        try {
            createdProject = gateway.getExtensionProjectManager().create(
                    extensionProjectName,
                    projectPath,
                    version,
                    configuration,
                    baseProject,
                    new NullProgressMonitor());
        } catch (CoreException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to create extension project: " + e.getMessage(), false, e); //$NON-NLS-1$
        }
        if (createdProject == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EXTENSION_API_UNAVAILABLE,
                    "IExtensionProjectManager returned null while creating extension project", false); //$NON-NLS-1$
        }

        Configuration createdConfiguration = configuration;
        try {
            IExtensionProject extensionProject = resolveExtensionProject(createdProject.getName());
            if (extensionProject.getConfiguration() != null) {
                createdConfiguration = extensionProject.getConfiguration();
            }
        } catch (MetadataOperationException e) {
            // Extension indexing may lag right after creation, keep data from requested configuration.
        }
        return new ExtensionCreateProjectResult(
                createdProject.getName(),
                baseProject.getName(),
                createdProject.getLocation() != null ? createdProject.getLocation().toOSString() : "", //$NON-NLS-1$
                version != null ? version.toString() : "", //$NON-NLS-1$
                createdConfiguration != null ? safe(createdConfiguration.getName()) : request.effectiveConfigurationName(),
                createdConfiguration != null && createdConfiguration.getConfigurationExtensionPurpose() != null
                        ? createdConfiguration.getConfigurationExtensionPurpose().name()
                        : request.effectivePurpose().name(),
                createdConfiguration != null && createdConfiguration.getConfigurationExtensionCompatibilityMode() != null
                        ? createdConfiguration.getConfigurationExtensionCompatibilityMode().name()
                        : (compatibilityMode != null ? compatibilityMode.name() : "")); //$NON-NLS-1$
    }

    public ExtensionProjectsResult listProjects(ExtensionListProjectsRequest request) {
        gateway.ensureExtensionRuntimeAvailable();
        IV8ProjectManager v8ProjectManager = gateway.getV8ProjectManager();

        String baseProjectFilter = request.normalizedBaseProjectName();
        List<ExtensionProjectSummary> items = new ArrayList<>();
        for (IExtensionProject extensionProject : v8ProjectManager.getProjects(IExtensionProject.class)) {
            if (extensionProject == null || extensionProject.getProject() == null) {
                continue;
            }
            IProject parent = extensionProject.getParentProject();
            if (baseProjectFilter != null) {
                String parentName = parent != null ? parent.getName() : null;
                if (parentName == null || !parentName.equalsIgnoreCase(baseProjectFilter)) {
                    continue;
                }
            }

            Configuration configuration = extensionProject.getConfiguration();
            String configName = configuration != null ? safe(configuration.getName()) : ""; //$NON-NLS-1$
            String purpose = configuration != null && configuration.getConfigurationExtensionPurpose() != null
                    ? configuration.getConfigurationExtensionPurpose().name()
                    : ""; //$NON-NLS-1$
            String compatibility = configuration != null && configuration.getConfigurationExtensionCompatibilityMode() != null
                    ? configuration.getConfigurationExtensionCompatibilityMode().name()
                    : ""; //$NON-NLS-1$

            items.add(new ExtensionProjectSummary(
                    extensionProject.getProject().getName(),
                    parent != null ? parent.getName() : "", //$NON-NLS-1$
                    configName,
                    purpose,
                    compatibility));
        }

        items.sort(Comparator.comparing(ExtensionProjectSummary::extensionProject, String.CASE_INSENSITIVE_ORDER));
        return new ExtensionProjectsResult(items.size(), items);
    }

    public ExtensionObjectsResult listObjects(ExtensionListObjectsRequest request) {
        gateway.ensureExtensionRuntimeAvailable();

        IExtensionProject extensionProject = resolveExtensionProject(request.normalizedExtensionProjectName());
        String baseProjectName = resolveEffectiveBaseProjectName(extensionProject, null);
        IProject baseProject = gateway.resolveProject(baseProjectName);
        if (baseProject == null || !baseProject.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Base project not found: " + baseProjectName, false); //$NON-NLS-1$
        }
        Configuration baseConfiguration = gateway.getConfigurationProvider().getConfiguration(baseProject);
        if (baseConfiguration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Base configuration is unavailable for project: " + baseProjectName,
                    false); //$NON-NLS-1$
        }

        String typeFilter = request.normalizedTypeFilter();
        String nameFilter = request.normalizedNameContains();
        int offset = request.effectiveOffset();
        int limit = request.effectiveLimit();
        List<ExtensionObjectSummary> all = collectAdoptedTopLevelObjects(
                baseConfiguration,
                extensionProject,
                typeFilter,
                nameFilter);
        all.sort(Comparator
                .comparing(ExtensionObjectSummary::kind, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ExtensionObjectSummary::name, String.CASE_INSENSITIVE_ORDER));

        int total = all.size();
        int start = Math.min(offset, total);
        int end = Math.min(start + limit, total);
        List<ExtensionObjectSummary> page = start >= end
                ? List.of()
                : new ArrayList<>(all.subList(start, end));

        return new ExtensionObjectsResult(
                extensionProject.getProject().getName(),
                total,
                page.size(),
                start,
                limit,
                end < total,
                page);
    }

    private IExtensionProject resolveExtensionProject(String projectName) {
        IV8ProjectManager v8ProjectManager = gateway.getV8ProjectManager();
        IV8Project direct = v8ProjectManager.getProject(projectName);
        if (direct instanceof IExtensionProject extensionProject) {
            return extensionProject;
        }
        for (IExtensionProject extensionProject : v8ProjectManager.getProjects(IExtensionProject.class)) {
            if (extensionProject != null
                    && extensionProject.getProject() != null
                    && extensionProject.getProject().getName().equalsIgnoreCase(projectName)) {
                return extensionProject;
            }
        }
        throw new MetadataOperationException(
                MetadataOperationCode.EXTENSION_PROJECT_NOT_FOUND,
                "Extension project not found: " + projectName, false); //$NON-NLS-1$
    }

    private List<ExtensionObjectSummary> collectAdoptedTopLevelObjects(
            Configuration baseConfiguration,
            IExtensionProject extensionProject,
            String typeFilter,
            String nameFilter
    ) {
        List<ExtensionObjectSummary> items = new ArrayList<>();
        for (MdObject source : collectKnownTopLevelObjects(baseConfiguration)) {
            if (source == null) {
                continue;
            }
            String kind = source.eClass().getName();
            if (typeFilter != null && !normalize(kind).contains(typeFilter)) {
                continue;
            }
            String name = safe(source.getName());
            if (nameFilter != null && !name.toLowerCase(Locale.ROOT).contains(nameFilter)) {
                continue;
            }
            if (!gateway.getModelObjectAdopter().isAdopted(source, extensionProject)) {
                continue;
            }
            MdObject adopted = castMdObject(gateway.getModelObjectAdopter().getAdopted(source, extensionProject));
            if (adopted == null) {
                continue;
            }
            String adoptedKind = adopted.eClass().getName();
            String adoptedName = safe(adopted.getName());
            items.add(new ExtensionObjectSummary(
                    safeFqn(adopted, adoptedKind, adoptedName),
                    adoptedName,
                    adoptedKind,
                    adopted.getObjectBelonging() != null ? adopted.getObjectBelonging().name() : "", //$NON-NLS-1$
                    resolveSynonym(adopted.getSynonym()),
                    adopted.getExtendedConfigurationObject() != null
                            ? adopted.getExtendedConfigurationObject().toString()
                            : "", //$NON-NLS-1$
                    adopted.getExtension() != null));
        }
        return items;
    }

    private List<MdObject> collectKnownTopLevelObjects(Configuration configuration) {
        List<MdObject> all = new ArrayList<>();
        all.addAll(configuration.getCatalogs());
        all.addAll(configuration.getDocuments());
        all.addAll(configuration.getInformationRegisters());
        all.addAll(configuration.getAccumulationRegisters());
        all.addAll(configuration.getAccountingRegisters());
        all.addAll(configuration.getCalculationRegisters());
        all.addAll(configuration.getCommonModules());
        all.addAll(configuration.getEnums());
        all.addAll(configuration.getReports());
        all.addAll(configuration.getDataProcessors());
        all.addAll(configuration.getConstants());
        all.addAll(configuration.getSubsystems());
        all.addAll(configuration.getRoles());
        all.addAll(configuration.getCommonCommands());
        all.addAll(configuration.getCommonForms());
        all.addAll(configuration.getCommonTemplates());
        all.addAll(configuration.getCommonPictures());
        all.addAll(configuration.getScheduledJobs());
        all.addAll(configuration.getDefinedTypes());
        all.addAll(configuration.getDocumentJournals());
        all.addAll(configuration.getDocumentNumerators());
        all.addAll(configuration.getChartsOfAccounts());
        all.addAll(configuration.getChartsOfCharacteristicTypes());
        all.addAll(configuration.getChartsOfCalculationTypes());
        all.addAll(configuration.getExchangePlans());
        all.addAll(configuration.getBusinessProcesses());
        all.addAll(configuration.getTasks());
        all.addAll(configuration.getSequences());
        all.addAll(configuration.getFunctionalOptions());
        all.addAll(configuration.getFunctionalOptionsParameters());
        all.addAll(configuration.getSessionParameters());
        all.addAll(configuration.getSettingsStorages());
        all.addAll(configuration.getXDTOPackages());
        all.addAll(configuration.getWsReferences());
        all.addAll(configuration.getWebServices());
        all.addAll(configuration.getHttpServices());
        all.addAll(configuration.getIntegrationServices());
        all.addAll(configuration.getExternalDataSources());
        all.addAll(configuration.getEventSubscriptions());
        return all;
    }

    private MdObject findSourceObject(Configuration configuration, String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return null;
        }
        MdObject resolvedByFqn = resolveByFqn(configuration, sourceRef);
        if (resolvedByFqn != null) {
            return resolvedByFqn;
        }
        int dot = sourceRef.indexOf('.');
        if (dot > 0 && dot < sourceRef.length() - 1) {
            String rawKind = sourceRef.substring(0, dot);
            String rawName = sourceRef.substring(dot + 1);
            MdObject byKindAndName = findByKindAndName(configuration, rawKind, rawName);
            if (byKindAndName != null) {
                return byKindAndName;
            }
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
            for (Object candidate : list) {
                if (!(candidate instanceof MdObject object)) {
                    continue;
                }
                String kind = object.eClass().getName();
                String name = safe(object.getName());
                String fqn = safeFqn(object, kind, name);
                if (matchesSourceRef(normalizedRef, kind, name, fqn)) {
                    return object;
                }
            }
        }
        return null;
    }

    private MdObject resolveByFqn(Configuration configuration, String fqn) {
        String[] parts = fqn != null ? fqn.split("\\.") : new String[0]; //$NON-NLS-1$
        if (parts.length < 2) {
            return null;
        }
        MdObject current = findTopLevel(configuration, parts[0], parts[1]);
        if (current == null) {
            return null;
        }
        for (int i = 2; i < parts.length; i += 2) {
            if (i + 1 >= parts.length) {
                return null;
            }
            current = findNestedChild(current, parts[i], parts[i + 1]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private MdObject findTopLevel(Configuration configuration, String type, String name) {
        String normalized = normalize(type);
        List<? extends MdObject> topLevel = switch (normalized) {
            case "catalog", "справочник" -> configuration.getCatalogs(); //$NON-NLS-1$ //$NON-NLS-2$
            case "document", "документ" -> configuration.getDocuments(); //$NON-NLS-1$ //$NON-NLS-2$
            case "informationregister", "регистрсведений" -> configuration.getInformationRegisters(); //$NON-NLS-1$ //$NON-NLS-2$
            case "accumulationregister", "регистрнакопления" -> configuration.getAccumulationRegisters(); //$NON-NLS-1$ //$NON-NLS-2$
            case "accountingregister", "регистрбухгалтерии" -> configuration.getAccountingRegisters(); //$NON-NLS-1$ //$NON-NLS-2$
            case "calculationregister", "регистррасчета", "регистррасчёта" -> configuration.getCalculationRegisters(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "commonmodule", "общиймодуль" -> configuration.getCommonModules(); //$NON-NLS-1$ //$NON-NLS-2$
            case "enum", "перечисление" -> configuration.getEnums(); //$NON-NLS-1$ //$NON-NLS-2$
            case "report", "отчет", "отчёт" -> configuration.getReports(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "dataprocessor", "обработка" -> configuration.getDataProcessors(); //$NON-NLS-1$ //$NON-NLS-2$
            case "constant", "константа" -> configuration.getConstants(); //$NON-NLS-1$ //$NON-NLS-2$
            case "subsystem", "подсистема" -> configuration.getSubsystems(); //$NON-NLS-1$ //$NON-NLS-2$
            case "role", "роль" -> configuration.getRoles(); //$NON-NLS-1$ //$NON-NLS-2$
            case "commoncommand", "общаякоманда" -> configuration.getCommonCommands(); //$NON-NLS-1$ //$NON-NLS-2$
            case "commonform", "общаяформа" -> configuration.getCommonForms(); //$NON-NLS-1$ //$NON-NLS-2$
            case "commontemplate", "общиймокет", "общиймакет" -> configuration.getCommonTemplates(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "commonpicture", "общаякартинка" -> configuration.getCommonPictures(); //$NON-NLS-1$ //$NON-NLS-2$
            case "scheduledjob", "регламентноезадание" -> configuration.getScheduledJobs(); //$NON-NLS-1$ //$NON-NLS-2$
            case "definedtype", "определяемыйтип" -> configuration.getDefinedTypes(); //$NON-NLS-1$ //$NON-NLS-2$
            case "documentjournal", "журналдокументов" -> configuration.getDocumentJournals(); //$NON-NLS-1$ //$NON-NLS-2$
            case "documentnumerator", "нумератордокументов" -> configuration.getDocumentNumerators(); //$NON-NLS-1$ //$NON-NLS-2$
            case "chartofaccounts", "плансчетов" -> configuration.getChartsOfAccounts(); //$NON-NLS-1$ //$NON-NLS-2$
            case "chartofcharacteristictypes", "планвидовхарактеристик" -> configuration.getChartsOfCharacteristicTypes(); //$NON-NLS-1$ //$NON-NLS-2$
            case "chartofcalculationtypes", "планвидоврасчета", "планвидоврасчёта" -> configuration.getChartsOfCalculationTypes(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "exchangeplan", "планобмена" -> configuration.getExchangePlans(); //$NON-NLS-1$ //$NON-NLS-2$
            case "businessprocess", "бизнеспроцесс" -> configuration.getBusinessProcesses(); //$NON-NLS-1$ //$NON-NLS-2$
            case "task", "задача" -> configuration.getTasks(); //$NON-NLS-1$ //$NON-NLS-2$
            case "sequence", "последовательность" -> configuration.getSequences(); //$NON-NLS-1$ //$NON-NLS-2$
            case "functionaloption", "функциональнаяопция" -> configuration.getFunctionalOptions(); //$NON-NLS-1$ //$NON-NLS-2$
            case "functionaloptionsparameter", "параметрфункциональныхопций" -> configuration.getFunctionalOptionsParameters(); //$NON-NLS-1$ //$NON-NLS-2$
            case "sessionparameter", "параметрсеанса" -> configuration.getSessionParameters(); //$NON-NLS-1$ //$NON-NLS-2$
            case "settingsstorage", "хранилищенастроек" -> configuration.getSettingsStorages(); //$NON-NLS-1$ //$NON-NLS-2$
            case "xdtopackage", "xdtoпакет" -> configuration.getXDTOPackages(); //$NON-NLS-1$ //$NON-NLS-2$
            case "wsreference", "webсервис" -> configuration.getWsReferences(); //$NON-NLS-1$ //$NON-NLS-2$
            case "webservice", "webсервиспубликация" -> configuration.getWebServices(); //$NON-NLS-1$ //$NON-NLS-2$
            case "httpservice", "httpсервис" -> configuration.getHttpServices(); //$NON-NLS-1$ //$NON-NLS-2$
            case "integrationservice", "сервисинтеграции" -> configuration.getIntegrationServices(); //$NON-NLS-1$ //$NON-NLS-2$
            case "externdatasource", "externaldatasource", "внешнийисточникданных" -> configuration.getExternalDataSources(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "eventsubscription", "подписканасобытие" -> configuration.getEventSubscriptions(); //$NON-NLS-1$ //$NON-NLS-2$
            default -> Collections.emptyList();
        };
        for (MdObject object : topLevel) {
            if (object != null && name.equalsIgnoreCase(safe(object.getName()))) {
                return object;
            }
        }
        return null;
    }

    private MdObject findNestedChild(MdObject parent, String marker, String childName) {
        String normalizedMarker = normalize(marker);
        for (EStructuralFeature feature : parent.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference reference) || !reference.isContainment() || !reference.isMany()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Collection<Object> values = (Collection<Object>) parent.eGet(feature);
            if (values == null) {
                continue;
            }
            for (Object value : values) {
                if (!(value instanceof MdObject child)) {
                    continue;
                }
                if (!childName.equalsIgnoreCase(safe(child.getName()))) {
                    continue;
                }
                if (matchesMarker(normalizedMarker, feature.getName(), child.eClass().getName())) {
                    return child;
                }
            }
        }
        return null;
    }

    private boolean matchesMarker(String marker, String featureName, String className) {
        if (marker == null || marker.isBlank()) {
            return true;
        }
        String normalizedFeature = normalize(featureName);
        String normalizedClass = normalize(className);
        String singularFeature = singularize(normalizedFeature);
        String shortClass = singularize(normalizedClass);
        return marker.equals(normalizedFeature)
                || marker.equals(singularFeature)
                || marker.equals(normalizedClass)
                || marker.equals(shortClass);
    }

    private String singularize(String token) {
        if (token == null || token.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        if (token.endsWith("ies")) { //$NON-NLS-1$
            return token.substring(0, token.length() - 3) + "y"; //$NON-NLS-1$
        }
        if (token.endsWith("s") && token.length() > 1) { //$NON-NLS-1$
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private MdObject findByKindAndName(Configuration configuration, String rawKind, String rawName) {
        String normalizedKind = normalize(rawKind);
        String normalizedName = normalize(rawName);
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
            for (Object candidate : list) {
                if (!(candidate instanceof MdObject object)) {
                    continue;
                }
                String kind = normalize(object.eClass().getName());
                String name = normalize(safe(object.getName()));
                if (normalizedKind.equals(kind) && normalizedName.equals(name)) {
                    return object;
                }
            }
        }
        return null;
    }

    private boolean matchesSourceRef(String normalizedRef, String kind, String name, String fqn) {
        if (normalizedRef.equals(normalize(fqn))) {
            return true;
        }
        return normalizedRef.equals(normalize(kind + "." + name)); //$NON-NLS-1$
    }

    private MdObject castMdObject(EObject object) {
        if (object instanceof MdObject mdObject) {
            return mdObject;
        }
        return null;
    }

    private String resolveEffectiveBaseProjectName(IExtensionProject extensionProject, String requestedBaseProject) {
        String effectiveBaseProjectName = requestedBaseProject;
        IProject extensionParent = extensionProject.getParentProject();
        if (effectiveBaseProjectName == null && extensionParent != null) {
            effectiveBaseProjectName = extensionParent.getName();
        }
        if (effectiveBaseProjectName == null || effectiveBaseProjectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Cannot resolve base project for extension: " + extensionProject.getProject().getName(),
                    false); //$NON-NLS-1$
        }
        if (extensionParent != null && !extensionParent.getName().equalsIgnoreCase(effectiveBaseProjectName)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "base_project does not match extension parent project", false); //$NON-NLS-1$
        }
        return effectiveBaseProjectName;
    }

    private String resolveSynonym(EMap<String, String> map) {
        if (map == null || map.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        String ru = map.get("ru"); //$NON-NLS-1$
        if (ru != null && !ru.isBlank()) {
            return ru;
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String value = entry.getValue();
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return ""; //$NON-NLS-1$
    }

    private String safeFqn(EObject object, String kind, String name) {
        if (object instanceof IBmObject bmObject) {
            String fqn = BmObjectHelper.safeTopFqn(bmObject);
            if (!fqn.isBlank()) {
                return fqn;
            }
        }
        return kind + "." + name; //$NON-NLS-1$
    }

    private <T> T executeWrite(IProject project, PlatformTransactionTask<T> task) {
        try {
            return gateway.getGlobalEditingContext().execute(
                    "CodePilot1C.ExtensionWrite", //$NON-NLS-1$
                    project,
                    this,
                    task::execute);
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Extension transaction failed: " + e.getMessage(), false, e); //$NON-NLS-1$
        }
    }

    @FunctionalInterface
    private interface PlatformTransactionTask<T> {
        T execute(IBmPlatformTransaction transaction);
    }

    private static final class Holder<T> {
        private T value;
    }

    private String safe(String value) {
        return value != null ? value : ""; //$NON-NLS-1$
    }

    private String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
