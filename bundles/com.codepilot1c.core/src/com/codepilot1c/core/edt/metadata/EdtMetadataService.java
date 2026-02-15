package com.codepilot1c.core.edt.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
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
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.FormType;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeDescriptionInfoWithTypeInfo;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeInfo;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeProviderService;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.codepilot1c.core.edt.forms.CreateFormRequest;
import com.codepilot1c.core.edt.forms.CreateFormResult;
import com.codepilot1c.core.edt.forms.FormOwnerStrategy;
import com.codepilot1c.core.edt.forms.FormUsage;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import org.osgi.framework.Bundle;

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
    private static final long FORM_MATERIALIZATION_POLL_MS =
            Long.getLong("codepilot1c.edt.form.materialization.poll.ms", 500L); //$NON-NLS-1$
    private static final String EN_LANGUAGE = "en"; //$NON-NLS-1$
    private static final String FORM_BUNDLE_ID = "com._1c.g5.v8.dt.form"; //$NON-NLS-1$
    private static final String PLATFORM_BUNDLE_ID = "com._1c.g5.v8.dt.platform"; //$NON-NLS-1$
    private static final String FORM_PLUGIN_CLASS = "com._1c.g5.v8.dt.internal.form.FormPlugin"; //$NON-NLS-1$
    private static final String FORM_GENERATOR_CLASS = "com._1c.g5.v8.dt.form.generator.IFormGenerator"; //$NON-NLS-1$
    private static final String FORM_FIELD_GENERATOR_CLASS = "com._1c.g5.v8.dt.form.generator.IFormFieldGenerator"; //$NON-NLS-1$
    private static final String FORM_FIELD_INFO_CLASS = "com._1c.g5.v8.dt.form.generator.FormFieldInfo"; //$NON-NLS-1$
    private static final String FORM_GENERATOR_TYPE_CLASS = "com._1c.g5.v8.dt.form.generator.FormType"; //$NON-NLS-1$
    private static final String VERSION_CLASS = "com._1c.g5.v8.dt.platform.version.Version"; //$NON-NLS-1$
    private static final String GUICE_INJECTOR_CLASS = "com.google.inject.Injector"; //$NON-NLS-1$
    private static final String DEFAULT_BASIC_FEATURE_TYPE = "String"; //$NON-NLS-1$
    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtMetadataService.class);
    private static final Map<String, String> ATTRIBUTE_NAME_ALIASES = createAttributeNameAliases();
    private static final Map<String, Set<String>> RESERVED_ATTRIBUTE_FALLBACK = createReservedAttributeFallback();

    private final EdtMetadataGateway gateway;
    private final MetadataProjectReadinessChecker readinessChecker;
    private final FormOwnerStrategy formOwnerStrategy;

    private record TypeSpec(
            String typeQuery,
            Integer stringLength,
            Boolean stringFixed,
            Integer numberPrecision,
            Integer numberScale,
            Boolean numberNonNegative,
            DateFractions dateFractions
    ) {
        static TypeSpec of(String typeQuery) {
            return new TypeSpec(typeQuery, null, null, null, null, null, null);
        }
    }

    public EdtMetadataService() {
        this(new EdtMetadataGateway());
    }

    public EdtMetadataService(EdtMetadataGateway gateway) {
        this.gateway = gateway;
        this.readinessChecker = new MetadataProjectReadinessChecker(gateway);
        this.formOwnerStrategy = FormOwnerStrategy.defaultStrategy();
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

    public CreateFormResult createForm(CreateFormRequest request) {
        String opId = LogSanitizer.newId("edt-form"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        request.validate();
        FormUsage effectiveUsage = resolveEffectiveFormUsage(request.ownerFqn(), request.name(), request.usage());
        String effectiveName = resolveEffectiveFormName(request.ownerFqn(), request.name(), effectiveUsage);
        boolean bindAsDefault = resolveDefaultBinding(request.setAsDefault(), effectiveUsage);
        LOG.info("[%s] createForm START project=%s owner=%s name=%s usage=%s setAsDefault=%s", // $NON-NLS-1$
                opId,
                request.projectName(),
                request.ownerFqn(),
                effectiveName,
                effectiveUsage,
                bindAsDefault);
        gateway.ensureMutationRuntimeAvailable();
        IProject project = requireProject(request.projectName());
        readinessChecker.ensureReady(project);
        repairConfigurationMissingUuids(project, opId);

        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve project configuration", false); //$NON-NLS-1$
        }

        String formFqn = request.ownerFqn() + ".Form." + effectiveName; //$NON-NLS-1$
        final FormUsage capturedUsage = effectiveUsage;
        final boolean capturedBindAsDefault = bindAsDefault;
        final String capturedName = effectiveName;

        executeWrite(project, transaction -> {
            Configuration txConfiguration = transaction.toTransactionObject(configuration);
            if (txConfiguration == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot access configuration in BM transaction", false); //$NON-NLS-1$
            }

            MdObject owner = resolveByFqn(txConfiguration, request.ownerFqn());
            if (owner == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_PARENT_NOT_FOUND,
                        "Owner not found: " + request.ownerFqn(), false); //$NON-NLS-1$
            }
            validateReservedChildName(owner, MetadataChildKind.FORM, capturedName);
            MdObject form = createFormByParent(owner);
            setCommonProperties(form, capturedName, request.synonym(), request.comment());
            initializeFormForRequest(form, request);
            ensureUuidsRecursively(form, opId, formFqn);
            try {
                addChildToParent(owner, form, MetadataChildKind.FORM);
            } catch (MetadataOperationException e) {
                if (e.getCode() == MetadataOperationCode.METADATA_ALREADY_EXISTS) {
                    throw new MetadataOperationException(
                            MetadataOperationCode.FORM_ALREADY_EXISTS,
                            "Form already exists: " + formFqn, false, e); //$NON-NLS-1$
                }
                throw e;
            }
            populateFormContent(project, transaction, owner, form, txConfiguration, capturedUsage, opId);
            ensureUuidsRecursively(form, opId, formFqn);
            if (capturedBindAsDefault) {
                bindDefaultForm(owner, form, capturedUsage, opId);
            }
            ensureUuidsRecursively(owner, opId, request.ownerFqn());
            LOG.debug("[%s] createForm transaction ownerClass=%s formClass=%s usage=%s bindDefault=%s", // $NON-NLS-1$
                    opId,
                    owner.eClass().getName(),
                    form.eClass().getName(),
                    capturedUsage,
                    capturedBindAsDefault);
            return null;
        });

        String topLevelFqn = extractTopLevelFqn(formFqn);
        forceExportTopLevelObject(project, topLevelFqn, opId);
        verifyObjectPersisted(project, formFqn, opId);

        FormArtifactPaths artifacts = waitForFormMaterialization(
                project,
                request.ownerFqn(),
                effectiveName,
                request.effectiveWaitMs(),
                opId);
        refreshProjectSafely(project);
        LOG.info("[%s] createForm SUCCESS in %s form=%s", opId, //$NON-NLS-1$
                LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                formFqn);

        return new CreateFormResult(
                request.ownerFqn(),
                formFqn,
                capturedUsage,
                capturedBindAsDefault,
                true,
                artifacts.formAbsolutePath(),
                artifacts.moduleAbsolutePath(),
                artifacts.diagnostics());
    }

    public MetadataOperationResult addMetadataChild(AddMetadataChildRequest request) {
        String opId = LogSanitizer.newId("edt-child"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        LOG.info("[%s] addMetadataChild START project=%s parent=%s kind=%s name=%s", // $NON-NLS-1$
                opId, request.projectName(), request.parentFqn(), request.childKind(), request.name());
        request.validate();
        if (request.childKind() == MetadataChildKind.FORM) {
            CreateFormRequest formRequest = createFormRequestFromAddChild(request);
            CreateFormResult formResult = createForm(formRequest);
            LOG.info("[%s] addMetadataChild FORM routed to createForm in %s fqn=%s", opId, //$NON-NLS-1$
                    LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                    formResult.formFqn());
            return formResult.toMetadataOperationResult(
                    request.projectName(),
                    extractNameFromFqn(formResult.formFqn()));
        }
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

        Map<String, TypeItem> preResolvedTypes = preResolveChildTypes(project, request);
        final Map<String, TypeItem> capturedTypes = preResolvedTypes;

        String childFqn = executeWrite(project, transaction -> {
            LOG.debug("[%s] Transaction started for addMetadataChild", opId); //$NON-NLS-1$
            Configuration txConfiguration = transaction.toTransactionObject(configuration);
            if (txConfiguration == null) {
                LOG.error("[%s] Failed to map configuration into transaction", opId); //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot access configuration in BM transaction", false); //$NON-NLS-1$
            }
            return createGenericChild(txConfiguration, request, transaction, capturedTypes);
        });
        verifyObjectPersisted(project, childFqn, opId);
        LOG.info("[%s] addMetadataChild SUCCESS in %s fqn=%s", opId, // $NON-NLS-1$
                LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                childFqn);

        return new MetadataOperationResult(
                true,
                request.projectName(),
                request.childKind().name(),
                extractNameFromFqn(childFqn),
                childFqn,
                "Metadata child object created successfully"); //$NON-NLS-1$
    }

    public MetadataOperationResult updateMetadata(UpdateMetadataRequest request) {
        String opId = LogSanitizer.newId("edt-update"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        LOG.info("[%s] updateMetadata START project=%s target=%s", // $NON-NLS-1$
                opId, request.projectName(), request.targetFqn());
        request.validate();
        gateway.ensureMutationRuntimeAvailable();
        IProject project = requireProject(request.projectName());
        readinessChecker.ensureReady(project);

        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve project configuration", false); //$NON-NLS-1$
        }

        // Pre-resolve all TypeItems from changes (top-level set and children_ops)
        Set<String> typeStrings = collectTypeStrings(request.changes());
        Map<String, TypeItem> preResolvedTypes = new HashMap<>();
        if (!typeStrings.isEmpty()) {
            executeRead(project, readTx -> {
                for (String typeString : typeStrings) {
                    TypeItem item = resolveTypeItem(typeString, readTx);
                    if (item == null && !isSimpleTypeQuery(typeString)) {
                        throw new MetadataOperationException(
                                MetadataOperationCode.INVALID_PROPERTY_VALUE,
                                "Type not found in BM: " + typeString, false); //$NON-NLS-1$
                    }
                    if (item != null) {
                        cacheResolvedTypeItem(preResolvedTypes, typeString, item);
                    }
                }
                return null;
            });
        }
        final Map<String, TypeItem> capturedTypes = preResolvedTypes;

        String targetFqn = executeWrite(project, transaction -> {
            Configuration txConfiguration = transaction.toTransactionObject(configuration);
            if (txConfiguration == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot access configuration in BM transaction", false); //$NON-NLS-1$
            }
            MdObject target = resolveByFqn(txConfiguration, request.targetFqn());
            if (target == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_NOT_FOUND,
                        "Metadata object not found: " + request.targetFqn(), false); //$NON-NLS-1$
            }
            applyObjectChanges(txConfiguration, target, request.changes(), request.targetFqn(),
                    transaction, capturedTypes);
            ensureUuidsRecursively(target, opId, request.targetFqn());
            return request.targetFqn();
        });

        String topLevelFqn = extractTopLevelFqn(targetFqn);
        forceExportTopLevelObject(project, topLevelFqn, opId);
        verifyObjectPersisted(project, targetFqn, opId);
        refreshProjectSafely(project);
        LOG.info("[%s] updateMetadata SUCCESS in %s target=%s", opId, // $NON-NLS-1$
                LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                targetFqn);

        return new MetadataOperationResult(
                true,
                request.projectName(),
                "UPDATE", //$NON-NLS-1$
                extractNameFromFqn(targetFqn),
                targetFqn,
                "Metadata object updated successfully"); //$NON-NLS-1$
    }

    public FieldTypeCandidatesResult listFieldTypeCandidates(FieldTypeCandidatesRequest request) {
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

        String fieldName = request.effectiveFieldName();
        int limit = request.effectiveLimit();
        return executeRead(project, tx -> {
            Configuration txConfiguration = tx.toTransactionObject(configuration);
            Configuration contextConfiguration = txConfiguration != null ? txConfiguration : configuration;
            MdObject target = resolveByFqn(contextConfiguration, request.targetFqn());
            if (target == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_NOT_FOUND,
                        "Metadata object not found: " + request.targetFqn(), false); //$NON-NLS-1$
            }
            EStructuralFeature feature = resolveFeatureIgnoreCase(target, fieldName);
            if (!(feature instanceof EReference typeReference)) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Field is not a reference: " + fieldName, false); //$NON-NLS-1$
            }

            LinkedHashMap<String, FieldTypeCandidate> unique = new LinkedHashMap<>();
            collectTypeCandidates(
                    unique,
                    TypeProviderService.INSTANCE.getTypeDescriptionInfoWithTypeInfo(
                            target,
                            contextConfiguration,
                            typeReference,
                            null));
            if (unique.isEmpty()) {
                collectTypeCandidates(
                        unique,
                        TypeProviderService.INSTANCE.getTypeDescriptionInfoWithTypeInfo(
                                target,
                                typeReference,
                                null));
            }

            List<FieldTypeCandidate> allCandidates = new ArrayList<>(unique.values());
            int total = allCandidates.size();
            if (allCandidates.size() > limit) {
                allCandidates = new ArrayList<>(allCandidates.subList(0, limit));
            }
            return new FieldTypeCandidatesResult(
                    request.projectName(),
                    request.targetFqn(),
                    fieldName,
                    total,
                    allCandidates);
        });
    }

    public MetadataOperationResult deleteMetadata(DeleteMetadataRequest request) {
        String opId = LogSanitizer.newId("edt-delete"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        LOG.info("[%s] deleteMetadata START project=%s target=%s recursive=%s", // $NON-NLS-1$
                opId, request.projectName(), request.targetFqn(), request.recursive());
        request.validate();
        gateway.ensureMutationRuntimeAvailable();
        IProject project = requireProject(request.projectName());
        readinessChecker.ensureReady(project);

        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve project configuration", false); //$NON-NLS-1$
        }

        String targetFqn = request.targetFqn();
        executeWrite(project, transaction -> {
            Configuration txConfiguration = transaction.toTransactionObject(configuration);
            if (txConfiguration == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Cannot access configuration in BM transaction", false); //$NON-NLS-1$
            }
            MdObject target = resolveByFqn(txConfiguration, targetFqn);
            if (target == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_NOT_FOUND,
                        "Metadata object not found: " + targetFqn, false); //$NON-NLS-1$
            }
            if (!request.recursive() && hasNestedMetadataChildren(target)) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_DELETE_CONFLICT,
                        "Metadata object has nested children. Use recursive=true: " + targetFqn, false); //$NON-NLS-1$
            }
            removeMetadataObject(txConfiguration, targetFqn, target);
            return null;
        });

        String topLevelFqn = extractTopLevelFqn(targetFqn);
        forceExportTopLevelObject(project, topLevelFqn, opId);
        verifyObjectRemoved(project, targetFqn, opId);
        refreshProjectSafely(project);
        LOG.info("[%s] deleteMetadata SUCCESS in %s target=%s", opId, // $NON-NLS-1$
                LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                targetFqn);

        return new MetadataOperationResult(
                true,
                request.projectName(),
                "DELETE", //$NON-NLS-1$
                extractNameFromFqn(targetFqn),
                targetFqn,
                "Metadata object deleted successfully"); //$NON-NLS-1$
    }

    public ModuleArtifactResult ensureModuleArtifact(EnsureModuleArtifactRequest request) {
        String opId = LogSanitizer.newId("edt-module"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        request.validate();
        LOG.info("[%s] ensureModuleArtifact START project=%s object=%s kind=%s create=%s", //$NON-NLS-1$
                opId, request.projectName(), request.objectFqn(), request.moduleKind(), request.createIfMissing());
        gateway.ensureMutationRuntimeAvailable();

        IProject project = requireProject(request.projectName());
        readinessChecker.ensureReady(project);

        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve project configuration", false); //$NON-NLS-1$
        }

        ModuleTarget target = executeRead(project, tx -> {
            Configuration txConfiguration = tx.toTransactionObject(configuration);
            if (txConfiguration == null) {
                return null;
            }
            MdObject resolved = resolveByFqn(txConfiguration, request.objectFqn());
            if (resolved == null) {
                return null;
            }
            URI uri = resolved.eResource() != null ? resolved.eResource().getURI() : null;
            String resourcePath = toProjectRelativePath(project, uri);
            return new ModuleTarget(
                    resolved.eClass().getName(),
                    resourcePath,
                    topKindFromFqn(request.objectFqn()),
                    topNameFromFqn(request.objectFqn()),
                    formNameFromFqn(request.objectFqn()));
        });
        if (target == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Metadata object not found: " + request.objectFqn(), false); //$NON-NLS-1$
        }
        if (target.formName() != null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Form module artifact is unavailable: forms are stored in owner .mdo in current EDT format: "
                            + request.objectFqn(),
                    false); //$NON-NLS-1$
        }

        List<String> candidates = buildModuleCandidates(target, request.moduleKind());
        if (candidates.isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Cannot resolve module path for object: " + request.objectFqn(), false); //$NON-NLS-1$
        }

        for (String candidate : candidates) {
            IFile existing = project.getFile(candidate);
            if (existing != null && existing.exists()) {
                String workspacePath = existing.getFullPath().toString();
                if (workspacePath.startsWith("/")) { //$NON-NLS-1$
                    workspacePath = workspacePath.substring(1);
                }
                LOG.info("[%s] ensureModuleArtifact SUCCESS (exists) in %s path=%s", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt), workspacePath);
                return new ModuleArtifactResult(
                        request.projectName(),
                        request.objectFqn(),
                        request.moduleKind(),
                        workspacePath,
                        false);
            }
        }

        if (!request.createIfMissing()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Module file not found for object: " + request.objectFqn(), true); //$NON-NLS-1$
        }

        IFile targetFile = project.getFile(candidates.get(0));
        try {
            createParentsIfMissing(targetFile);
        } catch (CoreException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to create module folders: " + candidates.get(0), true, e); //$NON-NLS-1$
        }
        String content = request.initialContent() != null ? request.initialContent() : ""; //$NON-NLS-1$
        try (ByteArrayInputStream source = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            if (targetFile.exists()) {
                targetFile.setContents(source, IResource.FORCE, null);
            } else {
                targetFile.create(source, IResource.FORCE, null);
            }
            targetFile.refreshLocal(IResource.DEPTH_ZERO, null);
        } catch (IOException | CoreException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to create module file: " + candidates.get(0), true, e); //$NON-NLS-1$
        }
        refreshProjectSafely(project);

        String workspacePath = targetFile.getFullPath().toString();
        if (workspacePath.startsWith("/")) { //$NON-NLS-1$
            workspacePath = workspacePath.substring(1);
        }
        LOG.info("[%s] ensureModuleArtifact SUCCESS (created) in %s path=%s", opId, //$NON-NLS-1$
                LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt), workspacePath);
        return new ModuleArtifactResult(
                request.projectName(),
                request.objectFqn(),
                request.moduleKind(),
                workspacePath,
                true);
    }

    private List<String> buildModuleCandidates(ModuleTarget target, ModuleArtifactKind requestedKind) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (target.formName() != null && target.topKind() != null && target.topName() != null) {
            String formsPath = "src/" + mapTopFolder(target.topKind()) + "/" + target.topName() + "/Forms/" + target.formName() + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            candidates.add(formsPath);
        }

        String resourcePath = target.resourcePath();
        if (resourcePath != null && !resourcePath.isBlank()) {
            String normalized = resourcePath.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            String dir = slash >= 0 ? normalized.substring(0, slash) : ""; //$NON-NLS-1$
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".form")) { //$NON-NLS-1$
                candidates.add(dir + "/Module.bsl"); //$NON-NLS-1$
            } else if (lower.endsWith(".mdo")) { //$NON-NLS-1$
                if (target.formName() != null) {
                    candidates.add(dir + "/Forms/" + target.formName() + "/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
                } else {
                    candidates.add(dir + "/" + moduleFileName(requestedKind, target.className())); //$NON-NLS-1$
                }
            }
            if (target.formName() == null) {
                candidates.add(dir + "/" + moduleFileName(requestedKind, target.className())); //$NON-NLS-1$
            }
        }

        if (target.topKind() != null && target.topName() != null) {
            String topPath = "src/" + mapTopFolder(target.topKind()) + "/" + target.topName() + "/" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + moduleFileName(requestedKind, target.className());
            candidates.add(topPath);
        }
        return List.copyOf(candidates);
    }

    private String moduleFileName(ModuleArtifactKind kind, String className) {
        if (kind == ModuleArtifactKind.MODULE) {
            return "Module.bsl"; //$NON-NLS-1$
        }
        if (kind == ModuleArtifactKind.MANAGER) {
            return "ManagerModule.bsl"; //$NON-NLS-1$
        }
        if (kind == ModuleArtifactKind.OBJECT) {
            return "ObjectModule.bsl"; //$NON-NLS-1$
        }
        if ("CommonModule".equals(className) || (className != null && className.contains("Form"))) { //$NON-NLS-1$ //$NON-NLS-2$
            return "Module.bsl"; //$NON-NLS-1$
        }
        return "ObjectModule.bsl"; //$NON-NLS-1$
    }

    private String mapTopFolder(String topKind) {
        return switch (normalizeToken(topKind)) {
            case "catalog", "catalogs" -> "Catalogs"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "document", "documents" -> "Documents"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "informationregister", "informationregisters" -> "InformationRegisters"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "accumulationregister", "accumulationregisters" -> "AccumulationRegisters"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "commonmodule", "commonmodules" -> "CommonModules"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "enum", "enums" -> "Enums"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "report", "reports" -> "Reports"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "dataprocessor", "dataprocessors" -> "DataProcessors"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "constant", "constants" -> "Constants"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Unsupported top-level metadata kind: " + topKind, false); //$NON-NLS-1$
        };
    }

    private String topKindFromFqn(String fqn) {
        String[] parts = fqn != null ? fqn.split("\\.") : new String[0]; //$NON-NLS-1$
        return parts.length > 0 ? parts[0] : null;
    }

    private String topNameFromFqn(String fqn) {
        String[] parts = fqn != null ? fqn.split("\\.") : new String[0]; //$NON-NLS-1$
        return parts.length > 1 ? parts[1] : null;
    }

    private String formNameFromFqn(String fqn) {
        String[] parts = fqn != null ? fqn.split("\\.") : new String[0]; //$NON-NLS-1$
        for (int i = 2; i + 1 < parts.length; i += 2) {
            if ("form".equalsIgnoreCase(parts[i])) { //$NON-NLS-1$
                return parts[i + 1];
            }
        }
        return null;
    }

    private CreateFormRequest createFormRequestFromAddChild(AddMetadataChildRequest request) {
        if (!request.hasSingleName()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Form creation via add_metadata_child requires a single form name", false); //$NON-NLS-1$
        }
        Map<String, Object> properties = request.properties() == null ? Map.of() : request.properties();
        String usageValue = firstNonBlank(
                asString(pickFirst(properties, "form_usage", "formUsage")), //$NON-NLS-1$ //$NON-NLS-2$
                asString(pickFirst(properties, "usage"))); //$NON-NLS-1$
        Boolean managed = parseBooleanProperty(properties, "managed"); //$NON-NLS-1$
        Boolean setAsDefault = parseBooleanProperty(properties, "set_as_default", "setAsDefault", "default"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Long waitMs = parseLongProperty(properties, "wait_ms", "waitMs"); //$NON-NLS-1$ //$NON-NLS-2$
        return new CreateFormRequest(
                request.projectName(),
                request.parentFqn(),
                request.name(),
                FormUsage.fromOptionalString(usageValue),
                managed,
                setAsDefault,
                request.synonym(),
                request.comment(),
                waitMs);
    }

    private FormUsage resolveEffectiveFormUsage(String ownerFqn, String requestedName, FormUsage requestedUsage) {
        if (requestedUsage != null) {
            return requestedUsage;
        }
        FormUsage fromName = detectUsageFromName(requestedName);
        if (fromName != null) {
            return fromName;
        }
        String ownerType = topKindFromFqn(ownerFqn);
        if (ownerType == null) {
            return FormUsage.AUXILIARY;
        }
        return switch (normalizeToken(ownerType)) {
            case "catalog", "document", "task", "businessprocess", "dataprocessor", "report" -> FormUsage.OBJECT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            case "enum", "informationregister", "accumulationregister", "accountingregister", "calculationregister" -> FormUsage.LIST; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            default -> FormUsage.AUXILIARY;
        };
    }

    private FormUsage detectUsageFromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = normalizeToken(name);
        if (normalized.contains("выбора") || normalized.contains("choice")) { //$NON-NLS-1$ //$NON-NLS-2$
            return FormUsage.CHOICE;
        }
        if (normalized.contains("списка") || normalized.contains("list")) { //$NON-NLS-1$ //$NON-NLS-2$
            return FormUsage.LIST;
        }
        if (normalized.contains("элемента") || normalized.contains("объекта") || normalized.contains("object")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return FormUsage.OBJECT;
        }
        return null;
    }

    private String resolveEffectiveFormName(String ownerFqn, String requestedName, FormUsage usage) {
        String trimmed = requestedName == null ? "" : requestedName.trim(); //$NON-NLS-1$
        String ownerType = topKindFromFqn(ownerFqn);
        if (trimmed.isBlank()) {
            return defaultFormName(ownerType, usage);
        }
        if (usage == FormUsage.OBJECT && isGenericObjectFormName(trimmed)) {
            if ("catalog".equals(normalizeToken(ownerType))) { //$NON-NLS-1$
                return "ФормаЭлемента"; //$NON-NLS-1$
            }
            if ("document".equals(normalizeToken(ownerType))) { //$NON-NLS-1$
                return "ФормаДокумента"; //$NON-NLS-1$
            }
        }
        return trimmed;
    }

    private String defaultFormName(String ownerType, FormUsage usage) {
        if (usage == FormUsage.LIST) {
            return "ФормаСписка"; //$NON-NLS-1$
        }
        if (usage == FormUsage.CHOICE) {
            return "ФормаВыбора"; //$NON-NLS-1$
        }
        if (usage == FormUsage.OBJECT) {
            if ("catalog".equals(normalizeToken(ownerType))) { //$NON-NLS-1$
                return "ФормаЭлемента"; //$NON-NLS-1$
            }
            if ("document".equals(normalizeToken(ownerType))) { //$NON-NLS-1$
                return "ФормаДокумента"; //$NON-NLS-1$
            }
            return "ФормаОбъекта"; //$NON-NLS-1$
        }
        return "Форма"; //$NON-NLS-1$
    }

    private boolean isGenericObjectFormName(String name) {
        String normalized = normalizeToken(name);
        return normalized.equals(normalizeToken("ФормаОбъекта")) //$NON-NLS-1$
                || normalized.equals(normalizeToken("ObjectForm")) //$NON-NLS-1$
                || normalized.equals(normalizeToken("Object")); //$NON-NLS-1$
    }

    private boolean resolveDefaultBinding(Boolean requestedSetAsDefault, FormUsage usage) {
        if (usage == FormUsage.AUXILIARY) {
            return false;
        }
        return requestedSetAsDefault == null || requestedSetAsDefault.booleanValue();
    }

    private FormArtifactPaths waitForFormMaterialization(
            IProject project,
            String ownerFqn,
            String formName,
            long waitMs,
            String opId
    ) {
        String topKind = topKindFromFqn(ownerFqn);
        String topName = topNameFromFqn(ownerFqn);
        if (topKind == null || topName == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_FORM_USAGE,
                    "Invalid owner FQN for form materialization: " + ownerFqn, false); //$NON-NLS-1$
        }

        String ownerBasePath = "src/" + mapTopFolder(topKind) + "/" + topName + "/"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        IFile ownerMdoFile = project.getFile(ownerBasePath + topName + ".mdo"); //$NON-NLS-1$
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + waitMs;

        while (System.currentTimeMillis() < deadline) {
            refreshFileSafely(ownerMdoFile);

            String ownerContent = readFileSafely(ownerMdoFile);
            if (ownerContent == null) {
                ownerContent = readFileFromDiskSafely(ownerMdoFile);
            }
            boolean formEntryInOwner = containsFormEntryInOwnerMdo(ownerContent, formName);
            if (formEntryInOwner) {
                String diagnostics = "materialized in " //$NON-NLS-1$
                        + LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt)
                        + ", storage=embedded-in-owner-mdo" //$NON-NLS-1$
                        + ", ownerMdo=" + toAbsolutePath(ownerMdoFile); //$NON-NLS-1$
                return new FormArtifactPaths(
                        toAbsolutePath(ownerMdoFile),
                        null,
                        diagnostics);
            }
            try {
                Thread.sleep(FORM_MATERIALIZATION_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "Interrupted while waiting form materialization for " + ownerFqn + ".Form." + formName, true, e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        String ownerContent = readFileSafely(ownerMdoFile);
        if (ownerContent == null) {
            ownerContent = readFileFromDiskSafely(ownerMdoFile);
        }
        boolean formEntryInOwner = containsFormEntryInOwnerMdo(ownerContent, formName);
        String diagnostics = "timeout=" + waitMs //$NON-NLS-1$
                + "ms, ownerMdo=" + toAbsolutePath(ownerMdoFile) //$NON-NLS-1$
                + ", ownerMdoExists=" + ownerMdoFile.exists() //$NON-NLS-1$
                + ", ownerHasFormEntry=" + formEntryInOwner; //$NON-NLS-1$
        throw new MetadataOperationException(
                MetadataOperationCode.FORM_MATERIALIZATION_TIMEOUT,
                "Form created in BM but artifacts are not materialized: " + diagnostics, true); //$NON-NLS-1$
    }

    private boolean containsFormEntryInOwnerMdo(String ownerContent, String formName) {
        if (ownerContent == null || ownerContent.isBlank() || formName == null || formName.isBlank()) {
            return false;
        }
        String lower = ownerContent.toLowerCase(Locale.ROOT);
        String expectedNameTag = "<name>" + formName.toLowerCase(Locale.ROOT) + "</name>"; //$NON-NLS-1$ //$NON-NLS-2$
        int fromIndex = 0;
        while (true) {
            int start = lower.indexOf("<forms", fromIndex); //$NON-NLS-1$
            if (start < 0) {
                return false;
            }
            int end = lower.indexOf("</forms>", start); //$NON-NLS-1$
            if (end < 0) {
                return false;
            }
            int endExclusive = end + "</forms>".length(); //$NON-NLS-1$
            String formsBlock = lower.substring(start, endExclusive);
            if (formsBlock.contains(expectedNameTag)) {
                return true;
            }
            fromIndex = endExclusive;
        }
    }

    private String toAbsolutePath(IFile file) {
        if (file == null) {
            return null;
        }
        if (file.getLocation() != null) {
            return file.getLocation().toOSString();
        }
        return file.getFullPath() != null ? file.getFullPath().toString() : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Object pickFirst(Map<String, Object> properties, String... keys) {
        if (properties == null || properties.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (properties.containsKey(key)) {
                return properties.get(key);
            }
        }
        return null;
    }

    private Boolean parseBooleanProperty(Map<String, Object> properties, String... keys) {
        Object raw = pickFirst(properties, keys);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value)) { //$NON-NLS-1$ //$NON-NLS-2$
            return Boolean.TRUE;
        }
        if ("false".equals(value) || "0".equals(value)) { //$NON-NLS-1$ //$NON-NLS-2$
            return Boolean.FALSE;
        }
        throw new MetadataOperationException(
                MetadataOperationCode.INVALID_PROPERTY_VALUE,
                "Invalid boolean value for " + String.join("/", keys) + ": " + raw, false); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Long parseLongProperty(Map<String, Object> properties, String... keys) {
        Object raw = pickFirst(properties, keys);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Invalid numeric value for " + String.join("/", keys) + ": " + raw, false); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private String toProjectRelativePath(IProject project, URI uri) {
        if (project == null || uri == null) {
            return null;
        }
        String platformPath = uri.toPlatformString(true);
        if (platformPath != null && !platformPath.isBlank()) {
            String normalized = platformPath.replace('\\', '/');
            String prefix = "/" + project.getName() + "/"; //$NON-NLS-1$ //$NON-NLS-2$
            if (normalized.startsWith(prefix)) {
                return normalized.substring(prefix.length());
            }
            return normalized.startsWith("/") ? normalized.substring(1) : normalized; //$NON-NLS-1$
        }
        String uriPath = uri.path();
        if (uriPath == null || uriPath.isBlank()) {
            return null;
        }
        String normalized = uriPath.replace('\\', '/');
        String prefix = "/" + project.getName() + "/"; //$NON-NLS-1$ //$NON-NLS-2$
        if (normalized.startsWith(prefix)) {
            return normalized.substring(prefix.length());
        }
        return normalized.startsWith("/") ? normalized.substring(1) : normalized; //$NON-NLS-1$
    }

    private void createParentsIfMissing(IFile file) throws CoreException {
        if (file == null) {
            return;
        }
        IContainer parent = file.getParent();
        if (parent instanceof IFolder folder) {
            createFolderChain(folder);
        }
    }

    private void createFolderChain(IFolder folder) throws CoreException {
        IContainer parent = folder.getParent();
        if (parent instanceof IFolder parentFolder && !parentFolder.exists()) {
            createFolderChain(parentFolder);
        }
        if (!folder.exists()) {
            folder.create(true, true, null);
        }
    }

    private String createGenericChild(
            Configuration configuration,
            AddMetadataChildRequest request,
            IBmPlatformTransaction transaction,
            Map<String, TypeItem> preResolvedTypes
    ) {
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

        MetadataChildKind effectiveKind = normalizeChildKind(parent, request.childKind());
        List<String> createdFqns = new ArrayList<>();
        if (request.hasSingleName()) {
            validateReservedChildName(parent, effectiveKind, request.name());
            MdObject child = effectiveKind == MetadataChildKind.FORM
                    ? createFormByParent(parent)
                    : createChildByFactory(parent, effectiveKind);
            LOG.debug("createGenericChild created child class=%s", child.eClass().getName()); //$NON-NLS-1$
            setCommonProperties(child, request.name(), request.synonym(), request.comment());
            initializeFormIfNeeded(child);
            ensureUuidsRecursively(child, "child", request.parentFqn()); //$NON-NLS-1$
            addChildToParent(parent, child, effectiveKind);
            applyDefaultTypeIfNeeded(
                    configuration,
                    child,
                    effectiveKind,
                    request.properties(),
                    preResolvedTypes,
                    transaction,
                    request.parentFqn(),
                    request.name());
            createdFqns.add(buildChildFqn(request.parentFqn(), effectiveKind, request.name()));
        }
        createdFqns.addAll(addChildrenBatch(
                configuration,
                parent,
                effectiveKind,
                request.properties(),
                request.parentFqn(),
                preResolvedTypes,
                transaction));

        if (createdFqns.isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Invalid metadata child name: " + request.name(), false); //$NON-NLS-1$
        }
        return createdFqns.get(0);
    }

    private MetadataChildKind normalizeChildKind(MdObject parent, MetadataChildKind kind) {
        if (parent == null || kind == null) {
            return kind;
        }
        if ("Enum".equals(parent.eClass().getName()) && kind == MetadataChildKind.REQUISITE) { //$NON-NLS-1$
            LOG.info("normalizeChildKind: remap REQUISITE -> ENUM_VALUE for parent Enum"); //$NON-NLS-1$
            return MetadataChildKind.ENUM_VALUE;
        }
        return kind;
    }

    private MdObject createFormByParent(MdObject parent) {
        String parentClass = parent.eClass().getName();
        String factoryMethod = formOwnerStrategy.resolveFactoryMethod(parentClass);
        MdObject created = invokeFactory(factoryMethod);
        if (created == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Cannot create form by factory method: " + factoryMethod, false); //$NON-NLS-1$
        }
        return created;
    }

    private void initializeFormIfNeeded(MdObject child) {
        if (!(child instanceof BasicForm basicForm)) {
            return;
        }
        if (basicForm.getFormType() == null) {
            basicForm.setFormType(FormType.MANAGED);
        }
    }

    private void initializeFormForRequest(MdObject child, CreateFormRequest request) {
        initializeFormIfNeeded(child);
        if (!(child instanceof BasicForm basicForm)) {
            return;
        }
        if (!request.managedEnabled()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_FORM_USAGE,
                    "Only managed forms are supported in MVP", false); //$NON-NLS-1$
        }
        basicForm.setFormType(FormType.MANAGED);
    }

    private void bindDefaultForm(MdObject owner, MdObject form, FormUsage usage, String opId) {
        String setter = formOwnerStrategy.resolveDefaultSetter(usage);
        if (setter == null) {
            return;
        }
        Method targetMethod = findCompatibleSetter(owner.getClass(), setter, form.getClass());
        if (targetMethod == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_FORM_USAGE,
                    "Form usage " + usage + " is not supported for owner " + owner.eClass().getName(), false); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try {
            targetMethod.invoke(owner, form);
            LOG.debug("[%s] Bound default form via %s for owner=%s form=%s", opId, setter, //$NON-NLS-1$
                    owner.eClass().getName(), form.getName());
        } catch (ReflectiveOperationException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to bind default form via " + setter + ": " + e.getMessage(), false, e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void populateFormContent(
            IProject project,
            IBmPlatformTransaction transaction,
            MdObject owner,
            MdObject form,
            Configuration configuration,
            FormUsage usage,
            String opId
    ) {
        if (!(form instanceof BasicForm basicForm)) {
            return;
        }
        try {
            Bundle formBundle = requireBundle(FORM_BUNDLE_ID);
            Class<?> formTypeClass = loadBundleClass(formBundle, FORM_GENERATOR_TYPE_CLASS);
            Class<?> formGeneratorClass = loadBundleClass(formBundle, FORM_GENERATOR_CLASS);
            Class<?> formFieldGeneratorClass = loadBundleClass(formBundle, FORM_FIELD_GENERATOR_CLASS);
            Class<?> formFieldInfoClass = loadBundleClass(formBundle, FORM_FIELD_INFO_CLASS);

            Bundle platformBundle = requireBundle(PLATFORM_BUNDLE_ID);
            Class<?> versionClass = loadBundleClass(platformBundle, VERSION_CLASS);

            Object injector = resolveFormInjector(formBundle);
            Object formGenerator = resolveInjectorService(injector, formGeneratorClass);
            Object formFieldGenerator = resolveInjectorService(injector, formFieldGeneratorClass);

            Object generatorFormType = resolveFormGeneratorType(owner, usage, formTypeClass);
            ScriptVariant scriptVariant = resolveScriptVariant(configuration);
            String languageCode = resolveLanguageCode(scriptVariant);
            Object runtimeVersion = resolveRuntimeVersion(configuration, versionClass, opId);

            Method getFieldsMethod = formFieldGeneratorClass.getMethod(
                    "getFormGeneratorFields", //$NON-NLS-1$
                    MdObject.class,
                    formTypeClass,
                    ScriptVariant.class,
                    versionClass);
            Object formFieldInfo = getFieldsMethod.invoke(
                    formFieldGenerator,
                    owner,
                    generatorFormType,
                    scriptVariant,
                    runtimeVersion);
            if (formFieldInfo == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "EDT form generator returned null FormFieldInfo for " + owner.eClass().getName(), false); //$NON-NLS-1$
            }

            Method generateFormMethod = formGeneratorClass.getMethod(
                    "generateForm", //$NON-NLS-1$
                    MdObject.class,
                    BasicForm.class,
                    formTypeClass,
                    ScriptVariant.class,
                    String.class,
                    versionClass,
                    formFieldInfoClass,
                    Integer.class,
                    com._1c.g5.v8.dt.metadata.mdclass.InterfaceCompatibilityMode.class);
            Object generatedForm = generateFormMethod.invoke(
                    formGenerator,
                    owner,
                    basicForm,
                    generatorFormType,
                    scriptVariant,
                    languageCode,
                    runtimeVersion,
                    formFieldInfo,
                    Integer.valueOf(1),
                    configuration.getInterfaceCompatibilityMode());
            if (generatedForm == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_TRANSACTION_FAILED,
                        "EDT form generator returned null form model for " + basicForm.getName(), false); //$NON-NLS-1$
            }

            Method setMdForm = generatedForm.getClass().getMethod("setMdForm", BasicForm.class); //$NON-NLS-1$
            setMdForm.invoke(generatedForm, basicForm);
            linkGeneratedFormToTransaction(project, transaction, basicForm, generatedForm, opId);

            LOG.debug("[%s] Form content generated via EDT IFormGenerator: owner=%s form=%s usage=%s formType=%s", // $NON-NLS-1$
                    opId,
                    owner.eClass().getName(),
                    basicForm.getName(),
                    usage,
                    String.valueOf(generatorFormType));
        } catch (MetadataOperationException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "EDT form generator is unavailable: " + e.getMessage(), false, e); //$NON-NLS-1$
        } catch (RuntimeException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to generate form content: " + e.getMessage(), false, e); //$NON-NLS-1$
        }
    }

    private void linkGeneratedFormToTransaction(
            IProject project,
            IBmPlatformTransaction transaction,
            BasicForm basicForm,
            Object generatedForm,
            String opId
    ) throws ReflectiveOperationException {
        if (!(generatedForm instanceof EObject generatedFormEObject) || !(generatedForm instanceof IBmObject generatedFormBm)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Generated form is not BM EObject: " + generatedForm.getClass().getName(), false); //$NON-NLS-1$
        }
        String externalFqn = gateway.getTopObjectFqnGenerator()
                .generateExternalPropertyFqn(basicForm, MdClassPackage.Literals.BASIC_FORM__FORM);
        if (externalFqn == null || externalFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Cannot generate external FQN for BasicForm.form", false); //$NON-NLS-1$
        }
        IBmNamespace namespace = gateway.getBmModelManager().getBmNamespace(project);
        if (namespace == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve BM namespace for project: " + project.getName(), false); //$NON-NLS-1$
        }

        Object transactionForm = generatedForm;
        if (generatedFormBm.bmGetEngine() == null) {
            transaction.attachTopObject(namespace, generatedFormBm, externalFqn);
            transactionForm = transaction.getTopObjectByFqn(namespace, externalFqn);
        } else {
            Object txForm = transaction.toTransactionObject(generatedFormEObject);
            if (txForm != null) {
                transactionForm = txForm;
            }
        }
        bindBasicFormReference(basicForm, transactionForm, opId, externalFqn);
    }

    private void bindBasicFormReference(BasicForm basicForm, Object formObject, String opId, String externalFqn)
            throws ReflectiveOperationException {
        for (Method method : BasicForm.class.getMethods()) {
            if (!"setForm".equals(method.getName()) || method.getParameterCount() != 1) { //$NON-NLS-1$
                continue;
            }
            if (method.getParameterTypes()[0].isInstance(formObject)) {
                method.invoke(basicForm, formObject);
                LOG.debug("[%s] Attached generated form to transaction: basicForm=%s externalFqn=%s formClass=%s", //$NON-NLS-1$
                        opId,
                        basicForm.getName(),
                        externalFqn,
                        formObject.getClass().getName());
                return;
            }
        }
        throw new MetadataOperationException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "BasicForm.setForm compatible setter not found for generated form class: "
                        + formObject.getClass().getName(),
                false); //$NON-NLS-1$
    }

    private Object resolveFormGeneratorType(MdObject owner, FormUsage usage, Class<?> formTypeClass) {
        String typeName = switch (usage) {
            case OBJECT -> "OBJECT"; //$NON-NLS-1$
            case LIST -> "LIST"; //$NON-NLS-1$
            case CHOICE -> "CHOICE"; //$NON-NLS-1$
            case AUXILIARY -> inferAuxiliaryFormType(owner);
        };
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Object enumValue = Enum.valueOf((Class<? extends Enum>) formTypeClass.asSubclass(Enum.class), typeName);
        return enumValue;
    }

    private String inferAuxiliaryFormType(MdObject owner) {
        if (owner == null || owner.eClass() == null) {
            return "GENERIC"; //$NON-NLS-1$
        }
        String ownerType = owner.eClass().getName();
        return switch (ownerType) {
            case "Report" -> "REPORT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Enum", "InformationRegister", "AccumulationRegister", "AccountingRegister", "CalculationRegister" -> "LIST"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            default -> "OBJECT"; //$NON-NLS-1$
        };
    }

    private ScriptVariant resolveScriptVariant(Configuration configuration) {
        ScriptVariant variant = configuration != null ? configuration.getScriptVariant() : null;
        return variant == null ? ScriptVariant.RUSSIAN : variant;
    }

    private String resolveLanguageCode(ScriptVariant scriptVariant) {
        if (scriptVariant == ScriptVariant.ENGLISH) {
            return EN_LANGUAGE;
        }
        return RU_LANGUAGE;
    }

    private Object resolveRuntimeVersion(Configuration configuration, Class<?> versionClass, String opId)
            throws ReflectiveOperationException {
        if (configuration != null && configuration.getCompatibilityMode() != null) {
            try {
                Method parseCompatibilityMode = versionClass.getMethod(
                        "parseCompatibilityMode", configuration.getCompatibilityMode().getClass()); //$NON-NLS-1$
                Object parsed = parseCompatibilityMode.invoke(null, configuration.getCompatibilityMode());
                if (parsed != null) {
                    return parsed;
                }
            } catch (ReflectiveOperationException e) {
                LOG.debug("[%s] Failed to parse compatibility mode, fallback to LATEST: %s", opId, e.getMessage()); //$NON-NLS-1$
            }
        }
        return versionClass.getField("LATEST").get(null); //$NON-NLS-1$
    }

    private Object resolveFormInjector(Bundle formBundle) throws ReflectiveOperationException {
        Class<?> formPluginClass = loadBundleClass(formBundle, FORM_PLUGIN_CLASS);
        Method getDefault = formPluginClass.getMethod("getDefault"); //$NON-NLS-1$
        Object plugin = getDefault.invoke(null);
        if (plugin == null) {
            try {
                formBundle.start(Bundle.START_TRANSIENT);
            } catch (Exception e) {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                        "Failed to start EDT form bundle: " + e.getMessage(), false, e); //$NON-NLS-1$
            }
            plugin = getDefault.invoke(null);
        }
        if (plugin == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "FormPlugin instance is unavailable", false); //$NON-NLS-1$
        }
        Method getInjector = formPluginClass.getMethod("getInjector"); //$NON-NLS-1$
        Object injector = getInjector.invoke(plugin);
        if (injector == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "FormPlugin injector is unavailable", false); //$NON-NLS-1$
        }
        return injector;
    }

    private Object resolveInjectorService(Object injector, Class<?> serviceClass) throws ReflectiveOperationException {
        Class<?> injectorApiClass = resolveInjectorApiClass(injector);
        Method getInstance = injectorApiClass.getMethod("getInstance", Class.class); //$NON-NLS-1$
        Object service = getInstance.invoke(injector, serviceClass);
        if (service == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Injector returned null for " + serviceClass.getName(), false); //$NON-NLS-1$
        }
        return service;
    }

    private Class<?> resolveInjectorApiClass(Object injector) {
        ClassLoader classLoader = injector.getClass().getClassLoader();
        try {
            Class<?> injectorInterface = Class.forName(GUICE_INJECTOR_CLASS, false, classLoader);
            if (injectorInterface.isAssignableFrom(injector.getClass())) {
                return injectorInterface;
            }
        } catch (ClassNotFoundException e) {
            LOG.debug("Guice Injector interface was not resolved from injector classloader: %s", e.getMessage()); //$NON-NLS-1$
        }
        for (Class<?> iface : injector.getClass().getInterfaces()) {
            if (GUICE_INJECTOR_CLASS.equals(iface.getName())) {
                return iface;
            }
        }
        return injector.getClass();
    }

    private Bundle requireBundle(String bundleId) {
        Bundle bundle = Platform.getBundle(bundleId);
        if (bundle == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Required EDT bundle is unavailable: " + bundleId, false); //$NON-NLS-1$
        }
        return bundle;
    }

    private Class<?> loadBundleClass(Bundle bundle, String className) throws ClassNotFoundException {
        return bundle.loadClass(className);
    }

    private Method findCompatibleSetter(Class<?> ownerClass, String methodName, Class<?> argumentType) {
        for (Method method : ownerClass.getMethods()) {
            if (!methodName.equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (parameterType.isAssignableFrom(argumentType)) {
                return method;
            }
        }
        return null;
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
                "Attribute", "TabularSection", "Command", "Form", "Template", "Dimension", "Resource", "Requisite", "EnumValue" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
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
        String[] suffixes = {"TabularSection", "Attribute", "Command", "Form", "Template", "Dimension", "Resource", "Requisite", "EnumValue"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
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

    private List<String> addChildrenBatch(
            Configuration configuration,
            MdObject parent,
            MetadataChildKind kind,
            Map<String, Object> properties,
            String parentFqn,
            Map<String, TypeItem> preResolvedTypes,
            IBmPlatformTransaction transaction
    ) {
        List<String> createdFqns = new ArrayList<>();
        if (properties == null || properties.isEmpty()) {
            return createdFqns;
        }
        Object rawChildren = properties.get("children"); //$NON-NLS-1$
        if (rawChildren == null && kind == MetadataChildKind.ATTRIBUTE) {
            rawChildren = properties.get("attributes"); //$NON-NLS-1$
        }
        if (!(rawChildren instanceof List<?> entries)) {
            return createdFqns;
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
            @SuppressWarnings("unchecked")
            Map<String, Object> childProperties = (Map<String, Object>) rawMap;
            try {
                addChildToParent(parent, child, kind);
                applyDefaultTypeIfNeeded(
                        configuration,
                        child,
                        kind,
                        childProperties,
                        preResolvedTypes,
                        transaction,
                        parentFqn,
                        name);
                createdFqns.add(buildChildFqn(parentFqn, kind, name));
            } catch (MetadataOperationException e) {
                if (e.getCode() != MetadataOperationCode.METADATA_ALREADY_EXISTS) {
                    throw e;
                }
            }
        }
        return createdFqns;
    }

    private Map<String, TypeItem> preResolveChildTypes(
            IProject project,
            AddMetadataChildRequest request
    ) {
        Set<String> typeStrings = collectChildTypeStrings(request);
        if (typeStrings.isEmpty()) {
            return Map.of();
        }
        Map<String, TypeItem> preResolvedTypes = new HashMap<>();
        executeRead(project, readTx -> {
            for (String typeString : typeStrings) {
                TypeItem item = resolveTypeItem(typeString, readTx);
                if (item == null && !isSimpleTypeQuery(typeString)) {
                    throw new MetadataOperationException(
                            MetadataOperationCode.INVALID_PROPERTY_VALUE,
                            "Type not found in BM: " + typeString, false); //$NON-NLS-1$
                }
                if (item != null) {
                    cacheResolvedTypeItem(preResolvedTypes, typeString, item);
                }
            }
            return null;
        });
        return preResolvedTypes;
    }

    @SuppressWarnings("unchecked")
    private Set<String> collectChildTypeStrings(AddMetadataChildRequest request) {
        Set<String> typeStrings = new LinkedHashSet<>();
        Map<String, Object> properties = request.properties();
        if (properties != null && !properties.isEmpty()) {
            String directType = normalizeTypeLookupQuery(getMapValueIgnoreCase(properties, "type")); //$NON-NLS-1$
            if (directType != null && !directType.isBlank()) {
                typeStrings.add(directType);
            }
            Object rawChildren = getMapValueIgnoreCase(properties, "children"); //$NON-NLS-1$
            if (rawChildren == null && request.childKind() == MetadataChildKind.ATTRIBUTE) {
                rawChildren = getMapValueIgnoreCase(properties, "attributes"); //$NON-NLS-1$
            }
            if (rawChildren instanceof List<?> entries) {
                for (Object entry : entries) {
                    if (entry instanceof Map<?, ?> entryMap) {
                        String entryType = normalizeTypeLookupQuery(getMapValueIgnoreCase((Map<String, Object>) entryMap, "type")); //$NON-NLS-1$
                        if (entryType != null && !entryType.isBlank()) {
                            typeStrings.add(entryType);
                        }
                    }
                }
            }
        }
        if (isKindWithRequiredType(request.childKind())) {
            typeStrings.add(DEFAULT_BASIC_FEATURE_TYPE);
        }
        return typeStrings;
    }

    private void applyDefaultTypeIfNeeded(
            Configuration configuration,
            MdObject child,
            MetadataChildKind kind,
            Map<String, Object> properties,
            Map<String, TypeItem> preResolvedTypes,
            IBmPlatformTransaction transaction,
            String parentFqn,
            String childName
    ) {
        if (!(child instanceof BasicFeature feature)) {
            return;
        }
        if (feature.getType() != null && !feature.getType().getTypes().isEmpty()) {
            return;
        }
        Object requestedTypeValue = properties == null ? null : getMapValueIgnoreCase(properties, "type"); //$NON-NLS-1$
        TypeSpec requestedSpec = requestedTypeValue == null ? null : normalizeTypeSpec(requestedTypeValue);
        String requestedType = requestedSpec == null ? null : requestedSpec.typeQuery();
        String typeToApply = requestedType != null ? requestedType
                : (isKindWithRequiredType(kind) ? DEFAULT_BASIC_FEATURE_TYPE : null);
        if (typeToApply == null || typeToApply.isBlank()) {
            return;
        }
        TypeItem typeItem = resolveTypeItemForFeature(
                feature,
                configuration,
                typeToApply,
                preResolvedTypes);
        if (typeItem == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Type not found in BM/type provider for add_metadata_child: " + typeToApply, false); //$NON-NLS-1$
        }
        if (requestedType == null) {
            LOG.info("Auto-assign default type=%s for child kind=%s parent=%s child=%s", //$NON-NLS-1$
                    typeToApply, kind, parentFqn, childName);
        }
        TypeSpec effectiveSpec = requestedSpec != null
                ? requestedSpec
                : TypeSpec.of(typeToApply);
        setAttributeType(feature, typeItem, effectiveSpec, transaction);
    }

    private boolean isKindWithRequiredType(MetadataChildKind kind) {
        return kind == MetadataChildKind.ATTRIBUTE
                || kind == MetadataChildKind.REQUISITE
                || kind == MetadataChildKind.DIMENSION
                || kind == MetadataChildKind.RESOURCE;
    }

    private String buildChildFqn(String parentFqn, MetadataChildKind kind, String name) {
        return parentFqn + "." + kind.getDisplayName() + "." + name; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String extractNameFromFqn(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return null;
        }
        int pos = fqn.lastIndexOf('.');
        return pos >= 0 && pos + 1 < fqn.length() ? fqn.substring(pos + 1) : fqn;
    }

    private String asString(Object value) {
        return value instanceof String str && !str.isBlank() ? str : null;
    }

    @SuppressWarnings("unchecked")
    private void applyObjectChanges(
            Configuration configuration,
            MdObject target,
            Map<String, Object> changes,
            String targetFqn,
            IBmPlatformTransaction transaction,
            Map<String, TypeItem> preResolvedTypes
    ) {
        Map<String, Object> setChanges = normalizeSetChangesForTarget(target, asMap(changes.get("set"))); //$NON-NLS-1$
        List<?> unsetChanges = changes.get("unset") instanceof List<?> list ? list : List.of(); //$NON-NLS-1$
        List<Map<String, Object>> childOps = asListOfMaps(changes.get("children_ops")); //$NON-NLS-1$

        if (setChanges.isEmpty() && unsetChanges.isEmpty() && childOps.isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "changes must include set, unset and/or children_ops", false); //$NON-NLS-1$
        }

        // Collect synthetic child ops from set keys that look like child attribute names
        // (i.e. key is not an EMF feature AND value is a Map, e.g. {"type":"CatalogRef.Контрагенты"})
        List<Map<String, Object>> syntheticChildOps = new ArrayList<>();
        Set<String> consumedSetKeys = new HashSet<>();

        for (Map.Entry<String, Object> entry : setChanges.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            if ("name".equalsIgnoreCase(key)) { //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Changing name is not supported in update_metadata", false); //$NON-NLS-1$
            }
            if ("synonym".equalsIgnoreCase(key)) { //$NON-NLS-1$
                String synonym = entry.getValue() == null ? null : String.valueOf(entry.getValue());
                if (synonym == null || synonym.isBlank()) {
                    continue;
                }
                EMap<String, String> synonymMap = target.getSynonym();
                if (synonymMap != null) {
                    synonymMap.put(RU_LANGUAGE, synonym);
                }
                continue;
            }
            // Auto-redirect: if key is not an EMF feature but value is a Map,
            // treat it as a child attribute property update (e.g. set type on Attribute)
            EStructuralFeature probe = target.eClass().getEStructuralFeature(key);
            if (probe instanceof EReference ref && ref.isContainment() && entry.getValue() instanceof List<?> rawChildren) {
                List<Map<String, Object>> redirected = buildChildOpsFromContainmentSet(
                        configuration, targetFqn, key, rawChildren);
                if (!redirected.isEmpty()) {
                    syntheticChildOps.addAll(redirected);
                    consumedSetKeys.add(key);
                    continue;
                }
            }
            if (probe == null && entry.getValue() instanceof Map<?, ?> childProps) {
                // Try resolving as child: targetFqn.Attribute.key
                String candidateFqn = targetFqn + ".Attribute." + key; //$NON-NLS-1$
                MdObject childProbe = resolveByFqn(configuration, candidateFqn);
                if (childProbe != null) {
                    LOG.info("applyObjectChanges: auto-redirect set key '%s' to children_ops for %s", key, candidateFqn); //$NON-NLS-1$
                    Map<String, Object> syntheticOp = new HashMap<>();
                    syntheticOp.put("op", "set"); //$NON-NLS-1$ //$NON-NLS-2$
                    syntheticOp.put("child_fqn", candidateFqn); //$NON-NLS-1$
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typedChildProps = (Map<String, Object>) childProps;
                    syntheticOp.put("set", typedChildProps); //$NON-NLS-1$
                    syntheticChildOps.add(syntheticOp);
                    continue;
                }
            }
            if (consumedSetKeys.contains(key)) {
                continue;
            }
            setFeatureValue(configuration, target, key, entry.getValue(), transaction, preResolvedTypes);
        }

        for (Object rawKey : unsetChanges) {
            String key = rawKey == null ? null : String.valueOf(rawKey);
            if (key == null || key.isBlank()) {
                continue;
            }
            if ("name".equalsIgnoreCase(key)) { //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Cannot unset required field: name", false); //$NON-NLS-1$
            }
            if ("synonym".equalsIgnoreCase(key)) { //$NON-NLS-1$
                EMap<String, String> synonymMap = target.getSynonym();
                if (synonymMap != null) {
                    synonymMap.removeKey(RU_LANGUAGE);
                }
                continue;
            }
            unsetFeatureValue(target, key);
        }

        // Merge any synthetic child ops from auto-redirected set keys
        List<Map<String, Object>> allChildOps;
        if (syntheticChildOps.isEmpty()) {
            allChildOps = childOps;
        } else {
            allChildOps = new ArrayList<>(childOps);
            allChildOps.addAll(syntheticChildOps);
        }
        applyChildOperations(configuration, targetFqn, allChildOps, transaction, preResolvedTypes);
    }

    private Map<String, Object> normalizeSetChangesForTarget(MdObject target, Map<String, Object> rawSetChanges) {
        if (rawSetChanges == null || rawSetChanges.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>(rawSetChanges);
        if (!(target instanceof BasicFeature)) {
            return normalized;
        }

        Map<String, Object> typePatch = new LinkedHashMap<>();
        List<String> consumedKeys = new ArrayList<>();
        for (Map.Entry<String, Object> entry : rawSetChanges.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            if (key.equalsIgnoreCase("length")) { //$NON-NLS-1$
                @SuppressWarnings("unchecked")
                Map<String, Object> sq = (Map<String, Object>) typePatch.computeIfAbsent(
                        "stringQualifiers", //$NON-NLS-1$
                        k -> new LinkedHashMap<String, Object>());
                sq.put("length", entry.getValue()); //$NON-NLS-1$
                consumedKeys.add(key);
                continue;
            }
            if (key.equalsIgnoreCase("fixed") || key.equalsIgnoreCase("fixedLength")) { //$NON-NLS-1$ //$NON-NLS-2$
                @SuppressWarnings("unchecked")
                Map<String, Object> sq = (Map<String, Object>) typePatch.computeIfAbsent(
                        "stringQualifiers", //$NON-NLS-1$
                        k -> new LinkedHashMap<String, Object>());
                sq.put("fixed", entry.getValue()); //$NON-NLS-1$
                consumedKeys.add(key);
                continue;
            }
            if (key.equalsIgnoreCase("precision") || key.equalsIgnoreCase("scale") || key.equalsIgnoreCase("nonNegative")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                @SuppressWarnings("unchecked")
                Map<String, Object> nq = (Map<String, Object>) typePatch.computeIfAbsent(
                        "numberQualifiers", //$NON-NLS-1$
                        k -> new LinkedHashMap<String, Object>());
                nq.put(key, entry.getValue());
                consumedKeys.add(key);
                continue;
            }
            if (key.equalsIgnoreCase("dateFractions") || key.equalsIgnoreCase("fractions")) { //$NON-NLS-1$ //$NON-NLS-2$
                @SuppressWarnings("unchecked")
                Map<String, Object> dq = (Map<String, Object>) typePatch.computeIfAbsent(
                        "dateQualifiers", //$NON-NLS-1$
                        k -> new LinkedHashMap<String, Object>());
                dq.put("dateFractions", entry.getValue()); //$NON-NLS-1$
                consumedKeys.add(key);
                continue;
            }
            if (key.equalsIgnoreCase("type.stringQualifiers")) { //$NON-NLS-1$
                Map<String, Object> nested = asMap(entry.getValue());
                if (!nested.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sq = (Map<String, Object>) typePatch.computeIfAbsent(
                            "stringQualifiers", //$NON-NLS-1$
                            k -> new LinkedHashMap<String, Object>());
                    sq.putAll(nested);
                }
                consumedKeys.add(key);
                continue;
            }
            if (key.equalsIgnoreCase("type.numberQualifiers")) { //$NON-NLS-1$
                Map<String, Object> nested = asMap(entry.getValue());
                if (!nested.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nq = (Map<String, Object>) typePatch.computeIfAbsent(
                            "numberQualifiers", //$NON-NLS-1$
                            k -> new LinkedHashMap<String, Object>());
                    nq.putAll(nested);
                }
                consumedKeys.add(key);
                continue;
            }
            if (key.equalsIgnoreCase("type.dateQualifiers")) { //$NON-NLS-1$
                Map<String, Object> nested = asMap(entry.getValue());
                if (!nested.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dq = (Map<String, Object>) typePatch.computeIfAbsent(
                            "dateQualifiers", //$NON-NLS-1$
                            k -> new LinkedHashMap<String, Object>());
                    dq.putAll(nested);
                }
                consumedKeys.add(key);
                continue;
            }
            if (key.equalsIgnoreCase("type.types")) { //$NON-NLS-1$
                typePatch.put("types", entry.getValue()); //$NON-NLS-1$
                consumedKeys.add(key);
                continue;
            }
            if (key.regionMatches(true, 0, "type.types[", 0, "type.types[".length()) && key.endsWith("]")) { //$NON-NLS-1$ //$NON-NLS-2$
                int indexStart = "type.types[".length(); //$NON-NLS-1$
                int indexEnd = key.length() - 1;
                Integer index = parseInteger(key.substring(indexStart, indexEnd));
                if (index != null && index.intValue() >= 0) {
                    List<Object> values = toMutableList(typePatch.get("types")); //$NON-NLS-1$
                    ensureListSize(values, index.intValue() + 1);
                    values.set(index.intValue(), entry.getValue());
                    typePatch.put("types", values); //$NON-NLS-1$
                    consumedKeys.add(key);
                    continue;
                }
            }
            if (key.regionMatches(true, 0, "type.", 0, "type.".length()) && key.length() > "type.".length()) { //$NON-NLS-1$ //$NON-NLS-2$
                String nestedPath = key.substring("type.".length()); //$NON-NLS-1$
                putNestedMapValue(typePatch, nestedPath, entry.getValue());
                consumedKeys.add(key);
            }
        }

        for (String consumed : consumedKeys) {
            normalized.remove(consumed);
        }
        if (typePatch.isEmpty()) {
            return normalized;
        }
        Object existingType = normalized.get("type"); //$NON-NLS-1$
        normalized.put("type", mergeTypeSetPayload(existingType, typePatch)); //$NON-NLS-1$
        return normalized;
    }

    private Object mergeTypeSetPayload(Object existingType, Map<String, Object> typePatch) {
        if (typePatch == null || typePatch.isEmpty()) {
            return existingType;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existingType instanceof Map<?, ?> existingMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) existingMap;
            mergeNestedMaps(merged, cast);
            mergeNestedMaps(merged, typePatch);
            return merged;
        }
        mergeNestedMaps(merged, typePatch);
        if (existingType != null) {
            merged.putIfAbsent("type", existingType); //$NON-NLS-1$
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private void mergeNestedMaps(Map<String, Object> target, Map<String, Object> patch) {
        if (target == null || patch == null || patch.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object current = target.get(key);
            if (current instanceof Map<?, ?> currentMap && value instanceof Map<?, ?> valueMap) {
                Map<String, Object> currentMutable = new LinkedHashMap<>((Map<String, Object>) currentMap);
                mergeNestedMaps(currentMutable, (Map<String, Object>) valueMap);
                target.put(key, currentMutable);
            } else {
                target.put(key, value);
            }
        }
    }

    private void putNestedMapValue(Map<String, Object> root, String dottedPath, Object value) {
        if (root == null || dottedPath == null || dottedPath.isBlank()) {
            return;
        }
        String[] parts = dottedPath.split("\\."); //$NON-NLS-1$
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].trim(); //$NON-NLS-1$
            if (part.isBlank()) {
                continue;
            }
            boolean last = i == parts.length - 1;
            if (last) {
                cursor.put(part, value);
                return;
            }
            Object next = cursor.get(part);
            Map<String, Object> nextMap;
            if (next instanceof Map<?, ?> existingMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) existingMap;
                nextMap = cast;
            } else {
                nextMap = new LinkedHashMap<>();
                cursor.put(part, nextMap);
            }
            cursor = nextMap;
        }
    }

    private List<Object> toMutableList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return new ArrayList<>();
    }

    private void ensureListSize(List<Object> list, int size) {
        if (list == null || size <= 0) {
            return;
        }
        while (list.size() < size) {
            list.add(null);
        }
    }

    private void applyChildOperations(
            Configuration configuration,
            String parentTargetFqn,
            List<Map<String, Object>> childOps,
            IBmPlatformTransaction transaction,
            Map<String, TypeItem> preResolvedTypes
    ) {
        for (Map<String, Object> op : childOps) {
            String opType = asString(op.get("op")); //$NON-NLS-1$
            if (opType == null || opType.isBlank()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "children_ops item must contain op", false); //$NON-NLS-1$
            }
            String childFqn = asString(op.get("child_fqn")); //$NON-NLS-1$
            if (childFqn == null || childFqn.isBlank()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "children_ops item must contain child_fqn", false); //$NON-NLS-1$
            }
            if (!isChildOfTarget(parentTargetFqn, childFqn)) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "child_fqn is outside target object scope: " + childFqn, false); //$NON-NLS-1$
            }

            MdObject child = resolveByFqn(configuration, childFqn);
            if (child == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_NOT_FOUND,
                        "Metadata child object not found: " + childFqn, false); //$NON-NLS-1$
            }

            String normalizedOp = normalizeToken(opType);
            switch (normalizedOp) {
                case "renamechild", "rename" -> renameChildObject(child, childFqn, asString(op.get("new_name"))); //$NON-NLS-1$ //$NON-NLS-2$
                case "deletechild", "delete", "remove" -> { //$NON-NLS-1$ //$NON-NLS-2$
                    boolean recursive = asBoolean(op.get("recursive")); //$NON-NLS-1$
                    if (!recursive && hasNestedMetadataChildren(child)) {
                        throw new MetadataOperationException(
                                MetadataOperationCode.METADATA_DELETE_CONFLICT,
                                "Child has nested objects. Use recursive=true: " + childFqn, false); //$NON-NLS-1$
                    }
                    removeMetadataObject(configuration, childFqn, child);
                }
                case "setchildprops", "set", "update" -> { //$NON-NLS-1$ //$NON-NLS-2$
                    Map<String, Object> nestedChanges = asMap(op.get("changes")); //$NON-NLS-1$
                    if (nestedChanges.isEmpty()) {
                        nestedChanges = new HashMap<>();
                        Object set = op.get("set"); //$NON-NLS-1$
                        Object unset = op.get("unset"); //$NON-NLS-1$
                        Object nestedChildOps = op.get("children_ops"); //$NON-NLS-1$
                        Object shorthandType = op.get("type"); //$NON-NLS-1$
                        Map<String, Object> shorthandProperties = asMap(op.get("properties")); //$NON-NLS-1$
                        if (set != null) {
                            nestedChanges.put("set", set); //$NON-NLS-1$
                        }
                        if (unset != null) {
                            nestedChanges.put("unset", unset); //$NON-NLS-1$
                        }
                        if (nestedChildOps != null) {
                            nestedChanges.put("children_ops", nestedChildOps); //$NON-NLS-1$
                        }
                        if (shorthandType != null || !shorthandProperties.isEmpty()) {
                            Map<String, Object> synthesizedSet = new HashMap<>();
                            if (shorthandType != null) {
                                synthesizedSet.put("type", shorthandType); //$NON-NLS-1$
                            }
                            if (!shorthandProperties.isEmpty()) {
                                synthesizedSet.putAll(shorthandProperties);
                            }
                            Map<String, Object> existingSet = asMap(nestedChanges.get("set")); //$NON-NLS-1$
                            if (!existingSet.isEmpty()) {
                                synthesizedSet.putAll(existingSet);
                            }
                            nestedChanges.put("set", synthesizedSet); //$NON-NLS-1$
                        }
                    }
                    applyObjectChanges(configuration, child, nestedChanges, childFqn,
                            transaction, preResolvedTypes);
                }
                default -> throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Unsupported children_ops op: " + opType, false); //$NON-NLS-1$
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildChildOpsFromContainmentSet(
            Configuration configuration,
            String parentTargetFqn,
            String featureKey,
            List<?> rawChildren
    ) {
        if (rawChildren == null || rawChildren.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> syntheticOps = new ArrayList<>();
        String marker = resolveChildMarkerByFeature(featureKey);
        for (Object raw : rawChildren) {
            if (!(raw instanceof Map<?, ?> childMapRaw)) {
                continue;
            }
            Map<String, Object> childMap = (Map<String, Object>) childMapRaw;
            String childName = asString(childMap.get("name")); //$NON-NLS-1$
            if (childName == null || childName.isBlank()) {
                continue;
            }
            String childFqn = parentTargetFqn + "." + marker + "." + childName; //$NON-NLS-1$ //$NON-NLS-2$
            MdObject child = resolveByFqn(configuration, childFqn);
            if (child == null) {
                continue;
            }
            Map<String, Object> childSet = new HashMap<>();
            for (Map.Entry<String, Object> entry : childMap.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank() || "name".equalsIgnoreCase(key)) { //$NON-NLS-1$
                    continue;
                }
                childSet.put(key, entry.getValue());
            }
            if (childSet.isEmpty()) {
                continue;
            }
            Map<String, Object> op = new HashMap<>();
            op.put("op", "update"); //$NON-NLS-1$ //$NON-NLS-2$
            op.put("child_fqn", childFqn); //$NON-NLS-1$
            op.put("set", childSet); //$NON-NLS-1$
            syntheticOps.add(op);
        }
        return syntheticOps;
    }

    private String resolveChildMarkerByFeature(String featureKey) {
        String token = normalizeToken(featureKey);
        if ("attributes".equals(token) || "attribute".equals(token)) { //$NON-NLS-1$ //$NON-NLS-2$
            return "Attribute"; //$NON-NLS-1$
        }
        if ("tabularsections".equals(token) || "tabularsection".equals(token)) { //$NON-NLS-1$ //$NON-NLS-2$
            return "TabularSection"; //$NON-NLS-1$
        }
        if ("forms".equals(token) || "form".equals(token)) { //$NON-NLS-1$ //$NON-NLS-2$
            return "Form"; //$NON-NLS-1$
        }
        if ("commands".equals(token) || "command".equals(token)) { //$NON-NLS-1$ //$NON-NLS-2$
            return "Command"; //$NON-NLS-1$
        }
        if ("templates".equals(token) || "template".equals(token)) { //$NON-NLS-1$ //$NON-NLS-2$
            return "Template"; //$NON-NLS-1$
        }
        String singular = singularize(token);
        if (singular == null || singular.isBlank()) {
            return "Attribute"; //$NON-NLS-1$
        }
        return Character.toUpperCase(singular.charAt(0)) + singular.substring(1);
    }

    private void renameChildObject(MdObject child, String childFqn, String newName) {
        if (!MetadataNameValidator.isValidName(newName)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Invalid metadata child name: " + newName, false); //$NON-NLS-1$
        }

        EObject container = child.eContainer();
        EStructuralFeature containment = child.eContainmentFeature();
        if (container == null || containment == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Cannot rename child without container: " + childFqn, false); //$NON-NLS-1$
        }
        if (container instanceof MdObject parent && isAttributeClassName(child.eClass().getName())) {
            validateReservedChildName(parent, MetadataChildKind.ATTRIBUTE, newName);
        }
        if (containment.isMany()) {
            @SuppressWarnings("unchecked")
            Collection<EObject> siblings = (Collection<EObject>) container.eGet(containment);
            if (siblings != null) {
                for (EObject sibling : siblings) {
                    if (!(sibling instanceof MdObject siblingObject) || siblingObject == child) {
                        continue;
                    }
                    if (newName.equalsIgnoreCase(siblingObject.getName())) {
                        throw new MetadataOperationException(
                                MetadataOperationCode.METADATA_ALREADY_EXISTS,
                                "Child already exists: " + newName, false); //$NON-NLS-1$
                    }
                }
            }
        }
        child.setName(newName);
    }

    private boolean isAttributeClassName(String className) {
        return className != null && className.endsWith("Attribute"); //$NON-NLS-1$
    }

    private boolean isChildOfTarget(String targetFqn, String childFqn) {
        if (targetFqn == null || childFqn == null) {
            return false;
        }
        return childFqn.length() > targetFqn.length()
                && childFqn.startsWith(targetFqn)
                && childFqn.charAt(targetFqn.length()) == '.';
    }

    private void setFeatureValue(Configuration configuration, MdObject target, String fieldName, Object value,
            IBmPlatformTransaction transaction, Map<String, TypeItem> preResolvedTypes) {
        if ("uuid".equalsIgnoreCase(fieldName)) { //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Changing uuid is not supported", false); //$NON-NLS-1$
        }
        // Special case: "type" on BasicFeature is a containment reference (TypeDescription),
        // which cannot be set via the generic applyReferenceValue path.
        // Instead, use dedicated TypeItem resolution from BM.
        if ("type".equalsIgnoreCase(fieldName) && target instanceof BasicFeature feature) { //$NON-NLS-1$
            TypeSpec typeSpec = normalizeTypeSpec(value);
            String typeString = typeSpec.typeQuery();
            TypeItem typeItem = resolveTypeItemForFeature(feature, configuration, typeString, preResolvedTypes);
            if (typeItem == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_PROPERTY_VALUE,
                        "Type not found in BM/type provider for field 'type': " + typeString, false); //$NON-NLS-1$
            }
            setAttributeType(feature, typeItem, typeSpec, transaction);
            return;
        }
        EStructuralFeature eFeature = resolveFeatureIgnoreCase(target, fieldName);
        if (eFeature == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Unknown metadata field: " + fieldName, false); //$NON-NLS-1$
        }
        if (eFeature.isDerived() || eFeature.isTransient() || eFeature.isVolatile()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Field is read-only: " + fieldName, false); //$NON-NLS-1$
        }
        if (eFeature instanceof EReference reference) {
            applyReferenceValue(configuration, target, reference, value);
            return;
        }
        if (eFeature.isMany()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Collection attribute updates are not supported: " + fieldName, false); //$NON-NLS-1$
        }
        if (!(eFeature instanceof EAttribute attribute)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Unsupported field type: " + fieldName, false); //$NON-NLS-1$
        }

        Object converted = convertAttributeValue(attribute, value);
        target.eSet(eFeature, converted);
    }

    /**
     * Sets the type (TypeDescription) on a BasicFeature using a pre-resolved TypeItem.
     * <p>The TypeItem must have been resolved in a read transaction before entering
     * the write transaction, then converted via {@code transaction.toTransactionObject()}.</p>
     */
    private void setAttributeType(
            BasicFeature feature,
            TypeItem preResolvedTypeItem,
            TypeSpec typeSpec,
            IBmPlatformTransaction transaction
    ) {
        TypeItem txTypeItem = null;
        if (preResolvedTypeItem != null) {
            try {
                txTypeItem = transaction.toTransactionObject(preResolvedTypeItem);
            } catch (RuntimeException e) {
                LOG.debug("setAttributeType: toTransactionObject failed for type=%s feature=%s: %s", //$NON-NLS-1$
                        typeSpec == null ? null : typeSpec.typeQuery(),
                        feature == null ? null : feature.eClass().getName(),
                        e.getMessage());
            }
        }
        if (txTypeItem == null) {
            txTypeItem = resolveTypeItemInCurrentNamespace(transaction, feature, typeSpec, preResolvedTypeItem);
        }
        if (txTypeItem == null) {
            txTypeItem = resolveTypeItemInCandidateNamespace(transaction, preResolvedTypeItem, typeSpec);
        }
        if (txTypeItem == null) {
            txTypeItem = resolveExternalTypeItemCandidate(transaction, preResolvedTypeItem, typeSpec);
        }
        if (txTypeItem == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Type value cannot be resolved in transaction namespace: " //$NON-NLS-1$
                            + (typeSpec == null ? null : typeSpec.typeQuery()),
                    false);
        }
        TypeDescription typeDesc = McoreFactory.eINSTANCE.createTypeDescription();
        typeDesc.getTypes().add(txTypeItem);

        String typeName = resolveTypeNameForQualifiers(preResolvedTypeItem, typeSpec);
        TypeDescription existingType = feature.getType();
        if (isNumberType(typeName)) {
            NumberQualifiers nq = McoreFactory.eINSTANCE.createNumberQualifiers();
            Integer precision = typeSpec == null ? null : typeSpec.numberPrecision();
            Integer scale = typeSpec == null ? null : typeSpec.numberScale();
            Boolean nonNegative = typeSpec == null ? null : typeSpec.numberNonNegative();
            NumberQualifiers existing = existingType == null ? null : existingType.getNumberQualifiers();
            nq.setPrecision(firstPositive(precision, existing == null ? null : existing.getPrecision(), 15));
            nq.setScale(firstNonNegative(scale, existing == null ? null : existing.getScale(), 2));
            nq.setNonNegative(nonNegative != null
                    ? nonNegative.booleanValue()
                    : (existing != null && existing.isNonNegative()));
            typeDesc.setNumberQualifiers(nq);
        } else if (isStringType(typeName)) {
            StringQualifiers sq = McoreFactory.eINSTANCE.createStringQualifiers();
            Integer length = typeSpec == null ? null : typeSpec.stringLength();
            Boolean fixed = typeSpec == null ? null : typeSpec.stringFixed();
            StringQualifiers existing = existingType == null ? null : existingType.getStringQualifiers();
            sq.setLength(firstPositive(length, existing == null ? null : existing.getLength(), 150));
            sq.setFixed(fixed != null
                    ? fixed.booleanValue()
                    : (existing != null && existing.isFixed()));
            typeDesc.setStringQualifiers(sq);
        } else if (isDateType(typeName)) {
            DateQualifiers dq = McoreFactory.eINSTANCE.createDateQualifiers();
            DateFractions fractions = typeSpec == null ? null : typeSpec.dateFractions();
            DateQualifiers existing = existingType == null ? null : existingType.getDateQualifiers();
            dq.setDateFractions(fractions != null
                    ? fractions
                    : (existing != null && existing.getDateFractions() != null
                            ? existing.getDateFractions()
                            : DateFractions.DATE_TIME));
            typeDesc.setDateQualifiers(dq);
        }

        feature.setType(typeDesc);
    }

    private TypeItem resolveExternalTypeItemCandidate(
            IBmPlatformTransaction transaction,
            TypeItem candidate,
            TypeSpec typeSpec
    ) {
        if (candidate == null) {
            return null;
        }
        if (!(candidate instanceof IBmObject bmObject)) {
            return candidate;
        }

        URI uri = null;
        try {
            uri = bmObject.bmGetUri();
        } catch (RuntimeException e) {
            LOG.debug("resolveExternalTypeItemCandidate: cannot read URI for type=%s: %s", //$NON-NLS-1$
                    typeSpec == null ? null : typeSpec.typeQuery(),
                    e.getMessage());
        }
        if (uri != null) {
            try {
                EObject external = transaction.getExternalObjectByUri(uri);
                if (external instanceof TypeItem externalType) {
                    LOG.debug("resolveExternalTypeItemCandidate: using external TypeItem by URI for type=%s", //$NON-NLS-1$
                            typeSpec == null ? null : typeSpec.typeQuery());
                    return externalType;
                }
            } catch (RuntimeException e) {
                LOG.debug("resolveExternalTypeItemCandidate: external lookup failed for type=%s uri=%s: %s", //$NON-NLS-1$
                        typeSpec == null ? null : typeSpec.typeQuery(),
                        uri,
                        e.getMessage());
            }
        }

        try {
            IBmNamespace namespace = bmObject.bmGetNamespace();
            if (namespace == null) {
                LOG.debug("resolveExternalTypeItemCandidate: fallback to detached TypeItem for type=%s", //$NON-NLS-1$
                        typeSpec == null ? null : typeSpec.typeQuery());
                return candidate;
            }
            if (isSimpleTypeSpec(typeSpec, candidate)) {
                LOG.debug("resolveExternalTypeItemCandidate: fallback to cross-namespace simple TypeItem for type=%s", //$NON-NLS-1$
                        typeSpec == null ? null : typeSpec.typeQuery());
                return candidate;
            }
        } catch (RuntimeException e) {
            LOG.debug("resolveExternalTypeItemCandidate: namespace probe failed for type=%s: %s", //$NON-NLS-1$
                    typeSpec == null ? null : typeSpec.typeQuery(),
                    e.getMessage());
        }
        return null;
    }

    private TypeItem resolveTypeItemInCandidateNamespace(
            IBmPlatformTransaction transaction,
            TypeItem candidate,
            TypeSpec typeSpec
    ) {
        if (!(candidate instanceof IBmObject bmObject)) {
            return null;
        }
        IBmNamespace candidateNamespace;
        try {
            candidateNamespace = bmObject.bmGetNamespace();
        } catch (RuntimeException e) {
            LOG.debug("resolveTypeItemInCandidateNamespace: failed to get namespace for type=%s: %s", //$NON-NLS-1$
                    typeSpec == null ? null : typeSpec.typeQuery(),
                    e.getMessage());
            return null;
        }
        if (candidateNamespace == null) {
            return null;
        }

        Set<String> queries = new LinkedHashSet<>();
        if (typeSpec != null && typeSpec.typeQuery() != null && !typeSpec.typeQuery().isBlank()) {
            queries.addAll(expandTypeQueries(typeSpec.typeQuery()));
        }
        String candidateName = firstNonBlank(
                candidate.getName(),
                candidate.getNameRu(),
                McoreUtil.getTypeName(candidate),
                McoreUtil.getTypeNameRu(candidate));
        if (candidateName != null) {
            queries.addAll(expandTypeQueries(candidateName));
        }
        if (queries.isEmpty()) {
            return null;
        }

        try {
            IBmTransaction namespaceTx = transaction.getNamespaceBoundTransaction(candidateNamespace);
            TypeItem mapped = namespaceTx.toTransactionObject(candidate);
            if (mapped != null && matchesTypeRef(mapped, queries)) {
                return mapped;
            }
            TypeItem fromNamespaceTx = findTypeItemInTransaction(namespaceTx, queries);
            if (fromNamespaceTx != null) {
                return fromNamespaceTx;
            }
            TypeItem top = findTypeItem(transaction.getTopObjectIterator(candidateNamespace, McorePackage.eINSTANCE.getType()),
                    queries);
            if (top != null) {
                return top;
            }
            return findTypeItem(
                    transaction.getContainedObjectIterator(candidateNamespace, McorePackage.eINSTANCE.getType()),
                    queries);
        } catch (RuntimeException e) {
            LOG.debug("resolveTypeItemInCandidateNamespace: failed for type=%s: %s", //$NON-NLS-1$
                    typeSpec == null ? null : typeSpec.typeQuery(),
                    e.getMessage());
            return null;
        }
    }

    private boolean isSimpleTypeSpec(TypeSpec typeSpec, TypeItem typeItem) {
        if (canonicalSimpleTypeName(typeSpec == null ? null : typeSpec.typeQuery()) != null) {
            return true;
        }
        String byTypeItem = firstNonBlank(
                typeItem == null ? null : typeItem.getName(),
                typeItem == null ? null : typeItem.getNameRu(),
                typeItem == null ? null : McoreUtil.getTypeName(typeItem),
                typeItem == null ? null : McoreUtil.getTypeNameRu(typeItem));
        return isSimpleTypeToken(byTypeItem);
    }

    private TypeItem resolveTypeItemInCurrentNamespace(
            IBmPlatformTransaction transaction,
            EObject contextObject,
            TypeSpec typeSpec,
            TypeItem fallbackTypeItem
    ) {
        Set<String> queries = new LinkedHashSet<>();
        if (typeSpec != null && typeSpec.typeQuery() != null && !typeSpec.typeQuery().isBlank()) {
            queries.addAll(expandTypeQueries(typeSpec.typeQuery()));
        }
        if (fallbackTypeItem != null) {
            String fallbackName = firstNonBlank(
                    fallbackTypeItem.getName(),
                    fallbackTypeItem.getNameRu(),
                    McoreUtil.getTypeName(fallbackTypeItem),
                    McoreUtil.getTypeNameRu(fallbackTypeItem));
            if (fallbackName != null) {
                queries.addAll(expandTypeQueries(fallbackName));
            }
        }
        if (queries.isEmpty()) {
            return null;
        }

        // First try namespace-bound transaction from the context BM object.
        TypeItem fromContext = findTypeItemInContextTransaction(contextObject, queries);
        if (fromContext != null) {
            return fromContext;
        }

        // Then resolve via platform namespace iterators.
        IBmNamespace namespace = resolveNamespace(contextObject);
        if (namespace != null) {
            IBmTransaction namespaceTx = transaction.getNamespaceBoundTransaction(namespace);
            TypeItem fromNamespaceTx = findTypeItemInTransaction(namespaceTx, queries);
            if (fromNamespaceTx != null) {
                return fromNamespaceTx;
            }
            TypeItem top = findTypeItem(transaction.getTopObjectIterator(namespace, McorePackage.eINSTANCE.getType()), queries);
            if (top != null) {
                return top;
            }
            TypeItem contained = findTypeItem(
                    transaction.getContainedObjectIterator(namespace, McorePackage.eINSTANCE.getType()),
                    queries);
            if (contained != null) {
                return contained;
            }
        }

        // Final fallback: if platform transaction is also namespace-bound transaction,
        // search all visible types from this transaction view.
        if (transaction instanceof IBmTransaction plainTx) {
            TypeItem fromPlainTx = findTypeItemInTransaction(plainTx, queries);
            if (fromPlainTx != null) {
                return fromPlainTx;
            }
        }
        return null;
    }

    private IBmNamespace resolveNamespace(EObject object) {
        if (object == null) {
            return null;
        }
        if (object instanceof IBmObject bmObject) {
            try {
                IBmNamespace namespace = bmObject.bmGetNamespace();
                if (namespace != null) {
                    return namespace;
                }
            } catch (RuntimeException e) {
                LOG.debug("Failed to read namespace from BM object=%s: %s", //$NON-NLS-1$
                        object.eClass().getName(),
                        e.getMessage());
            }
        }
        try {
            IBmModelManager modelManager = gateway.getBmModelManager();
            var model = modelManager.getModel(object);
            if (model == null) {
                return null;
            }
            IProject project = modelManager.getProject(model);
            if (project == null || !project.exists()) {
                return null;
            }
            return modelManager.getBmNamespace(project);
        } catch (RuntimeException e) {
            LOG.debug("Failed to resolve BM namespace for object=%s: %s", //$NON-NLS-1$
                    object.eClass().getName(),
                    e.getMessage());
            return null;
        }
    }

    private TypeItem findTypeItemInContextTransaction(EObject contextObject, Set<String> queries) {
        if (!(contextObject instanceof IBmObject bmObject)) {
            return null;
        }
        IBmTransaction tx;
        try {
            tx = bmObject.bmGetTransaction();
        } catch (RuntimeException e) {
            LOG.debug("Failed to read BM transaction from context=%s: %s", //$NON-NLS-1$
                    contextObject.eClass().getName(),
                    e.getMessage());
            return null;
        }
        return findTypeItemInTransaction(tx, queries);
    }

    private TypeItem findTypeItemInTransaction(IBmTransaction tx, Set<String> queries) {
        if (tx == null || queries == null || queries.isEmpty()) {
            return null;
        }
        TypeItem top = findTypeItem(tx.getTopObjectIterator(McorePackage.eINSTANCE.getType()), queries);
        if (top != null) {
            return top;
        }
        return findTypeItem(tx.getContainedObjectIterator(McorePackage.eINSTANCE.getType()), queries);
    }

    private String resolveTypeNameForQualifiers(TypeItem resolvedTypeItem, TypeSpec typeSpec) {
        String byTypeItem = firstNonBlank(
                resolvedTypeItem == null ? null : resolvedTypeItem.getName(),
                resolvedTypeItem == null ? null : resolvedTypeItem.getNameRu(),
                resolvedTypeItem == null ? null : McoreUtil.getTypeName(resolvedTypeItem),
                resolvedTypeItem == null ? null : McoreUtil.getTypeNameRu(resolvedTypeItem));
        if (byTypeItem != null) {
            return byTypeItem;
        }
        String byQuery = canonicalSimpleTypeName(typeSpec == null ? null : typeSpec.typeQuery());
        if (byQuery != null) {
            return byQuery;
        }
        return typeSpec == null ? null : typeSpec.typeQuery();
    }

    private void cacheResolvedTypeItem(Map<String, TypeItem> cache, String typeQuery, TypeItem item) {
        if (cache == null || item == null || typeQuery == null || typeQuery.isBlank()) {
            return;
        }
        cache.put(typeQuery, item);
        for (String alias : expandTypeQueries(typeQuery)) {
            cache.putIfAbsent(alias, item);
        }
    }

    private TypeItem lookupPreResolvedTypeItem(Map<String, TypeItem> cache, String typeQuery) {
        if (cache == null || typeQuery == null || typeQuery.isBlank()) {
            return null;
        }
        TypeItem direct = cache.get(typeQuery);
        if (direct != null) {
            return direct;
        }
        for (String alias : expandTypeQueries(typeQuery)) {
            TypeItem item = cache.get(alias);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    private TypeItem resolveTypeItemForFeature(
            BasicFeature feature,
            Configuration configuration,
            String typeQuery,
            Map<String, TypeItem> preResolvedTypes
    ) {
        if (typeQuery == null || typeQuery.isBlank()) {
            return null;
        }
        TypeItem fromFeature = resolveTypeItemFromFeature(feature, typeQuery);
        if (fromFeature != null) {
            cacheResolvedTypeItem(preResolvedTypes, typeQuery, fromFeature);
            return fromFeature;
        }
        TypeItem fromTypeProvider = resolveTypeItemFromTypeProvider(feature, configuration, typeQuery);
        if (fromTypeProvider != null) {
            cacheResolvedTypeItem(preResolvedTypes, typeQuery, fromTypeProvider);
            return fromTypeProvider;
        }
        TypeItem fromConfiguration = resolveSimpleTypeItemFromConfiguration(configuration, typeQuery);
        if (fromConfiguration != null) {
            cacheResolvedTypeItem(preResolvedTypes, typeQuery, fromConfiguration);
            return fromConfiguration;
        }
        return lookupPreResolvedTypeItem(preResolvedTypes, typeQuery);
    }

    private TypeItem resolveTypeItemFromFeature(BasicFeature feature, String typeQuery) {
        if (feature == null || typeQuery == null || typeQuery.isBlank()) {
            return null;
        }
        TypeDescription typeDescription = feature.getType();
        if (typeDescription == null || typeDescription.getTypes().isEmpty()) {
            return null;
        }
        Set<String> queries = expandTypeQueries(typeQuery);
        for (TypeItem item : typeDescription.getTypes()) {
            if (item != null && matchesTypeRef(item, queries)) {
                return item;
            }
        }
        return null;
    }

    private TypeItem resolveTypeItemFromTypeProvider(BasicFeature feature, EObject context, String typeQuery) {
        if (feature == null || typeQuery == null || typeQuery.isBlank()) {
            return null;
        }
        EReference typeReference = resolveTypeReference(feature);
        if (typeReference == null) {
            return null;
        }
        Set<String> queries = expandTypeQueries(typeQuery);
        try {
            TypeDescriptionInfoWithTypeInfo info = TypeProviderService.INSTANCE
                    .getTypeDescriptionInfoWithTypeInfo(feature, typeReference, null);
            TypeItem direct = findTypeItemInTypeInfo(info, queries);
            if (direct != null) {
                return direct;
            }
        } catch (RuntimeException e) {
            LOG.debug("TypeProviderService resolve failed for type=%s feature=%s: %s", //$NON-NLS-1$
                    typeQuery,
                    feature.eClass().getName(),
                    e.getMessage());
        }
        if (context == null) {
            return null;
        }
        try {
            TypeDescriptionInfoWithTypeInfo contextualInfo = TypeProviderService.INSTANCE
                    .getTypeDescriptionInfoWithTypeInfo(feature, context, typeReference, null);
            TypeItem contextual = findTypeItemInTypeInfo(contextualInfo, queries);
            if (contextual != null) {
                return contextual;
            }
            LOG.debug("TypeProviderService returned no matching types for type=%s feature=%s context=%s", //$NON-NLS-1$
                    typeQuery,
                    feature.eClass().getName(),
                    context.eClass().getName());
            return null;
        } catch (RuntimeException e) {
            LOG.debug("TypeProviderService contextual resolve failed for type=%s feature=%s context=%s: %s", //$NON-NLS-1$
                    typeQuery,
                    feature.eClass().getName(),
                    context.eClass().getName(),
                    e.getMessage());
            return null;
        }
    }

    private TypeItem findTypeItemInTypeInfo(TypeDescriptionInfoWithTypeInfo info, Set<String> queries) {
        if (info == null || info.getTypeInfos() == null || info.getTypeInfos().isEmpty() || queries == null
                || queries.isEmpty()) {
            return null;
        }
        for (String query : queries) {
            TypeItem byCode = findTypeItemByCode(info, query);
            if (byCode != null) {
                return byCode;
            }
        }
        for (TypeInfo typeInfo : info.getTypeInfos()) {
            if (typeInfo == null || typeInfo.getType() == null) {
                continue;
            }
            if (matchesTypeInfo(typeInfo, queries)) {
                return typeInfo.getType();
            }
        }
        return null;
    }

    private TypeItem findTypeItemByCode(TypeDescriptionInfoWithTypeInfo info, String query) {
        if (info == null || query == null || query.isBlank()) {
            return null;
        }
        TypeInfo nonSetType = info.getTypeInfo(query, false);
        if (nonSetType != null && nonSetType.getType() != null) {
            return nonSetType.getType();
        }
        TypeInfo typeSet = info.getTypeInfo(query, true);
        if (typeSet != null && typeSet.getType() != null) {
            return typeSet.getType();
        }
        return null;
    }

    private boolean matchesTypeInfo(TypeInfo typeInfo, Set<String> queries) {
        if (typeInfo == null || queries == null || queries.isEmpty()) {
            return false;
        }
        TypeItem typeItem = typeInfo.getType();
        if (typeItem != null && matchesTypeRef(typeItem, queries)) {
            return true;
        }
        String code = typeInfo.getCode() != null ? String.valueOf(typeInfo.getCode()) : null;
        String codeRu = typeInfo.getCodeRu() != null ? String.valueOf(typeInfo.getCodeRu()) : null;
        for (String query : queries) {
            if (matchesTypeToken(code, query) || matchesTypeToken(codeRu, query)) {
                return true;
            }
        }
        return false;
    }

    private void collectTypeCandidates(
            Map<String, FieldTypeCandidate> sink,
            TypeDescriptionInfoWithTypeInfo info
    ) {
        if (sink == null || info == null || info.getTypeInfos() == null || info.getTypeInfos().isEmpty()) {
            return;
        }
        for (TypeInfo typeInfo : info.getTypeInfos()) {
            if (typeInfo == null || typeInfo.getType() == null) {
                continue;
            }
            TypeItem type = typeInfo.getType();
            String name = firstNonBlank(type.getName(), ""); //$NON-NLS-1$
            String nameRu = firstNonBlank(type.getNameRu(), ""); //$NON-NLS-1$
            String code = typeInfo.getCode() != null ? String.valueOf(typeInfo.getCode()) : ""; //$NON-NLS-1$
            String codeRu = typeInfo.getCodeRu() != null ? String.valueOf(typeInfo.getCodeRu()) : ""; //$NON-NLS-1$
            String typeClass = typeInfo.getTypeClass() != null ? String.valueOf(typeInfo.getTypeClass()) : ""; //$NON-NLS-1$
            boolean simpleType = isSimpleTypeCandidate(name, nameRu, code, codeRu);
            String key = (name + "|" + nameRu + "|" + code + "|" + codeRu).toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sink.putIfAbsent(key, new FieldTypeCandidate(name, nameRu, code, codeRu, typeClass, simpleType));
        }
    }

    private EReference resolveTypeReference(BasicFeature feature) {
        if (feature == null) {
            return null;
        }
        EStructuralFeature resolved = resolveFeatureIgnoreCase(feature, "type"); //$NON-NLS-1$
        if (resolved instanceof EReference reference) {
            return reference;
        }
        return MdClassPackage.eINSTANCE.getBasicFeature_Type();
    }

    /**
     * Resolves a TypeItem from BM by name match.
     * <p>Must be called within a read transaction ({@link IBmTransaction}).</p>
     */
    private TypeItem resolveTypeItem(String typeString, IBmTransaction tx) {
        Set<String> queries = expandTypeQueries(typeString);
        if (queries.isEmpty()) {
            return null;
        }

        TypeItem found = findTypeItem(tx.getTopObjectIterator(McorePackage.eINSTANCE.getType()), queries);
        if (found != null) {
            return found;
        }
        return findTypeItem(tx.getContainedObjectIterator(McorePackage.eINSTANCE.getType()), queries);
    }

    private TypeItem resolveSimpleTypeItemFromConfiguration(Configuration configuration, String typeString) {
        if (configuration == null || !isSimpleTypeQuery(typeString)) {
            return null;
        }
        Set<String> queries = expandTypeQueries(typeString);
        if (queries.isEmpty()) {
            return null;
        }
        TypeDescription rootType = extractTypeDescriptionFromEObject(configuration);
        if (rootType != null) {
            TypeItem item = findMatchingTypeItem(rootType, queries);
            if (item != null) {
                return item;
            }
        }
        TreeIterator<EObject> iterator = configuration.eAllContents();
        while (iterator.hasNext()) {
            EObject node = iterator.next();
            TypeDescription existingType = extractTypeDescriptionFromEObject(node);
            if (existingType == null) {
                continue;
            }
            TypeItem item = findMatchingTypeItem(existingType, queries);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    private TypeItem findMatchingTypeItem(TypeDescription typeDescription, Set<String> queries) {
        if (typeDescription == null || typeDescription.getTypes() == null || typeDescription.getTypes().isEmpty()) {
            return null;
        }
        for (TypeItem item : typeDescription.getTypes()) {
            if (item != null && matchesTypeRef(item, queries)) {
                return item;
            }
        }
        return null;
    }

    private TypeDescription extractTypeDescriptionFromEObject(EObject node) {
        if (node == null) {
            return null;
        }
        if (node instanceof BasicFeature feature) {
            return feature.getType();
        }
        EStructuralFeature typeFeature = resolveStructuralFeatureIgnoreCase(node, "type"); //$NON-NLS-1$
        if (typeFeature != null) {
            Object typeValue = node.eGet(typeFeature);
            if (typeValue instanceof TypeDescription typeDescription) {
                return typeDescription;
            }
        }
        EStructuralFeature typeDescriptionFeature = resolveStructuralFeatureIgnoreCase(node, "typeDescription"); //$NON-NLS-1$
        if (typeDescriptionFeature != null) {
            Object typeDescriptionValue = node.eGet(typeDescriptionFeature);
            if (typeDescriptionValue instanceof TypeDescription typeDescription) {
                return typeDescription;
            }
        }
        return null;
    }

    private TypeItem findTypeItem(java.util.Iterator<IBmObject> iterator, Set<String> queries) {
        while (iterator.hasNext()) {
            IBmObject obj = iterator.next();
            if (obj instanceof TypeItem item && matchesTypeRef(item, queries)) {
                return item;
            }
        }
        return null;
    }

    private Set<String> expandTypeQueries(String rawType) {
        String normalized = rawType == null ? null : rawType.trim();
        if (normalized == null || normalized.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(normalized);

        int dot = normalized.indexOf('.');
        if (dot > 0 && dot + 1 < normalized.length()) {
            String prefix = normalized.substring(0, dot);
            String suffix = normalized.substring(dot + 1);
            String refPrefix = toRefPrefix(prefix);
            if (refPrefix != null) {
                queries.add(refPrefix + "." + suffix); //$NON-NLS-1$
            }
        }
        addSimpleTypeAliases(queries, normalized);
        return queries;
    }

    private void addSimpleTypeAliases(Set<String> queries, String normalized) {
        String base = normalized;
        int dot = normalized.indexOf('.');
        if (dot > 0) {
            base = normalized.substring(0, dot);
        }
        String token = normalizeToken(base);
        if (token == null || token.isBlank()) {
            return;
        }
        switch (token) {
            case "string", "строка" -> {
                queries.add("String"); //$NON-NLS-1$
                queries.add("Строка"); //$NON-NLS-1$
            }
            case "number", "число" -> {
                queries.add("Number"); //$NON-NLS-1$
                queries.add("Число"); //$NON-NLS-1$
            }
            case "date", "дата" -> {
                queries.add("Date"); //$NON-NLS-1$
                queries.add("Дата"); //$NON-NLS-1$
            }
            case "boolean", "булево", "bool" -> {
                queries.add("Boolean"); //$NON-NLS-1$
                queries.add("Булево"); //$NON-NLS-1$
            }
            default -> {
                // no-op
            }
        }
    }

    private String toRefPrefix(String prefix) {
        String token = normalizeToken(prefix);
        return switch (token) {
            case "catalog", "справочник", "catalogref", "справочникссылка" -> "CatalogRef"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "document", "документ", "documentref", "документссылка" -> "DocumentRef"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "enum", "перечисление", "enumref", "перечислениессылка" -> "EnumRef"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "chartofaccounts", "плансчетов", "chartofaccountsref", "плансчетовссылка" -> "ChartOfAccountsRef"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "chartofcharacteristictypes", "планвидовхарактеристик", "chartofcharacteristictypesref", "планвидовхарактеристикссылка" -> "ChartOfCharacteristicTypesRef"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "chartofcalculationtypes", "планвидоврасчета", "chartofcalculationtypesref", "планвидоврасчетассылка" -> "ChartOfCalculationTypesRef"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "task", "задача", "taskref", "задачассылка" -> "TaskRef"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "businessprocess", "бизнеспроцесс", "businessprocessref", "бизнеспроцессссылка" -> "BusinessProcessRef"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            default -> null;
        };
    }

    private boolean matchesTypeRef(TypeItem item, Set<String> queries) {
        for (String query : queries) {
            if (matchesTypeRef(item, query)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesTypeRef(TypeItem item, String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalizedQuery = query.trim();
        String name = item.getName();
        String nameRu = item.getNameRu();
        String typeName = McoreUtil.getTypeName(item);
        String typeNameRu = McoreUtil.getTypeNameRu(item);
        return matchesTypeToken(name, normalizedQuery)
                || matchesTypeToken(nameRu, normalizedQuery)
                || matchesTypeToken(typeName, normalizedQuery)
                || matchesTypeToken(typeNameRu, normalizedQuery);
    }

    private boolean matchesTypeToken(String candidate, String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return equalsIgnoreCaseSafe(query, candidate) || endsWithTypeSegment(candidate, query);
    }

    private boolean equalsIgnoreCaseSafe(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private boolean endsWithTypeSegment(String candidate, String query) {
        if (candidate == null || query == null || query.isBlank()) {
            return false;
        }
        if (candidate.equalsIgnoreCase(query)) {
            return true;
        }
        String suffix = "." + query; //$NON-NLS-1$
        if (candidate.length() <= suffix.length()) {
            return false;
        }
        return candidate.regionMatches(true, candidate.length() - suffix.length(), suffix, 0, suffix.length());
    }

    private boolean isNumberType(String name) {
        return name != null
                && (name.equalsIgnoreCase("Number") || name.equalsIgnoreCase("Число")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private boolean isStringType(String name) {
        return name != null
                && (name.equalsIgnoreCase("String") || name.equalsIgnoreCase("Строка")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private boolean isDateType(String name) {
        return name != null
                && (name.equalsIgnoreCase("Date") || name.equalsIgnoreCase("Дата")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private boolean isBooleanType(String name) {
        return name != null
                && (name.equalsIgnoreCase("Boolean")
                        || name.equalsIgnoreCase("Булево")
                        || name.equalsIgnoreCase("Bool")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private boolean isSimpleTypeCandidate(String name, String nameRu, String code, String codeRu) {
        return isSimpleTypeToken(name)
                || isSimpleTypeToken(nameRu)
                || isSimpleTypeToken(code)
                || isSimpleTypeToken(codeRu);
    }

    private boolean isSimpleTypeToken(String token) {
        return isStringType(token)
                || isNumberType(token)
                || isDateType(token)
                || isBooleanType(token);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSetMap(Map<String, Object> changes) {
        Object setObj = changes.get("set"); //$NON-NLS-1$
        if (setObj instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    /**
     * Collects all "type" string values from changes (top-level set and children_ops).
     */
    @SuppressWarnings("unchecked")
    private Set<String> collectTypeStrings(Map<String, Object> changes) {
        Set<String> typeStrings = new LinkedHashSet<>();
        // Top-level set.type
        Map<String, Object> setMap = extractSetMap(changes);
        if (setMap != null) {
            if (hasMapKeyIgnoreCase(setMap, "type")) { //$NON-NLS-1$
                String typeStr = normalizeTypeLookupQuery(getMapValueIgnoreCase(setMap, "type")); //$NON-NLS-1$
                if (typeStr != null && !typeStr.isBlank()) {
                    typeStrings.add(typeStr);
                }
            }
            // Also scan set values that are Maps containing "type"
            // (auto-redirect case: {"set":{"AttrName":{"type":"CatalogRef.Foo"}}})
            for (Object val : setMap.values()) {
                if (val instanceof Map<?, ?> nestedMap) {
                    String ts = normalizeTypeLookupQuery(getMapValueIgnoreCase((Map<String, Object>) nestedMap, "type")); //$NON-NLS-1$
                    if (ts != null && !ts.isBlank()) {
                        typeStrings.add(ts);
                    }
                } else if (val instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> nestedItemMap) {
                            String ts = normalizeTypeLookupQuery(getMapValueIgnoreCase((Map<String, Object>) nestedItemMap, "type")); //$NON-NLS-1$
                            if (ts != null && !ts.isBlank()) {
                                typeStrings.add(ts);
                            }
                        }
                    }
                }
            }
        }
        // children_ops[].set.type and children_ops[].changes.set.type
        List<Map<String, Object>> childOps = asListOfMaps(changes.get("children_ops")); //$NON-NLS-1$
        for (Map<String, Object> op : childOps) {
            Object setObj = getMapValueIgnoreCase(op, "set"); //$NON-NLS-1$
            if (setObj instanceof Map<?, ?> childSet) {
                String ts = normalizeTypeLookupQuery(getMapValueIgnoreCase((Map<String, Object>) childSet, "type")); //$NON-NLS-1$
                if (ts != null && !ts.isBlank()) {
                    typeStrings.add(ts);
                }
            }
            // shorthand support in children_ops:
            // 1) {op:"update", child_fqn:"...", type:"String.50"}
            // 2) {op:"update", child_fqn:"...", properties:{type:{...}}}
            String opType = normalizeTypeLookupQuery(getMapValueIgnoreCase(op, "type")); //$NON-NLS-1$
            if (opType != null && !opType.isBlank()) {
                typeStrings.add(opType);
            }
            Object propertiesObj = getMapValueIgnoreCase(op, "properties"); //$NON-NLS-1$
            if (propertiesObj instanceof Map<?, ?> propertiesMap) {
                String propsType = normalizeTypeLookupQuery(getMapValueIgnoreCase((Map<String, Object>) propertiesMap, "type")); //$NON-NLS-1$
                if (propsType != null && !propsType.isBlank()) {
                    typeStrings.add(propsType);
                }
            }
            Object changesObj = op.get("changes"); //$NON-NLS-1$
            if (changesObj instanceof Map<?, ?> nestedChanges) {
                typeStrings.addAll(collectTypeStrings((Map<String, Object>) nestedChanges));
            }
        }
        return typeStrings;
    }

    @SuppressWarnings("unchecked")
    private String normalizeTypeLookupQuery(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String normalized = normalizeTypeLookupQuery(item);
                if (normalized != null) {
                    return normalized;
                }
            }
            return null;
        }
        if (value instanceof String str) {
            String trimmed = str.trim();
            return trimmed.isBlank() ? null : trimmed;
        }
        if (value instanceof Map<?, ?> map) {
            Object nestedType = getMapValueIgnoreCase(map, "type"); //$NON-NLS-1$
            if (nestedType != null && nestedType != value) {
                return normalizeTypeLookupQuery(nestedType);
            }
            Object types = getMapValueIgnoreCase(map, "types"); //$NON-NLS-1$
            if (types != null && types != value) {
                String normalizedTypes = normalizeTypeLookupQuery(types);
                if (normalizedTypes != null) {
                    return normalizedTypes;
                }
            }
            Object directValue = getMapValueIgnoreCase(map, "value"); //$NON-NLS-1$
            if (directValue != null && directValue != value) {
                String normalizedValue = normalizeTypeLookupQuery(directValue);
                if (normalizedValue != null) {
                    return normalizedValue;
                }
            }
            Object name = getMapValueIgnoreCase(map, "name"); //$NON-NLS-1$
            if (name != null) {
                String normalizedName = normalizeTypeLookupQuery(name);
                if (normalizedName != null) {
                    return normalizedName;
                }
            }
            Object nameRu = getMapValueIgnoreCase(map, "nameRu"); //$NON-NLS-1$
            if (nameRu != null) {
                String normalizedNameRu = normalizeTypeLookupQuery(nameRu);
                if (normalizedNameRu != null) {
                    return normalizedNameRu;
                }
            }
            Object code = getMapValueIgnoreCase(map, "code"); //$NON-NLS-1$
            if (code != null) {
                String normalizedCode = normalizeTypeLookupQuery(code);
                if (normalizedCode != null) {
                    return normalizedCode;
                }
            }
            Object codeRu = getMapValueIgnoreCase(map, "codeRu"); //$NON-NLS-1$
            if (codeRu != null) {
                String normalizedCodeRu = normalizeTypeLookupQuery(codeRu);
                if (normalizedCodeRu != null) {
                    return normalizedCodeRu;
                }
            }
            Object catalog = getMapValueIgnoreCase(map, "catalog"); //$NON-NLS-1$
            if (catalog != null) {
                String catalogName = String.valueOf(catalog).trim();
                if (!catalogName.isBlank()) {
                    return "CatalogRef." + catalogName; //$NON-NLS-1$
                }
            }
            Object document = getMapValueIgnoreCase(map, "document"); //$NON-NLS-1$
            if (document != null) {
                String documentName = String.valueOf(document).trim();
                if (!documentName.isBlank()) {
                    return "DocumentRef." + documentName; //$NON-NLS-1$
                }
            }
            Object enumeration = getMapValueIgnoreCase(map, "enum"); //$NON-NLS-1$
            if (enumeration != null) {
                String enumName = String.valueOf(enumeration).trim();
                if (!enumName.isBlank()) {
                    return "EnumRef." + enumName; //$NON-NLS-1$
                }
            }
            Object fqn = getMapValueIgnoreCase(map, "fqn"); //$NON-NLS-1$
            if (fqn != null) {
                return normalizeTypeLookupQuery(fqn);
            }
            return null;
        }
        String fallback = String.valueOf(value).trim();
        return fallback.isBlank() ? null : fallback;
    }

    private TypeSpec normalizeTypeSpec(Object value) {
        if (value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Type value cannot be null", false); //$NON-NLS-1$
        }
        InlineTypeSpec inline = parseInlineTypeSpec(value);
        Map<String, Object> root = asMap(value);
        Object rootType = getMapValueIgnoreCase(root, "type"); //$NON-NLS-1$
        Object typeCarrier = rootType != null ? rootType : value;
        String typeQuery = normalizeTypeLookupQuery(typeCarrier);
        if ((typeQuery == null || typeQuery.isBlank()) && inline != null) {
            typeQuery = inline.typeQuery();
        }
        if (inline != null && typeQuery != null && typeQuery.equalsIgnoreCase(inline.rawLiteral())) {
            typeQuery = inline.typeQuery();
        }
        if (typeQuery == null || typeQuery.isBlank()) {
            typeQuery = normalizeTypeLookupQuery(value);
        }
        if ((typeQuery == null || typeQuery.isBlank()) && inline != null) {
            typeQuery = inline.typeQuery();
        }
        if (typeQuery == null || typeQuery.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Type query is empty or invalid: " + value, false); //$NON-NLS-1$
        }

        Map<String, Object> nestedTypeMap = !root.isEmpty() ? asMap(getMapValueIgnoreCase(root, "type")) : Map.of(); //$NON-NLS-1$
        Map<String, Object> stringQualifiers = mergeMaps(
                asMap(getMapValueIgnoreCase(root, "stringQualifiers")), //$NON-NLS-1$
                asMap(getMapValueIgnoreCase(nestedTypeMap, "stringQualifiers"))); //$NON-NLS-1$
        Map<String, Object> numberQualifiers = mergeMaps(
                asMap(getMapValueIgnoreCase(root, "numberQualifiers")), //$NON-NLS-1$
                asMap(getMapValueIgnoreCase(nestedTypeMap, "numberQualifiers"))); //$NON-NLS-1$
        Map<String, Object> dateQualifiers = mergeMaps(
                asMap(getMapValueIgnoreCase(root, "dateQualifiers")), //$NON-NLS-1$
                asMap(getMapValueIgnoreCase(nestedTypeMap, "dateQualifiers"))); //$NON-NLS-1$

        Integer stringLength = firstParsedInteger(
                getMapValueIgnoreCase(stringQualifiers, "length"), //$NON-NLS-1$
                getMapValueIgnoreCase(root, "stringLength"), //$NON-NLS-1$
                getMapValueIgnoreCase(nestedTypeMap, "stringLength"), //$NON-NLS-1$
                inline == null ? null : inline.stringLength());
        Boolean stringFixed = firstParsedBoolean(
                getMapValueIgnoreCase(stringQualifiers, "fixed"), //$NON-NLS-1$
                getMapValueIgnoreCase(stringQualifiers, "fixedLength"), //$NON-NLS-1$
                getMapValueIgnoreCase(root, "stringFixed"), //$NON-NLS-1$
                getMapValueIgnoreCase(nestedTypeMap, "stringFixed")); //$NON-NLS-1$

        Integer numberPrecision = firstParsedInteger(
                getMapValueIgnoreCase(numberQualifiers, "precision"), //$NON-NLS-1$
                getMapValueIgnoreCase(numberQualifiers, "length"), //$NON-NLS-1$
                getMapValueIgnoreCase(root, "precision"), //$NON-NLS-1$
                getMapValueIgnoreCase(nestedTypeMap, "precision"), //$NON-NLS-1$
                inline == null ? null : inline.numberPrecision());
        Integer numberScale = firstParsedInteger(
                getMapValueIgnoreCase(numberQualifiers, "scale"), //$NON-NLS-1$
                getMapValueIgnoreCase(root, "scale"), //$NON-NLS-1$
                getMapValueIgnoreCase(nestedTypeMap, "scale"), //$NON-NLS-1$
                inline == null ? null : inline.numberScale());
        Boolean numberNonNegative = firstParsedBoolean(
                getMapValueIgnoreCase(numberQualifiers, "nonNegative"), //$NON-NLS-1$
                getMapValueIgnoreCase(root, "nonNegative"), //$NON-NLS-1$
                getMapValueIgnoreCase(nestedTypeMap, "nonNegative")); //$NON-NLS-1$

        DateFractions dateFractions = firstParsedDateFractions(
                getMapValueIgnoreCase(dateQualifiers, "dateFractions"), //$NON-NLS-1$
                getMapValueIgnoreCase(dateQualifiers, "fractions"), //$NON-NLS-1$
                getMapValueIgnoreCase(root, "dateFractions"), //$NON-NLS-1$
                getMapValueIgnoreCase(root, "fractions"), //$NON-NLS-1$
                getMapValueIgnoreCase(nestedTypeMap, "dateFractions"), //$NON-NLS-1$
                getMapValueIgnoreCase(nestedTypeMap, "fractions"), //$NON-NLS-1$
                inline == null ? null : inline.dateFractions());

        return new TypeSpec(
                typeQuery,
                stringLength,
                stringFixed,
                numberPrecision,
                numberScale,
                numberNonNegative,
                dateFractions);
    }

    private record InlineTypeSpec(
            String rawLiteral,
            String typeQuery,
            Integer stringLength,
            Integer numberPrecision,
            Integer numberScale,
            DateFractions dateFractions
    ) {
    }

    private InlineTypeSpec parseInlineTypeSpec(Object value) {
        if (!(value instanceof String literal)) {
            return null;
        }
        String raw = literal.trim();
        if (raw.isBlank()) {
            return null;
        }
        int open = raw.indexOf('(');
        int close = raw.lastIndexOf(')');
        if (open <= 0 || close <= open) {
            return null;
        }

        String baseRaw = raw.substring(0, open).trim();
        String argsRaw = raw.substring(open + 1, close).trim();
        if (baseRaw.isBlank()) {
            return null;
        }
        String baseType = canonicalSimpleTypeName(baseRaw);
        if (baseType == null) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        if (!argsRaw.isBlank()) {
            for (String piece : argsRaw.split(",")) { //$NON-NLS-1$
                String token = piece.trim();
                if (!token.isBlank()) {
                    parts.add(token);
                }
            }
        }

        if (isStringType(baseType)) {
            Integer length = parts.isEmpty() ? null : parseInteger(parts.get(0));
            return new InlineTypeSpec(raw, baseType, length, null, null, null);
        }
        if (isNumberType(baseType)) {
            Integer precision = parts.isEmpty() ? null : parseInteger(parts.get(0));
            Integer scale = parts.size() > 1 ? parseInteger(parts.get(1)) : null;
            return new InlineTypeSpec(raw, baseType, null, precision, scale, null);
        }
        if (isDateType(baseType)) {
            DateFractions fractions = parts.isEmpty() ? null : parseDateFractions(parts.get(0));
            return new InlineTypeSpec(raw, baseType, null, null, null, fractions);
        }
        return new InlineTypeSpec(raw, baseType, null, null, null, null);
    }

    private Map<String, Object> mergeMaps(Map<String, Object> primary, Map<String, Object> secondary) {
        if ((primary == null || primary.isEmpty()) && (secondary == null || secondary.isEmpty())) {
            return Map.of();
        }
        Map<String, Object> merged = new HashMap<>();
        if (secondary != null && !secondary.isEmpty()) {
            merged.putAll(secondary);
        }
        if (primary != null && !primary.isEmpty()) {
            merged.putAll(primary);
        }
        return merged;
    }

    private Integer firstParsedInteger(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            Integer parsed = parseInteger(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Boolean firstParsedBoolean(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            Boolean parsed = parseBoolean(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private DateFractions firstParsedDateFractions(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            DateFractions parsed = parseDateFractions(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get("value"); //$NON-NLS-1$
            if (nested != null && nested != value) {
                return parseInteger(nested);
            }
            return null;
        }
        if (value instanceof Number number) {
            return Integer.valueOf(number.intValue());
        }
        String raw = String.valueOf(value).trim();
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get("value"); //$NON-NLS-1$
            if (nested != null && nested != value) {
                return parseBoolean(nested);
            }
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isBlank()) {
            return null;
        }
        if ("1".equals(raw)) { //$NON-NLS-1$
            return Boolean.TRUE;
        }
        if ("0".equals(raw)) { //$NON-NLS-1$
            return Boolean.FALSE;
        }
        if ("yes".equalsIgnoreCase(raw) || "true".equalsIgnoreCase(raw)) { //$NON-NLS-1$ //$NON-NLS-2$
            return Boolean.TRUE;
        }
        if ("no".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) { //$NON-NLS-1$ //$NON-NLS-2$
            return Boolean.FALSE;
        }
        return null;
    }

    private DateFractions parseDateFractions(Object value) {
        if (value == null) {
            return null;
        }
        String raw = normalizeTypeLookupQuery(value);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String token = normalizeToken(raw);
        return switch (token) {
            case "date", "дата" -> DateFractions.DATE; //$NON-NLS-1$ //$NON-NLS-2$
            case "time", "время" -> DateFractions.TIME; //$NON-NLS-1$ //$NON-NLS-2$
            case "datetime", "date_time", "dateandtime", "датавремя", "датиивремя" -> DateFractions.DATE_TIME; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            default -> DateFractions.getByName(raw);
        };
    }

    private Integer firstPositive(Integer first, Integer second, int fallback) {
        if (first != null && first.intValue() > 0) {
            return first;
        }
        if (second != null && second.intValue() > 0) {
            return second;
        }
        return Integer.valueOf(fallback);
    }

    private Integer firstNonNegative(Integer first, Integer second, int fallback) {
        if (first != null && first.intValue() >= 0) {
            return first;
        }
        if (second != null && second.intValue() >= 0) {
            return second;
        }
        return Integer.valueOf(fallback);
    }

    private boolean isSimpleTypeQuery(String typeString) {
        return canonicalSimpleTypeName(typeString) != null;
    }

    private String canonicalSimpleTypeName(String typeString) {
        if (typeString == null || typeString.isBlank()) {
            return null;
        }
        String base = typeString.trim();
        int openParen = base.indexOf('(');
        if (openParen > 0) {
            base = base.substring(0, openParen).trim();
        }
        int dot = base.indexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String token = normalizeToken(base);
        return switch (token) {
            case "string", "строка" -> "String"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "number", "число" -> "Number"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "date", "дата" -> "Date"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "boolean", "bool", "булево" -> "Boolean"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            default -> null;
        };
    }

    private void unsetFeatureValue(MdObject target, String fieldName) {
        if ("uuid".equalsIgnoreCase(fieldName)) { //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Cannot unset required field: uuid", false); //$NON-NLS-1$
        }
        EStructuralFeature feature = resolveFeatureIgnoreCase(target, fieldName);
        if (feature == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Unknown metadata field: " + fieldName, false); //$NON-NLS-1$
        }
        if (feature.isDerived() || feature.isTransient() || feature.isVolatile()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Field cannot be unset: " + fieldName, false); //$NON-NLS-1$
        }
        if (feature instanceof EReference && feature.isMany()) {
            Object raw = target.eGet(feature);
            if (raw instanceof Collection<?> collection) {
                collection.clear();
                return;
            }
        }
        target.eUnset(feature);
    }

    @SuppressWarnings("unchecked")
    private void applyReferenceValue(
            Configuration configuration,
            MdObject target,
            EReference reference,
            Object value
    ) {
        if (reference.isContainment()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Containment reference updates are not supported in set. Use children_ops/add_metadata_child: "
                            + reference.getName(),
                    false); //$NON-NLS-1$
        }

        if (reference.isMany()) {
            Collection<Object> resolved = resolveReferenceValues(configuration, reference, value);
            Object raw = target.eGet(reference);
            if (raw instanceof Collection<?> current) {
                Collection<Object> typed = (Collection<Object>) current;
                typed.clear();
                typed.addAll(resolved);
                return;
            }
            target.eSet(reference, resolved);
            return;
        }

        Object resolved = resolveSingleReferenceValue(configuration, reference, value);
        target.eSet(reference, resolved);
    }

    private Collection<Object> resolveReferenceValues(
            Configuration configuration,
            EReference reference,
            Object rawValue
    ) {
        List<?> source;
        if (rawValue == null) {
            source = List.of();
        } else if (rawValue instanceof List<?> list) {
            source = list;
        } else {
            source = List.of(rawValue);
        }

        List<Object> resolved = new ArrayList<>(source.size());
        for (Object item : source) {
            Object value = resolveSingleReferenceValue(configuration, reference, item);
            if (value != null) {
                resolved.add(value);
            }
        }
        return resolved;
    }

    private Object resolveSingleReferenceValue(
            Configuration configuration,
            EReference reference,
            Object rawValue
    ) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof EObject eObject) {
            ensureReferenceTypeCompatible(reference, eObject, rawValue);
            return eObject;
        }

        String fqn = extractReferenceFqn(rawValue);
        if (fqn == null || fqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Reference value must be metadata FQN or object with fqn/target_fqn for field: "
                            + reference.getName(),
                    false); //$NON-NLS-1$
        }
        MdObject resolved = resolveByFqn(configuration, fqn);
        if (resolved == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Referenced metadata object not found: " + fqn,
                    false); //$NON-NLS-1$
        }
        ensureReferenceTypeCompatible(reference, resolved, fqn);
        return resolved;
    }

    private void ensureReferenceTypeCompatible(EReference reference, EObject resolved, Object sourceValue) {
        if (!reference.getEReferenceType().isSuperTypeOf(resolved.eClass())) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Referenced object has incompatible type for field " + reference.getName() + ": " + sourceValue,
                    false); //$NON-NLS-1$
        }
    }

    @SuppressWarnings("unchecked")
    private String extractReferenceFqn(Object rawValue) {
        if (rawValue instanceof String str) {
            return str;
        }
        if (rawValue instanceof Map<?, ?> map) {
            Object fqn = map.get("fqn"); //$NON-NLS-1$
            if (fqn == null) {
                fqn = map.get("target_fqn"); //$NON-NLS-1$
            }
            return fqn == null ? null : String.valueOf(fqn);
        }
        return null;
    }

    private Object convertAttributeValue(EAttribute attribute, Object value) {
        if (value == null) {
            return null;
        }

        EDataType dataType = attribute.getEAttributeType();
        Class<?> instanceClass = dataType != null ? dataType.getInstanceClass() : null;
        if (instanceClass == null) {
            return value;
        }
        if (instanceClass.isInstance(value)) {
            return value;
        }

        String raw = String.valueOf(value);
        try {
            if (instanceClass == String.class) {
                return raw;
            }
            if (instanceClass == Integer.class || instanceClass == int.class) {
                return Integer.valueOf(raw);
            }
            if (instanceClass == Long.class || instanceClass == long.class) {
                return Long.valueOf(raw);
            }
            if (instanceClass == Double.class || instanceClass == double.class) {
                return Double.valueOf(raw);
            }
            if (instanceClass == Float.class || instanceClass == float.class) {
                return Float.valueOf(raw);
            }
            if (instanceClass == Boolean.class || instanceClass == boolean.class) {
                return Boolean.valueOf(raw);
            }
            if (instanceClass.isEnum()) {
                Object[] constants = instanceClass.getEnumConstants();
                if (constants != null) {
                    for (Object constant : constants) {
                        if (constant != null && raw.equalsIgnoreCase(String.valueOf(constant))) {
                            return constant;
                        }
                    }
                }
            }
            if (dataType instanceof EEnum eEnum) {
                EEnumLiteral literal = eEnum.getEEnumLiteralByLiteral(raw);
                if (literal == null) {
                    literal = eEnum.getEEnumLiteral(raw);
                }
                if (literal == null) {
                    for (EEnumLiteral candidate : eEnum.getELiterals()) {
                        if (candidate != null && raw.equalsIgnoreCase(candidate.getName())) {
                            literal = candidate;
                            break;
                        }
                    }
                }
                if (literal != null) {
                    return literal.getInstance();
                }
            }
        } catch (RuntimeException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Invalid value for field " + attribute.getName() + ": " + raw, false, e); //$NON-NLS-1$ //$NON-NLS-2$
        }

        throw new MetadataOperationException(
                MetadataOperationCode.INVALID_METADATA_CHANGE,
                "Unsupported value type for field " + attribute.getName() + ": " + value, false); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private boolean hasNestedMetadataChildren(MdObject target) {
        for (EStructuralFeature feature : target.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference reference) || !reference.isContainment()) {
                continue;
            }
            if (feature.isMany()) {
                Object raw = target.eGet(feature);
                if (raw instanceof Collection<?> collection && !collection.isEmpty()) {
                    return true;
                }
                continue;
            }
            if (target.eGet(feature) != null) {
                return true;
            }
        }
        return false;
    }

    private void removeMetadataObject(Configuration configuration, String fqn, MdObject target) {
        if (isTopLevelFqn(fqn)) {
            MetadataKind kind = metadataKindByFqn(fqn);
            removeTopLevelObjectLinks(configuration, kind, target.getName());
            return;
        }
        EObject container = target.eContainer();
        EStructuralFeature containment = target.eContainmentFeature();
        if (container == null || containment == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Cannot resolve container for metadata object: " + fqn, false); //$NON-NLS-1$
        }
        if (containment.isMany()) {
            @SuppressWarnings("unchecked")
            Collection<EObject> children = (Collection<EObject>) container.eGet(containment);
            if (children != null) {
                children.remove(target);
            }
        } else {
            container.eSet(containment, null);
        }
    }

    private MetadataKind metadataKindByFqn(String fqn) {
        String[] parts = fqn != null ? fqn.split("\\.") : new String[0]; //$NON-NLS-1$
        if (parts.length < 2) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    "Invalid metadata FQN: " + fqn, false); //$NON-NLS-1$
        }
        return MetadataKind.fromString(parts[0]);
    }

    private boolean isTopLevelFqn(String fqn) {
        String[] parts = fqn != null ? fqn.split("\\.") : new String[0]; //$NON-NLS-1$
        return parts.length == 2;
    }

    private String extractTopLevelFqn(String fqn) {
        String[] parts = fqn != null ? fqn.split("\\.") : new String[0]; //$NON-NLS-1$
        if (parts.length < 2) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Invalid metadata FQN: " + fqn, false); //$NON-NLS-1$
        }
        return parts[0] + "." + parts[1]; //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private Object getMapValueIgnoreCase(Map<?, ?> map, String key) {
        if (map == null || map.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        if (map.containsKey(key)) {
            return map.get(key);
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object rawKey = entry.getKey();
            if (rawKey instanceof String str && str.equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean hasMapKeyIgnoreCase(Map<?, ?> map, String key) {
        if (map == null || map.isEmpty() || key == null || key.isBlank()) {
            return false;
        }
        if (map.containsKey(key)) {
            return true;
        }
        for (Object rawKey : map.keySet()) {
            if (rawKey instanceof String str && str.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private EStructuralFeature resolveFeatureIgnoreCase(MdObject target, String fieldName) {
        if (target == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        return resolveStructuralFeatureIgnoreCase(target, fieldName);
    }

    private EStructuralFeature resolveStructuralFeatureIgnoreCase(EObject object, String fieldName) {
        if (object == null || object.eClass() == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        EStructuralFeature direct = object.eClass().getEStructuralFeature(fieldName);
        if (direct != null) {
            return direct;
        }
        for (EStructuralFeature candidate : object.eClass().getEAllStructuralFeatures()) {
            if (candidate != null && candidate.getName().equalsIgnoreCase(fieldName)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool.booleanValue();
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
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

    private void verifyObjectRemoved(IProject project, String fqn, String opId) {
        IConfigurationProvider configurationProvider = gateway.getConfigurationProvider();
        Configuration configuration = configurationProvider.getConfiguration(project);
        boolean exists = executeRead(project, tx -> {
            Configuration txConfiguration = tx.toTransactionObject(configuration);
            return txConfiguration != null && resolveByFqn(txConfiguration, fqn) != null;
        });
        if (exists) {
            LOG.error("[%s] Post-verify failed: object still exists by FQN=%s", opId, fqn); //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Metadata object still exists after delete: " + fqn, true); //$NON-NLS-1$
        }
        LOG.debug("[%s] Post-verify remove passed for FQN=%s", opId, fqn); //$NON-NLS-1$
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

    private record ModuleTarget(
            String className,
            String resourcePath,
            String topKind,
            String topName,
            String formName
    ) {
    }

    private record FormArtifactPaths(
            String formAbsolutePath,
            String moduleAbsolutePath,
            String diagnostics
    ) {
    }
}
