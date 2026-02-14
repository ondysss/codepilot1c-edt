package com.codepilot1c.core.edt.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.BmDeadlockDetectedException;
import com._1c.g5.v8.bm.core.BmLockWaitTimeoutException;
import com._1c.g5.v8.bm.core.BmNameAlreadyInUseException;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmPlatformGlobalEditingContext;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.FormType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Service for EDT BM metadata creation.
 */
public class EdtMetadataService {

    private static final String RU_LANGUAGE = "ru"; //$NON-NLS-1$
    private static final long CONFIG_SERIALIZATION_WAIT_MS = 30_000L;
    private static final long CONFIG_SERIALIZATION_POLL_MS = 500L;
    private static final long EXPORT_DERIVED_WAIT_MS = Long.getLong("codepilot1c.edt.export.wait.ms", 120_000L); //$NON-NLS-1$
    private static final String EXPORT_SEGMENT_OBJECTS = "EXP_O"; //$NON-NLS-1$
    private static final String EXPORT_SEGMENT_BLOBS = "EXP_B"; //$NON-NLS-1$
    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtMetadataService.class);
    private static final Map<String, String> ATTRIBUTE_NAME_ALIASES = createAttributeNameAliases();
    private static final Map<String, Set<String>> RESERVED_ATTRIBUTE_FALLBACK = createReservedAttributeFallback();

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
        String opId = LogSanitizer.newId("edt-create"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        LOG.info("[%s] createMetadata START project=%s kind=%s name=%s", // $NON-NLS-1$
                opId, request.projectName(), request.kind(), request.name());
        request.validate();
        gateway.ensureMutationRuntimeAvailable();
        IProject project = requireProject(request.projectName());
        readinessChecker.ensureReady(project);
        LOG.debug("[%s] Project is ready: %s", opId, project.getName()); //$NON-NLS-1$
        repairConfigurationMissingUuids(project, opId);

        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null) {
            LOG.error("[%s] Configuration is null for project=%s", opId, request.projectName()); //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve project configuration", false); //$NON-NLS-1$
        }

        String fqn = request.kind().getFqnPrefix() + "." + request.name(); //$NON-NLS-1$
        LOG.debug("[%s] Target FQN: %s", opId, fqn); //$NON-NLS-1$

        executeWrite(project, transaction -> {
            LOG.debug("[%s] Transaction started for createMetadata", opId); //$NON-NLS-1$
            Configuration txConfiguration = transaction.toTransactionObject(configuration);
            if (txConfiguration == null) {
                LOG.error("[%s] Failed to map configuration into transaction", opId); //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot access configuration in BM transaction", false); //$NON-NLS-1$
            }

            if (existsTopLevel(txConfiguration, request.kind(), request.name())) {
                LOG.warn("[%s] Metadata already exists: %s", opId, fqn); //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_ALREADY_EXISTS,
                        "Metadata object already exists: " + fqn, false); //$NON-NLS-1$
            }

            MdObject object = createTopLevelObject(request.kind());
            LOG.debug("[%s] Created object instance: %s", opId, object.eClass().getName()); //$NON-NLS-1$
            setCommonProperties(object, request.name(), request.synonym(), request.comment());
            MdObject txObject = attachTopLevelObject(transaction, project, object, fqn);
            LOG.debug("[%s] Attached top object by FQN=%s", opId, fqn); //$NON-NLS-1$
            ensureUuidsRecursively(txObject, opId, fqn);
            // Keep eager link for immediate in-memory visibility in EDT UI.
            addTopLevelObject(txConfiguration, request.kind(), txObject);
            LOG.debug("[%s] Eager linked object into Configuration collections", opId); //$NON-NLS-1$
            LOG.debug("[%s] Transaction steps completed for %s", opId, fqn); //$NON-NLS-1$
            return null;
        });
        rebindTopLevelIntoConfiguration(project, request.kind(), request.name(), fqn, opId);
        forceExportTopLevelObject(project, fqn, opId);
        verifyTopLevelPersisted(project, fqn, opId);
        verifyConfigurationEntryPersisted(project, request.kind(), fqn, opId);
        refreshProjectSafely(project);
        LOG.info("[%s] createMetadata SUCCESS in %s fqn=%s", opId, // $NON-NLS-1$
                LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                fqn);

        return new MetadataOperationResult(
                true,
                request.projectName(),
                request.kind().name(),
                request.name(),
                fqn,
                "Metadata object created successfully"); //$NON-NLS-1$
    }

    public MetadataOperationResult addMetadataChild(AddMetadataChildRequest request) {
        String opId = LogSanitizer.newId("edt-child"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        LOG.info("[%s] addMetadataChild START project=%s parent=%s kind=%s name=%s", // $NON-NLS-1$
                opId, request.projectName(), request.parentFqn(), request.childKind(), request.name());
        request.validate();
        gateway.ensureMutationRuntimeAvailable();
        IProject project = requireProject(request.projectName());
        readinessChecker.ensureReady(project);
        LOG.debug("[%s] Project is ready: %s", opId, project.getName()); //$NON-NLS-1$
        repairConfigurationMissingUuids(project, opId);

        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null) {
            LOG.error("[%s] Configuration is null for project=%s", opId, request.projectName()); //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve project configuration", false); //$NON-NLS-1$
        }

        String childFqn = executeWrite(project, transaction -> {
            LOG.debug("[%s] Transaction started for addMetadataChild", opId); //$NON-NLS-1$
            Configuration txConfiguration = transaction.toTransactionObject(configuration);
            if (txConfiguration == null) {
                LOG.error("[%s] Failed to map configuration into transaction", opId); //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot access configuration in BM transaction", false); //$NON-NLS-1$
            }
            return createGenericChild(txConfiguration, request);
        });
        verifyObjectPersisted(project, childFqn, opId);
        LOG.info("[%s] addMetadataChild SUCCESS in %s fqn=%s", opId, // $NON-NLS-1$
                LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                childFqn);

        return new MetadataOperationResult(
                true,
                request.projectName(),
                request.childKind().name(),
                request.name(),
                childFqn,
                "Metadata child object created successfully"); //$NON-NLS-1$
    }

    private String createGenericChild(Configuration configuration, AddMetadataChildRequest request) {
        LOG.debug("createGenericChild parent=%s childKind=%s name=%s", // $NON-NLS-1$
                request.parentFqn(), request.childKind(), request.name());
        MdObject parent = resolveByFqn(configuration, request.parentFqn());
        if (parent == null) {
            LOG.warn("createGenericChild parent not found: %s", request.parentFqn()); //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_PARENT_NOT_FOUND,
                    "Parent not found: " + request.parentFqn(), false); //$NON-NLS-1$
        }
        LOG.debug("createGenericChild resolved parent class=%s name=%s", // $NON-NLS-1$
                parent.eClass().getName(), parent.getName());
        validateReservedChildName(parent, request.childKind(), request.name());

        MdObject child = request.childKind() == MetadataChildKind.FORM
                ? createFormByParent(parent)
                : createChildByFactory(parent, request.childKind());
        LOG.debug("createGenericChild created child class=%s", child.eClass().getName()); //$NON-NLS-1$
        setCommonProperties(child, request.name(), request.synonym(), request.comment());
        initializeFormIfNeeded(child);
        ensureUuidsRecursively(child, "child", request.parentFqn()); //$NON-NLS-1$

        addChildToParent(parent, child, request.childKind());
        addChildrenBatch(parent, request.childKind(), request.properties());
        return request.parentFqn() + "." + request.childKind().getDisplayName() + "." + request.name(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private MdObject createFormByParent(MdObject parent) {
        String parentClass = parent.eClass().getName();
        return switch (parentClass) {
            case "Catalog" -> MdClassFactory.eINSTANCE.createCatalogForm(); //$NON-NLS-1$
            case "Document" -> MdClassFactory.eINSTANCE.createDocumentForm(); //$NON-NLS-1$
            case "InformationRegister" -> MdClassFactory.eINSTANCE.createInformationRegisterForm(); //$NON-NLS-1$
            case "AccumulationRegister" -> MdClassFactory.eINSTANCE.createAccumulationRegisterForm(); //$NON-NLS-1$
            case "Report" -> MdClassFactory.eINSTANCE.createReportForm(); //$NON-NLS-1$
            case "DataProcessor" -> MdClassFactory.eINSTANCE.createDataProcessorForm(); //$NON-NLS-1$
            case "Enum" -> MdClassFactory.eINSTANCE.createEnumForm(); //$NON-NLS-1$
            case "Task" -> MdClassFactory.eINSTANCE.createTaskForm(); //$NON-NLS-1$
            case "BusinessProcess" -> MdClassFactory.eINSTANCE.createBusinessProcessForm(); //$NON-NLS-1$
            case "ChartOfAccounts" -> MdClassFactory.eINSTANCE.createChartOfAccountsForm(); //$NON-NLS-1$
            case "ChartOfCalculationTypes" -> MdClassFactory.eINSTANCE.createChartOfCalculationTypesForm(); //$NON-NLS-1$
            case "ChartOfCharacteristicTypes" -> MdClassFactory.eINSTANCE.createChartOfCharacteristicTypesForm(); //$NON-NLS-1$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Unsupported parent for Form: " + parentClass, false); //$NON-NLS-1$
        };
    }

    private void initializeFormIfNeeded(MdObject child) {
        if (!(child instanceof BasicForm basicForm)) {
            return;
        }
        if (basicForm.getFormType() == null) {
            basicForm.setFormType(FormType.MANAGED);
        }
    }

    private MdObject resolveByFqn(Configuration configuration, String fqn) {
        LOG.debug("resolveByFqn: %s", fqn); //$NON-NLS-1$
        String[] parts = fqn != null ? fqn.split("\\.") : new String[0]; //$NON-NLS-1$
        if (parts.length < 2) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_PARENT_NOT_FOUND,
                    "Parent FQN must be <Type>.<Name>[.<Marker>.<Name>...]", false); //$NON-NLS-1$
        }

        MdObject current = findTopLevel(configuration, parts[0], parts[1]);
        LOG.debug("resolveByFqn top-level type=%s name=%s found=%s", // $NON-NLS-1$
                parts[0], parts[1], current != null);
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
            LOG.debug("resolveByFqn nested marker=%s name=%s found=%s", marker, name, current != null); //$NON-NLS-1$
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
            LOG.debug("Trying MdClassFactory method: %s", methodName); //$NON-NLS-1$
            MdObject created = invokeFactory(methodName);
            if (created != null) {
                LOG.debug("Factory method resolved: %s -> %s", methodName, created.eClass().getName()); //$NON-NLS-1$
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
            LOG.error("No containment reference for parent=%s child=%s kind=%s", // $NON-NLS-1$
                    parent.eClass().getName(), child.eClass().getName(), kind);
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Parent " + parent.eClass().getName() + " does not support child " + kind, false); //$NON-NLS-1$ //$NON-NLS-2$
        }
        @SuppressWarnings("unchecked")
        List<MdObject> container = (List<MdObject>) parent.eGet(reference);
        if (containsMdObjectName(container, child.getName())) {
            LOG.warn("Child already exists under reference=%s childName=%s", reference.getName(), child.getName()); //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_ALREADY_EXISTS,
                    "Child already exists: " + child.getName(), false); //$NON-NLS-1$
        }
        LOG.debug("Adding child to reference=%s parent=%s child=%s", // $NON-NLS-1$
                reference.getName(), parent.getName(), child.getName());
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
            validateReservedChildName(parent, kind, name);
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

    private void validateReservedChildName(MdObject parent, MetadataChildKind kind, String childName) {
        if (kind != MetadataChildKind.ATTRIBUTE || childName == null || childName.isBlank() || parent == null) {
            return;
        }

        Set<String> reserved = collectReservedAttributeNames(parent);
        if (reserved.isEmpty()) {
            return;
        }

        String normalizedInput = normalizeToken(childName);
        String canonicalInput = ATTRIBUTE_NAME_ALIASES.getOrDefault(normalizedInput, normalizedInput);
        if (!reserved.contains(canonicalInput)) {
            return;
        }

        LOG.warn("Reserved attribute name blocked: parentClass=%s parentName=%s name=%s canonical=%s", // $NON-NLS-1$
                parent.eClass().getName(), parent.getName(), childName, canonicalInput);
        String suggested = buildSafeAttributeName(childName);
        throw new MetadataOperationException(
                MetadataOperationCode.INVALID_METADATA_NAME,
                "Attribute name is reserved for " + parent.eClass().getName() + ": " + childName //$NON-NLS-1$ //$NON-NLS-2$
                        + ". Use a different name, for example: " + suggested, //$NON-NLS-1$
                false);
    }

    private String buildSafeAttributeName(String sourceName) {
        String base = sourceName != null ? sourceName.trim() : ""; //$NON-NLS-1$
        if (base.isEmpty()) {
            return "РеквизитПользовательский"; //$NON-NLS-1$
        }
        if (base.matches("^[A-Za-z0-9_]+$")) { //$NON-NLS-1$
            return base + "Custom"; //$NON-NLS-1$
        }
        return base + "Пользовательский"; //$NON-NLS-1$
    }

    private Set<String> collectReservedAttributeNames(MdObject parent) {
        Set<String> reserved = new HashSet<>();
        EStructuralFeature stdFeature = parent.eClass().getEStructuralFeature("standardAttributes"); //$NON-NLS-1$
        if (stdFeature != null) {
            Object stdValue = parent.eGet(stdFeature);
            if (stdValue instanceof Collection<?> stdCollection) {
                for (Object item : stdCollection) {
                    if (!(item instanceof EObject stdAttr)) {
                        continue;
                    }
                    EStructuralFeature nameFeature = stdAttr.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
                    if (nameFeature == null) {
                        continue;
                    }
                    Object rawName = stdAttr.eGet(nameFeature);
                    if (rawName instanceof String name && !name.isBlank()) {
                        reserved.add(normalizeToken(name));
                    }
                }
            }
        }

        if (!reserved.isEmpty()) {
            return reserved;
        }

        String parentClass = parent.eClass().getName();
        Set<String> fallback = RESERVED_ATTRIBUTE_FALLBACK.get(parentClass);
        if (fallback != null) {
            reserved.addAll(fallback);
        }
        if (parentClass.endsWith("TabularSection")) { //$NON-NLS-1$
            reserved.add(normalizeToken("LineNumber")); //$NON-NLS-1$
        }
        return reserved;
    }

    private static Map<String, String> createAttributeNameAliases() {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("наименование", "description"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("код", "code"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("родитель", "parent"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("владелец", "owner"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("этогруппа", "isfolder"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("пометкаудаления", "deletionmark"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("ссылка", "ref"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("предопределенный", "predefined"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("имяпредопределенныхданных", "predefineddataname"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("номер", "number"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("дата", "date"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("проведен", "posted"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("проведён", "posted"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("период", "period"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("регистратор", "recorder"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("активность", "active"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("виддвижения", "recordtype"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("номерстроки", "linenumber"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("видрасчета", "calculationtype"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("периоддействия", "actionperiod"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("началопериодадействия", "begofactionperiod"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("конецпериодадействия", "endofactionperiod"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("началобазовогопериода", "begofbaseperiod"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("конецбазовогопериода", "endofbaseperiod"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("периодрегистрации", "registrationperiod"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("сторнирующаязапись", "reversingentry"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("завершен", "completed"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("завершён", "completed"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("головнаязадача", "headtask"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("стартована", "started"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("выполнена", "executed"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("бизнеспроцесс", "businessprocess"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("точкамаршрута", "routepoint"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("тип", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("типзначения", "valuetype"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("базовыйпериоддействия", "actionperiodisbasic"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("датаобмена", "exchangedate"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("полученныйномер", "receivedno"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("отправленныйномер", "sentno"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("этотузел", "thisnode"); //$NON-NLS-1$ //$NON-NLS-2$
        aliases.put("порядок", "order"); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableMap(aliases);
    }

    private static Map<String, Set<String>> createReservedAttributeFallback() {
        Map<String, Set<String>> reserved = new HashMap<>();
        reserved.put("Catalog", setOfNormalized( //$NON-NLS-1$
                "Code", "Description", "Parent", "Owner", "IsFolder", "DeletionMark", "Ref", "Predefined", "PredefinedDataName", "LineNumber")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
        reserved.put("Document", setOfNormalized( //$NON-NLS-1$
                "Number", "Date", "Posted", "DeletionMark", "Ref", "LineNumber")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        reserved.put("InformationRegister", setOfNormalized( //$NON-NLS-1$
                "Period", "Recorder", "Active", "LineNumber")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        reserved.put("AccumulationRegister", setOfNormalized( //$NON-NLS-1$
                "Period", "Recorder", "RecordType", "Active", "LineNumber")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        reserved.put("CalculationRegister", setOfNormalized( //$NON-NLS-1$
                "ActionPeriod", "Active", "BegOfActionPeriod", "BegOfBasePeriod", "CalculationType",
                "EndOfActionPeriod", "EndOfBasePeriod", "LineNumber", "Recorder", "RegistrationPeriod",
                "ReversingEntry")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$
        reserved.put("BusinessProcess", setOfNormalized( //$NON-NLS-1$
                "Completed", "Date", "DeletionMark", "HeadTask", "LineNumber", "Number", "Ref", "Started")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        reserved.put("Task", setOfNormalized( //$NON-NLS-1$
                "BusinessProcess", "Date", "DeletionMark", "Description", "Executed", "Number", "Ref", "RoutePoint")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        reserved.put("ChartOfCharacteristicTypes", setOfNormalized( //$NON-NLS-1$
                "Code", "DeletionMark", "Description", "IsFolder", "LineNumber", "Parent", "Predefined",
                "PredefinedDataName", "Ref", "ValueType")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
        reserved.put("ChartOfCalculationTypes", setOfNormalized( //$NON-NLS-1$
                "ActionPeriodIsBasic", "CalculationType", "Code", "DeletionMark", "Description", "LineNumber",
                "Predefined", "PredefinedDataName", "Ref")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
        reserved.put("ExchangePlan", setOfNormalized( //$NON-NLS-1$
                "Code", "DeletionMark", "Description", "ExchangeDate", "LineNumber", "ReceivedNo", "Ref",
                "SentNo", "ThisNode")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
        reserved.put("Enum", setOfNormalized("Order", "Ref")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        reserved.put("DataProcessor", setOfNormalized("LineNumber")); //$NON-NLS-1$ //$NON-NLS-2$
        reserved.put("DocumentJournal", setOfNormalized( //$NON-NLS-1$
                "Date", "DeletionMark", "Number", "Posted", "Ref", "Type")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        return Collections.unmodifiableMap(reserved);
    }

    private static Set<String> setOfNormalized(String... values) {
        Set<String> result = new HashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                result.add(value
                        .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                        .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                        .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                        .toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(result);
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
        // In EDT model, top-level typed collections may be backed by generic content.
        // First, ensure generic content link exists.
        addMdObjectIfMissing(configuration.getContent(), object);

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

    private void addMdObjectIfMissing(List<? extends MdObject> container, MdObject object) {
        if (container == null || object == null) {
            return;
        }
        if (!containsMdObjectName(container, object.getName())) {
            @SuppressWarnings("unchecked")
            List<MdObject> mutable = (List<MdObject>) container;
            mutable.add(object);
        }
    }

    private MdObject attachTopLevelObject(
            IBmPlatformTransaction transaction,
            IProject project,
            MdObject object,
            String fqn
    ) {
        if (!(object instanceof IBmObject bmObject)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Created object is not BM object in transaction: " + object.eClass().getName(), false); //$NON-NLS-1$
        }
        IBmNamespace namespace = gateway.getBmModelManager().getBmNamespace(project);
        if (namespace == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve BM namespace for project: " + project.getName(), false); //$NON-NLS-1$
        }
        transaction.attachTopObject(namespace, bmObject, fqn);
        Object attached = transaction.getTopObjectByFqn(namespace, fqn);
        if (!(attached instanceof MdObject txObject)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Cannot resolve attached object in transaction by FQN: " + fqn, false); //$NON-NLS-1$
        }
        return txObject;
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
        if (object.getUuid() == null) {
            object.setUuid(UUID.randomUUID());
        }
        LOG.debug("setCommonProperties class=%s name=%s synonym=%s commentLength=%s", // $NON-NLS-1$
                object.eClass().getName(),
                name,
                synonym != null ? LogSanitizer.truncate(synonym, 80) : "null", //$NON-NLS-1$
                comment != null ? comment.length() : 0);
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

    private void repairConfigurationMissingUuids(IProject project, String opId) {
        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve project configuration for uuid repair: " + project.getName(), false); //$NON-NLS-1$
        }

        Integer repaired = executeWrite(project, transaction -> {
            Configuration txConfiguration = transaction.toTransactionObject(configuration);
            if (txConfiguration == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot access configuration in BM transaction during uuid repair", false); //$NON-NLS-1$
            }

            int fixed = 0;
            fixed += ensureUuidsForCollection(txConfiguration.getCatalogs(), opId, "repair.catalogs"); //$NON-NLS-1$
            fixed += ensureUuidsForCollection(txConfiguration.getDocuments(), opId, "repair.documents"); //$NON-NLS-1$
            fixed += ensureUuidsForCollection(txConfiguration.getInformationRegisters(), opId, "repair.infoRegisters"); //$NON-NLS-1$
            fixed += ensureUuidsForCollection(txConfiguration.getAccumulationRegisters(), opId, "repair.accRegisters"); //$NON-NLS-1$
            fixed += ensureUuidsForCollection(txConfiguration.getCommonModules(), opId, "repair.commonModules"); //$NON-NLS-1$
            fixed += ensureUuidsForCollection(txConfiguration.getEnums(), opId, "repair.enums"); //$NON-NLS-1$
            fixed += ensureUuidsForCollection(txConfiguration.getReports(), opId, "repair.reports"); //$NON-NLS-1$
            fixed += ensureUuidsForCollection(txConfiguration.getDataProcessors(), opId, "repair.dataProcessors"); //$NON-NLS-1$
            fixed += ensureUuidsForCollection(txConfiguration.getConstants(), opId, "repair.constants"); //$NON-NLS-1$
            return Integer.valueOf(fixed);
        });

        if (repaired != null && repaired.intValue() > 0) {
            LOG.warn("[%s] UUID repair fixed %d metadata objects with missing uuid", opId, repaired.intValue()); //$NON-NLS-1$
            forceExportTopLevelObject(project, "Configuration", opId); //$NON-NLS-1$
            refreshProjectSafely(project);
        }
    }

    private int ensureUuidsForCollection(List<? extends MdObject> objects, String opId, String context) {
        if (objects == null || objects.isEmpty()) {
            return 0;
        }
        int fixed = 0;
        for (MdObject object : objects) {
            fixed += ensureUuidsRecursively(object, opId, context);
        }
        return fixed;
    }

    private int ensureUuidsRecursively(MdObject root, String opId, String context) {
        if (root == null) {
            return 0;
        }
        int fixed = 0;
        Set<EObject> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (visited.add(root)) {
            fixed += assignUuidIfMissing(root, opId, context);
        }
        TreeIterator<EObject> iterator = root.eAllContents();
        while (iterator.hasNext()) {
            EObject current = iterator.next();
            if (!(current instanceof MdObject mdObject)) {
                continue;
            }
            if (!visited.add(mdObject)) {
                continue;
            }
            fixed += assignUuidIfMissing(mdObject, opId, context);
        }
        return fixed;
    }

    private int assignUuidIfMissing(MdObject object, String opId, String context) {
        if (object == null || object.getUuid() != null) {
            return 0;
        }
        object.setUuid(UUID.randomUUID());
        LOG.warn("[%s] Fixed missing uuid for class=%s name=%s context=%s", opId, //$NON-NLS-1$
                object.eClass().getName(), object.getName(), context);
        return 1;
    }

    private void verifyTopLevelPersisted(IProject project, String fqn, String opId) {
        boolean exists = executeRead(project, tx -> tx.getTopObjectByFqn(fqn) != null);
        if (!exists) {
            LOG.error("[%s] Post-verify failed: top-level object not found by FQN=%s", opId, fqn); //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Metadata created in transaction but not found after commit: " + fqn, true); //$NON-NLS-1$
        }
        LOG.debug("[%s] Post-verify passed for top-level FQN=%s", opId, fqn); //$NON-NLS-1$
    }

    private void rebindTopLevelIntoConfiguration(
            IProject project,
            MetadataKind kind,
            String objectName,
            String fqn,
            String opId
    ) {
        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve project configuration for relink: " + project.getName(), false); //$NON-NLS-1$
        }

        executeWrite(project, transaction -> {
            Configuration txConfiguration = transaction.toTransactionObject(configuration);
            if (txConfiguration == null) {
                throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot access configuration in BM transaction during relink", false); //$NON-NLS-1$
            }

            IBmNamespace namespace = gateway.getBmModelManager().getBmNamespace(project);
            Object top = transaction.getTopObjectByFqn(namespace, fqn);
            if (!(top instanceof MdObject txObject)) {
                throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Cannot resolve top object by FQN during relink: " + fqn, true); //$NON-NLS-1$
            }
            removeTopLevelObjectLinks(txConfiguration, kind, objectName);
            addTopLevelObject(txConfiguration, kind, txObject);
            return null;
        });

        boolean linkedAfterRelink = executeRead(project, tx -> {
            Configuration txConfiguration = tx.toTransactionObject(configuration);
            return txConfiguration != null && existsTopLevel(txConfiguration, kind, objectName);
        });
        if (!linkedAfterRelink) {
            throw new MetadataOperationException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "Top-level object exists in BM but cannot be linked into Configuration: " + fqn, true); //$NON-NLS-1$
        }
        LOG.info("[%s] Top-level object (re)linked into Configuration: %s", opId, fqn); //$NON-NLS-1$
    }

    private void verifyConfigurationEntryPersisted(IProject project, MetadataKind kind, String fqn, String opId) {
        IFile configFile = project.getFile("src/Configuration/Configuration.mdo"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + CONFIG_SERIALIZATION_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            refreshFileSafely(configFile);
            if (hasConfigurationEntry(configFile, kind, fqn)) {
                LOG.debug("[%s] Configuration serialization verified in %s for %s", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt), fqn);
                return;
            }
            try {
                Thread.sleep(CONFIG_SERIALIZATION_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Interrupted while waiting configuration serialization for " + fqn, true, e); //$NON-NLS-1$
            }
        }

        // Last attempt with explicit full refresh and direct disk read.
        refreshProjectSafely(project);
        if (hasConfigurationEntry(configFile, kind, fqn)) {
            LOG.debug("[%s] Configuration serialization verified after full refresh for %s", opId, fqn); //$NON-NLS-1$
            return;
        }

        // BM commit is authoritative for the operation result; XML serialization may lag behind.
        LOG.warn("[%s] Configuration serialization is delayed for FQN=%s in file=%s. " // $NON-NLS-1$
                + "BM object exists, operation treated as successful.", //$NON-NLS-1$
                opId, fqn, configFile.getFullPath());
    }

    private void forceExportTopLevelObject(IProject project, String fqn, String opId) {
        IBmModelManager modelManager = gateway.getBmModelManager();
        IDtProjectManager projectManager = gateway.getDtProjectManager();
        IDtProject dtProject = projectManager.getDtProject(project);
        if (dtProject == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve DT project for force export: " + project.getName(), false); //$NON-NLS-1$
        }

        List<String> targets = buildExportTargets(fqn);
        boolean exported = false;
        try {
            exported = modelManager.forceExport(dtProject, targets);
        } catch (RuntimeException e) {
            LOG.warn("[%s] forceExport(List) failed for %s: %s", opId, targets, e.getMessage()); //$NON-NLS-1$
        }
        if (!exported) {
            try {
                exported = modelManager.forceExport(dtProject, fqn);
            } catch (RuntimeException e) {
                LOG.warn("[%s] forceExport(String) failed for %s: %s", opId, fqn, e.getMessage()); //$NON-NLS-1$
            }
        }
        if (!exported) {
            try {
                exported = modelManager.forceExport(dtProject, "Configuration"); //$NON-NLS-1$
            } catch (RuntimeException e) {
                LOG.warn("[%s] forceExport(String) failed for Configuration: %s", opId, e.getMessage()); //$NON-NLS-1$
            }
        }
        if (!exported) {
            throw new MetadataOperationException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "forceExport did not schedule export tasks for " + fqn, true); //$NON-NLS-1$
        }

        LOG.debug("[%s] forceExport targets=%s result=%s", opId, targets, exported); //$NON-NLS-1$
        waitExportDerivedData(dtProject, opId, fqn);
        flushDerivedDataPipeline(dtProject, opId, fqn);
        modelManager.waitModelSynchronization(project);
        LOG.debug("[%s] waitModelSynchronization completed for project=%s", opId, project.getName()); //$NON-NLS-1$
    }

    private List<String> buildExportTargets(String fqn) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        if (fqn != null && !fqn.isBlank()) {
            targets.add(fqn);
        }
        targets.add("Configuration"); //$NON-NLS-1$
        return List.copyOf(targets);
    }

    private void waitExportDerivedData(IDtProject dtProject, String opId, String fqn) {
        IDerivedDataManager ddManager = gateway.getDerivedDataManagerProvider().get(dtProject);
        if (ddManager == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve derived-data manager for project: " + dtProject.getName(), false); //$NON-NLS-1$
        }
        try {
            boolean done = ddManager.waitComputation(
                    EXPORT_DERIVED_WAIT_MS,
                    true,
                    EXPORT_SEGMENT_OBJECTS,
                    EXPORT_SEGMENT_BLOBS);
            LOG.debug("[%s] waitComputation(EXP_O,EXP_B) for %s: %s", opId, fqn, done); //$NON-NLS-1$
            if (!done) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Timed out waiting export derived-data for " + fqn + " in " + EXPORT_DERIVED_WAIT_MS + "ms", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Interrupted while waiting export derived-data for " + fqn, true, e); //$NON-NLS-1$
        }
    }

    private void flushDerivedDataPipeline(IDtProject dtProject, String opId, String fqn) {
        IDerivedDataManager ddManager = gateway.getDerivedDataManagerProvider().get(dtProject);
        if (ddManager == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve derived-data manager for project: " + dtProject.getName(), false); //$NON-NLS-1$
        }

        try {
            ddManager.applyForcedUpdates();
            LOG.debug("[%s] applyForcedUpdates completed for %s", opId, fqn); //$NON-NLS-1$

            boolean allDone = ddManager.waitAllComputations(EXPORT_DERIVED_WAIT_MS);
            LOG.debug("[%s] waitAllComputations for %s: %s", opId, fqn, allDone); //$NON-NLS-1$
            if (!allDone) {
                LOG.warn("[%s] waitAllComputations timed out for %s in %dms", opId, fqn, EXPORT_DERIVED_WAIT_MS); //$NON-NLS-1$
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Interrupted while flushing derived-data pipeline for " + fqn, true, e); //$NON-NLS-1$
        }
    }

    private void refreshFileSafely(IFile file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            file.refreshLocal(IResource.DEPTH_ZERO, null);
        } catch (CoreException e) {
            LOG.warn("refreshFileSafely failed for %s: %s", file.getFullPath(), e.getMessage()); //$NON-NLS-1$
        }
    }

    private void refreshProjectSafely(IProject project) {
        if (project == null || !project.exists()) {
            return;
        }
        try {
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
        } catch (CoreException e) {
            LOG.warn("refreshProjectSafely failed for %s: %s", project.getName(), e.getMessage()); //$NON-NLS-1$
        }
    }

    private String configurationTag(MetadataKind kind) {
        return switch (kind) {
            case CATALOG -> "catalogs"; //$NON-NLS-1$
            case DOCUMENT -> "documents"; //$NON-NLS-1$
            case INFORMATION_REGISTER -> "informationRegisters"; //$NON-NLS-1$
            case ACCUMULATION_REGISTER -> "accumulationRegisters"; //$NON-NLS-1$
            case COMMON_MODULE -> "commonModules"; //$NON-NLS-1$
            case ENUM -> "enums"; //$NON-NLS-1$
            case REPORT -> "reports"; //$NON-NLS-1$
            case DATA_PROCESSOR -> "dataProcessors"; //$NON-NLS-1$
            case CONSTANT -> "constants"; //$NON-NLS-1$
        };
    }

    private String readFileSafely(IFile file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try (InputStream in = file.getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (CoreException | IOException e) {
            LOG.warn("readFileSafely failed for %s: %s", file.getFullPath(), e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private boolean hasConfigurationEntry(IFile configFile, MetadataKind kind, String fqn) {
        String content = readFileSafely(configFile);
        if (containsConfigurationEntry(content, kind, fqn)) {
            return true;
        }
        String diskContent = readFileFromDiskSafely(configFile);
        return containsConfigurationEntry(diskContent, kind, fqn);
    }

    private boolean containsConfigurationEntry(String content, MetadataKind kind, String fqn) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String tagName = configurationTag(kind);
        String expectedEntry = "<" + tagName + ">" + fqn + "</" + tagName + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (content.contains(expectedEntry)) {
            return true;
        }
        return content.toLowerCase(Locale.ROOT).contains(expectedEntry.toLowerCase(Locale.ROOT));
    }

    private String readFileFromDiskSafely(IFile file) {
        if (file == null) {
            return null;
        }
        try {
            if (file.getLocation() == null) {
                return null;
            }
            Path path = file.getLocation().toFile().toPath();
            if (!Files.exists(path)) {
                return null;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("readFileFromDiskSafely failed for %s: %s", file.getFullPath(), e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private void verifyObjectPersisted(IProject project, String fqn, String opId) {
        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        boolean exists = executeRead(project, tx -> {
            Configuration txConfiguration = tx.toTransactionObject(configuration);
            return txConfiguration != null && resolveByFqn(txConfiguration, fqn) != null;
        });
        if (!exists) {
            LOG.error("[%s] Post-verify failed: object not found by FQN=%s", opId, fqn); //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Metadata object not found after commit: " + fqn, true); //$NON-NLS-1$
        }
        LOG.debug("[%s] Post-verify passed for FQN=%s", opId, fqn); //$NON-NLS-1$
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

    private <T> T executeWrite(IProject project, PlatformTransactionTask<T> task) {
        IBmPlatformGlobalEditingContext editingContext = gateway.getGlobalEditingContext();
        int maxAttempts = 3;
        Throwable lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long startedAt = System.currentTimeMillis();
            LOG.debug("executeWrite(project=%s) attempt=%d/%d START", project.getName(), attempt, maxAttempts); //$NON-NLS-1$
            try {
                T result = editingContext.execute(
                        "CodePilot1C.MetadataWrite", //$NON-NLS-1$
                        project,
                        this,
                        task::execute);
                LOG.debug("executeWrite(project=%s) attempt=%d SUCCESS in %s", // $NON-NLS-1$
                        project.getName(), attempt, LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt));
                return result;
            } catch (BmDeadlockDetectedException | BmLockWaitTimeoutException e) {
                lastError = e;
                LOG.warn("executeWrite(project=%s) attempt=%d retryable error: %s", // $NON-NLS-1$
                        project.getName(), attempt, e.getMessage());
                if (attempt >= maxAttempts) {
                    break;
                }
            } catch (BmNameAlreadyInUseException e) {
                LOG.warn("executeWrite(project=%s) name already in use: %s", project.getName(), e.getMessage()); //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_ALREADY_EXISTS,
                        e.getMessage(), false, e);
            } catch (MetadataOperationException e) {
                LOG.warn("executeWrite(project=%s) business error: %s (%s)", // $NON-NLS-1$
                        project.getName(), e.getMessage(), e.getCode());
                throw e;
            } catch (RuntimeException e) {
                LOG.error("executeWrite(project=%s) runtime failure: %s", project.getName(), e.getMessage()); //$NON-NLS-1$
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

    private <T> T executeRead(IProject project, ReadTransactionTask<T> task) {
        IBmModelManager modelManager = gateway.getBmModelManager();
        try {
            return modelManager.executeReadOnlyTask(project, task::execute);
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Metadata read transaction failed: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
    }

    @FunctionalInterface
    private interface PlatformTransactionTask<T> {
        T execute(IBmPlatformTransaction transaction);
    }

    @FunctionalInterface
    private interface ReadTransactionTask<T> {
        T execute(IBmTransaction transaction);
    }

    private void removeTopLevelObjectLinks(Configuration configuration, MetadataKind kind, String name) {
        removeByName(configuration.getContent(), name);
        switch (kind) {
            case CATALOG -> removeByName(configuration.getCatalogs(), name);
            case DOCUMENT -> removeByName(configuration.getDocuments(), name);
            case INFORMATION_REGISTER -> removeByName(configuration.getInformationRegisters(), name);
            case ACCUMULATION_REGISTER -> removeByName(configuration.getAccumulationRegisters(), name);
            case COMMON_MODULE -> removeByName(configuration.getCommonModules(), name);
            case ENUM -> removeByName(configuration.getEnums(), name);
            case REPORT -> removeByName(configuration.getReports(), name);
            case DATA_PROCESSOR -> removeByName(configuration.getDataProcessors(), name);
            case CONSTANT -> removeByName(configuration.getConstants(), name);
        }
    }

    private void removeByName(List<? extends MdObject> objects, String name) {
        if (objects == null || name == null || name.isBlank()) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<MdObject> mutable = (List<MdObject>) objects;
        mutable.removeIf(existing -> existing != null && name.equalsIgnoreCase(existing.getName()));
    }
}
