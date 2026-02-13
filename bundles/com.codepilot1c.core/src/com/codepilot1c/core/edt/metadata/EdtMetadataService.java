package com.codepilot1c.core.edt.metadata;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.BmDeadlockDetectedException;
import com._1c.g5.v8.bm.core.BmLockWaitTimeoutException;
import com._1c.g5.v8.bm.core.BmNameAlreadyInUseException;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Service for EDT BM metadata creation.
 */
public class EdtMetadataService {

    private static final String RU_LANGUAGE = "ru"; //$NON-NLS-1$

    private final EdtMetadataGateway gateway;
    private final MetadataProjectReadinessChecker readinessChecker;

    public EdtMetadataService() {
        this(new EdtMetadataGateway());
    }

    public EdtMetadataService(EdtMetadataGateway gateway) {
        this.gateway = gateway;
        this.readinessChecker = new MetadataProjectReadinessChecker(gateway);
    }

    public boolean isEdtAvailable() {
        return gateway.isEdtAvailable();
    }

    public MetadataOperationResult createMetadata(CreateMetadataRequest request) {
        request.validate();
        IProject project = requireProject(request.projectName());
        readinessChecker.ensureReady(project);

        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve project configuration", false); //$NON-NLS-1$
        }

        String fqn = request.kind().getFqnPrefix() + "." + request.name(); //$NON-NLS-1$

        executeWrite(project, transaction -> {
            Configuration txConfiguration = transaction.toTransactionObject(configuration);
            if (txConfiguration == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot access configuration in BM transaction", false); //$NON-NLS-1$
            }

            if (existsTopLevel(txConfiguration, request.kind(), request.name())) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_ALREADY_EXISTS,
                        "Metadata object already exists: " + fqn, false); //$NON-NLS-1$
            }

            MdObject object = createTopLevelObject(request.kind());
            setCommonProperties(object, request.name(), request.synonym(), request.comment());
            addTopLevelObject(txConfiguration, request.kind(), object);
            return null;
        });

        return new MetadataOperationResult(
                true,
                request.projectName(),
                request.kind().name(),
                request.name(),
                fqn,
                "Metadata object created successfully"); //$NON-NLS-1$
    }

    public MetadataOperationResult addMetadataChild(AddMetadataChildRequest request) {
        request.validate();
        IProject project = requireProject(request.projectName());
        readinessChecker.ensureReady(project);

        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve project configuration", false); //$NON-NLS-1$
        }

        String childFqn = executeWrite(project, transaction -> {
            Configuration txConfiguration = transaction.toTransactionObject(configuration);
            if (txConfiguration == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot access configuration in BM transaction", false); //$NON-NLS-1$
            }
            return createGenericChild(txConfiguration, request);
        });

        return new MetadataOperationResult(
                true,
                request.projectName(),
                request.childKind().name(),
                request.name(),
                childFqn,
                "Metadata child object created successfully"); //$NON-NLS-1$
    }

    private String createGenericChild(Configuration configuration, AddMetadataChildRequest request) {
        MdObject parent = resolveByFqn(configuration, request.parentFqn());
        if (parent == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_PARENT_NOT_FOUND,
                    "Parent not found: " + request.parentFqn(), false); //$NON-NLS-1$
        }

        MdObject child = createChildByFactory(parent, request.childKind());
        setCommonProperties(child, request.name(), request.synonym(), request.comment());

        addChildToParent(parent, child, request.childKind());
        addChildrenBatch(parent, request.childKind(), request.properties());
        return request.parentFqn() + "." + request.childKind().getDisplayName() + "." + request.name(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private MdObject resolveByFqn(Configuration configuration, String fqn) {
        String[] parts = fqn != null ? fqn.split("\\.") : new String[0]; //$NON-NLS-1$
        if (parts.length < 2) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_PARENT_NOT_FOUND,
                    "Parent FQN must be <Type>.<Name>[.<Marker>.<Name>...]", false); //$NON-NLS-1$
        }

        MdObject current = findTopLevel(configuration, parts[0], parts[1]);
        if (current == null) {
            return null;
        }
        for (int i = 2; i < parts.length; i += 2) {
            if (i + 1 >= parts.length) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_PARENT_NOT_FOUND,
                        "Nested FQN segments must be marker/name pairs: " + fqn, false); //$NON-NLS-1$
            }
            String marker = parts[i];
            String name = parts[i + 1];
            current = findNestedChild(current, marker, name);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private MdObject findTopLevel(Configuration configuration, String type, String name) {
        String normalized = normalizeToken(type);
        List<? extends MdObject> topLevel = switch (normalized) {
            case "catalog", "справочник" -> configuration.getCatalogs(); //$NON-NLS-1$ //$NON-NLS-2$
            case "document", "документ" -> configuration.getDocuments(); //$NON-NLS-1$ //$NON-NLS-2$
            case "informationregister", "регистрсведений" -> configuration.getInformationRegisters(); //$NON-NLS-1$ //$NON-NLS-2$
            case "accumulationregister", "регистрнакопления" -> configuration.getAccumulationRegisters(); //$NON-NLS-1$ //$NON-NLS-2$
            case "commonmodule", "общиймодуль" -> configuration.getCommonModules(); //$NON-NLS-1$ //$NON-NLS-2$
            case "enum", "перечисление" -> configuration.getEnums(); //$NON-NLS-1$ //$NON-NLS-2$
            case "report", "отчет", "отчёт" -> configuration.getReports(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "dataprocessor", "обработка" -> configuration.getDataProcessors(); //$NON-NLS-1$ //$NON-NLS-2$
            case "constant", "константа" -> configuration.getConstants(); //$NON-NLS-1$ //$NON-NLS-2$
            default -> Collections.emptyList();
        };
        for (MdObject object : topLevel) {
            if (name.equalsIgnoreCase(object.getName())) {
                return object;
            }
        }
        return null;
    }

    private MdObject findNestedChild(MdObject parent, String marker, String childName) {
        String normalizedMarker = normalizeToken(marker);
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
                if (!childName.equalsIgnoreCase(child.getName())) {
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
        String normalizedFeature = normalizeToken(featureName);
        String singularFeature = singularize(normalizedFeature);
        String normalizedClass = normalizeToken(className);
        String shortClass = normalizeToken(extractShortClassMarker(className));
        return marker.equals(normalizedFeature)
                || marker.equals(singularFeature)
                || marker.equals(normalizedClass)
                || marker.equals(shortClass);
    }

    private String extractShortClassMarker(String className) {
        String normalized = className != null ? className : ""; //$NON-NLS-1$
        String[] tails = {
                "Attribute", "TabularSection", "Command", "Form", "Template", "Dimension", "Resource", "Requisite" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        };
        for (String tail : tails) {
            if (normalized.endsWith(tail)) {
                return tail;
            }
        }
        return normalized;
    }

    private MdObject createChildByFactory(MdObject parent, MetadataChildKind kind) {
        String childSuffix = kind.getDisplayName();
        List<String> candidates = new ArrayList<>();
        candidates.add("create" + parent.eClass().getName() + childSuffix); //$NON-NLS-1$

        String shortParent = parent.eClass().getName();
        int nestedPos = indexOfNestedSuffix(shortParent, kind);
        if (nestedPos > 0) {
            candidates.add("create" + shortParent.substring(nestedPos) + childSuffix); //$NON-NLS-1$
        }
        candidates.add("create" + childSuffix); //$NON-NLS-1$

        for (String methodName : candidates) {
            MdObject created = invokeFactory(methodName);
            if (created != null) {
                return created;
            }
        }

        throw new MetadataOperationException(
                MetadataOperationCode.INVALID_METADATA_KIND,
                "Cannot create child kind " + kind + " for parent " + parent.eClass().getName(), false); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private int indexOfNestedSuffix(String name, MetadataChildKind kind) {
        String[] suffixes = {"TabularSection", "Attribute", "Command", "Form", "Template", "Dimension", "Resource", "Requisite"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        String own = kind.getDisplayName();
        for (String suffix : suffixes) {
            if (!suffix.equals(own)) {
                int idx = name.indexOf(suffix);
                if (idx > 0) {
                    return idx;
                }
            }
        }
        return -1;
    }

    private MdObject invokeFactory(String methodName) {
        try {
            Method method = MdClassFactory.class.getMethod(methodName);
            Object result = method.invoke(MdClassFactory.eINSTANCE);
            if (result instanceof MdObject object) {
                return object;
            }
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to invoke factory method " + methodName + ": " + e.getMessage(), false, e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void addChildToParent(MdObject parent, MdObject child, MetadataChildKind kind) {
        EReference reference = resolveTargetReference(parent, child, kind);
        if (reference == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Parent " + parent.eClass().getName() + " does not support child " + kind, false); //$NON-NLS-1$ //$NON-NLS-2$
        }
        @SuppressWarnings("unchecked")
        List<MdObject> container = (List<MdObject>) parent.eGet(reference);
        if (containsMdObjectName(container, child.getName())) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_ALREADY_EXISTS,
                    "Child already exists: " + child.getName(), false); //$NON-NLS-1$
        }
        container.add(child);
    }

    private EReference resolveTargetReference(MdObject parent, MdObject child, MetadataChildKind kind) {
        String normalizedKind = normalizeToken(kind.getDisplayName());
        for (EStructuralFeature feature : parent.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference reference) || !reference.isContainment() || !reference.isMany()) {
                continue;
            }
            String featureName = normalizeToken(reference.getName());
            String singular = singularize(featureName);
            if (!normalizedKind.equals(featureName) && !normalizedKind.equals(singular)) {
                continue;
            }
            if (reference.getEReferenceType().isSuperTypeOf(child.eClass())) {
                return reference;
            }
        }

        for (EStructuralFeature feature : parent.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference reference) || !reference.isContainment() || !reference.isMany()) {
                continue;
            }
            if (reference.getEReferenceType().isSuperTypeOf(child.eClass())) {
                return reference;
            }
        }
        return null;
    }

    private void addChildrenBatch(MdObject parent, MetadataChildKind kind, Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        Object rawChildren = properties.get("children"); //$NON-NLS-1$
        if (rawChildren == null && kind == MetadataChildKind.ATTRIBUTE) {
            rawChildren = properties.get("attributes"); //$NON-NLS-1$
        }
        if (!(rawChildren instanceof List<?> entries)) {
            return;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> rawMap)) {
                continue;
            }
            String name = asString(rawMap.get("name")); //$NON-NLS-1$
            if (!MetadataNameValidator.isValidName(name)) {
                continue;
            }
            MdObject child = createChildByFactory(parent, kind);
            setCommonProperties(child, name, asString(rawMap.get("synonym")), asString(rawMap.get("comment"))); //$NON-NLS-1$ //$NON-NLS-2$
            try {
                addChildToParent(parent, child, kind);
            } catch (MetadataOperationException e) {
                if (e.getCode() != MetadataOperationCode.METADATA_ALREADY_EXISTS) {
                    throw e;
                }
            }
        }
    }

    private String asString(Object value) {
        return value instanceof String str && !str.isBlank() ? str : null;
    }

    private String normalizeToken(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                .toLowerCase(Locale.ROOT);
    }

    private String singularize(String value) {
        if (value.endsWith("ies")) { //$NON-NLS-1$
            return value.substring(0, value.length() - 3) + "y"; //$NON-NLS-1$
        }
        if (value.endsWith("es")) { //$NON-NLS-1$
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("s")) { //$NON-NLS-1$
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private MdObject createTopLevelObject(MetadataKind kind) {
        return switch (kind) {
            case CATALOG -> MdClassFactory.eINSTANCE.createCatalog();
            case DOCUMENT -> MdClassFactory.eINSTANCE.createDocument();
            case INFORMATION_REGISTER -> MdClassFactory.eINSTANCE.createInformationRegister();
            case ACCUMULATION_REGISTER -> MdClassFactory.eINSTANCE.createAccumulationRegister();
            case COMMON_MODULE -> MdClassFactory.eINSTANCE.createCommonModule();
            case ENUM -> MdClassFactory.eINSTANCE.createEnum();
            case REPORT -> MdClassFactory.eINSTANCE.createReport();
            case DATA_PROCESSOR -> MdClassFactory.eINSTANCE.createDataProcessor();
            case CONSTANT -> MdClassFactory.eINSTANCE.createConstant();
        };
    }

    private void addTopLevelObject(Configuration configuration, MetadataKind kind, MdObject object) {
        switch (kind) {
            case CATALOG -> configuration.getCatalogs().add((com._1c.g5.v8.dt.metadata.mdclass.Catalog) object);
            case DOCUMENT -> configuration.getDocuments().add((Document) object);
            case INFORMATION_REGISTER ->
                    configuration.getInformationRegisters().add(
                            (com._1c.g5.v8.dt.metadata.mdclass.InformationRegister) object);
            case ACCUMULATION_REGISTER ->
                    configuration.getAccumulationRegisters().add(
                            (com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister) object);
            case COMMON_MODULE ->
                    configuration.getCommonModules().add((com._1c.g5.v8.dt.metadata.mdclass.CommonModule) object);
            case ENUM -> configuration.getEnums().add((com._1c.g5.v8.dt.metadata.mdclass.Enum) object);
            case REPORT -> configuration.getReports().add((com._1c.g5.v8.dt.metadata.mdclass.Report) object);
            case DATA_PROCESSOR -> configuration.getDataProcessors().add((DataProcessor) object);
            case CONSTANT -> configuration.getConstants().add((com._1c.g5.v8.dt.metadata.mdclass.Constant) object);
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Unsupported metadata kind: " + kind, false); //$NON-NLS-1$
        }
    }

    private boolean existsTopLevel(Configuration configuration, MetadataKind kind, String name) {
        return switch (kind) {
            case CATALOG -> containsMdObjectName(configuration.getCatalogs(), name);
            case DOCUMENT -> containsMdObjectName(configuration.getDocuments(), name);
            case INFORMATION_REGISTER -> containsMdObjectName(configuration.getInformationRegisters(), name);
            case ACCUMULATION_REGISTER -> containsMdObjectName(configuration.getAccumulationRegisters(), name);
            case COMMON_MODULE -> containsMdObjectName(configuration.getCommonModules(), name);
            case ENUM -> containsMdObjectName(configuration.getEnums(), name);
            case REPORT -> containsMdObjectName(configuration.getReports(), name);
            case DATA_PROCESSOR -> containsMdObjectName(configuration.getDataProcessors(), name);
            case CONSTANT -> containsMdObjectName(configuration.getConstants(), name);
        };
    }

    private boolean containsMdObjectName(List<? extends MdObject> objects, String name) {
        for (MdObject object : objects) {
            if (name.equalsIgnoreCase(object.getName())) {
                return true;
            }
        }
        return false;
    }

    private void setCommonProperties(MdObject object, String name, String synonym, String comment) {
        object.setName(name);
        if (comment != null && !comment.isBlank()) {
            object.setComment(comment);
        }
        if (synonym != null && !synonym.isBlank()) {
            EMap<String, String> synonymMap = object.getSynonym();
            if (synonymMap != null) {
                synonymMap.put(RU_LANGUAGE, synonym);
            }
        }
    }

    private IProject requireProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Project not found: " + projectName, false); //$NON-NLS-1$
        }
        return project;
    }

    private <T> T executeWrite(IProject project, TransactionTask<T> task) {
        IBmModelManager modelManager = gateway.getBmModelManager();
        int maxAttempts = 3;
        Throwable lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return modelManager.executeReadWriteTask(project, task::execute);
            } catch (BmDeadlockDetectedException | BmLockWaitTimeoutException e) {
                lastError = e;
                if (attempt >= maxAttempts) {
                    break;
                }
            } catch (BmNameAlreadyInUseException e) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_ALREADY_EXISTS,
                        e.getMessage(), false, e);
            } catch (MetadataOperationException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Metadata transaction failed: " + e.getMessage(), false, e); //$NON-NLS-1$
            }
        }
        throw new MetadataOperationException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "Metadata transaction failed after retries: " + (lastError != null ? lastError.getMessage() : ""), //$NON-NLS-1$ //$NON-NLS-2$
                true,
                lastError);
    }

    @FunctionalInterface
    private interface TransactionTask<T> {
        T execute(IBmTransaction transaction);
    }
}
