package com.codepilot1c.core.edt.rights;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractRoleDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.rights.IRightInfosService;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.RestrictionTemplate;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com._1c.g5.v8.dt.rights.model.util.RightName;
import com._1c.g5.v8.dt.rights.model.util.RightsModelUtil;
import com.codepilot1c.core.edt.BmObjectHelper;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataConfigurationCollections;
import com.codepilot1c.core.edt.metadata.MetadataKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Read and mutate 1C role access rights (the EDT rights model stored under a {@code Role}).
 *
 * <p>Roles keep rights not in the {@code .mdo} but in a separate {@code RoleDescription}
 * ({@link Role#getRights()}): per-object {@link ObjectRights} → {@link ObjectRight} ({@code Right} +
 * {@code RightValue} SET/UNSET/PROVIDED, plus optional RLS), three default flags, and RLS templates.
 * Mutation runs through {@link EdtMetadataService#mutateTopObjectAndExport} (BM write transaction +
 * filesystem export) and resolves rights via {@link IRightInfosService} and {@code RightsModelUtil};
 * RLS (per-record restrictions) is intentionally out of scope here (phase 2).</p>
 */
public class EdtRoleRightsService {

    private static final String RIGHT_NOT_AVAILABLE = "RIGHT_NOT_AVAILABLE"; //$NON-NLS-1$
    private static final String AVAILABLE_CONFIG_RIGHTS_DIAGNOSTIC = "available configuration rights"; //$NON-NLS-1$
    private static final List<String> FALLBACK_EXTENSION_CONFIG_RIGHTS = List.of(
            "ThinClient", "WebClient", "ExternalConnection", "Automation", "MobileClient", "DesktopClient"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtRoleRightsService.class);
    private static final String ROLE_PREFIX = "Role."; //$NON-NLS-1$

    private final EdtMetadataGateway gateway;
    private final EdtMetadataService metadataService;

    public EdtRoleRightsService() {
        this(new EdtMetadataGateway());
    }

    public EdtRoleRightsService(EdtMetadataGateway gateway) {
        this.gateway = gateway == null ? new EdtMetadataGateway() : gateway;
        this.metadataService = new EdtMetadataService(this.gateway);
    }

    // ---------------------------------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads the rights model of a role.
     *
     * @param projectName  EDT project name
     * @param role         role name or {@code Role.<Name>} FQN
     * @param objectFilter optional case-insensitive substring to keep only matching object FQNs
     */
    public RoleRightsSnapshot inspectRoleRights(String projectName, String role, String objectFilter) {
        IProject project = requireProject(projectName);
        String roleFqn = normalizeRoleFqn(role);
        String filter = objectFilter == null || objectFilter.isBlank()
                ? null
                : objectFilter.trim().toLowerCase(Locale.ROOT);
        LOG.debug("inspectRoleRights project=%s role=%s", projectName, roleFqn); //$NON-NLS-1$
        try {
            ReadTransactionTask<RoleRightsSnapshot> task =
                    transaction -> readSnapshot(transaction, projectName, roleFqn, filter);
            return gateway.getBmModelManager().executeReadOnlyTask(project, task::execute);
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Role rights read failed: " + e.getMessage(), true, e); //$NON-NLS-1$
        }
    }

    private RoleRightsSnapshot readSnapshot(IBmTransaction transaction, String projectName, String roleFqn,
            String filter) {
        Object top = transaction.getTopObjectByFqn(roleFqn);
        if (!(top instanceof Role role)) {
            throw new MetadataOperationException(MetadataOperationCode.METADATA_NOT_FOUND,
                    "Role not found: " + roleFqn, false); //$NON-NLS-1$
        }
        AbstractRoleDescription description = role.getRights();
        if (!(description instanceof RoleDescription roleDescription)) {
            return new RoleRightsSnapshot(projectName, roleFqn, role.getName(), false,
                    false, false, false, List.of(), List.of());
        }
        List<String> templates = new ArrayList<>();
        for (RestrictionTemplate template : roleDescription.getTemplates()) {
            if (template != null && template.getName() != null) {
                templates.add(template.getName());
            }
        }
        List<ObjectRightsView> objects = new ArrayList<>();
        for (ObjectRights objectRights : roleDescription.getRights()) {
            if (objectRights == null) {
                continue;
            }
            String objectFqn = resolveObjectFqn(objectRights.getObject());
            if (filter != null && (objectFqn == null || !objectFqn.toLowerCase(Locale.ROOT).contains(filter))) {
                continue;
            }
            List<RightView> rights = new ArrayList<>();
            for (ObjectRight objectRight : objectRights.getRights()) {
                if (objectRight == null || objectRight.getRight() == null) {
                    continue;
                }
                Right right = objectRight.getRight();
                String value = objectRight.getValue() == null ? "UNSET" : objectRight.getValue().getName(); //$NON-NLS-1$
                boolean hasRls = objectRight.getRestrictionsByCondition() != null
                        && !objectRight.getRestrictionsByCondition().isEmpty();
                rights.add(new RightView(right.getName(), safeNameRu(right), value, hasRls));
            }
            objects.add(new ObjectRightsView(objectFqn, resolveObjectKind(objectRights.getObject()), rights));
        }
        return new RoleRightsSnapshot(projectName, roleFqn, role.getName(), true,
                roleDescription.isSetForNewObjects(), roleDescription.isSetForAttributesByDefault(),
                roleDescription.isIndependentRightsOfChildObjects(), templates, objects);
    }

    // ---------------------------------------------------------------------------------------------
    // Mutate
    // ---------------------------------------------------------------------------------------------

    /**
     * Applies a batch of rights operations to a role (BM write transaction + filesystem export).
     * Supported ops: {@code set_right}, {@code set_config_right}, {@code set_flags}, {@code clear_object}.
     */
    public MutateResult mutateRoleRights(String projectName, String role, List<Map<String, Object>> operations) {
        IProject project = requireProject(projectName);
        String roleFqn = normalizeRoleFqn(role);
        if (operations == null || operations.isEmpty()) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "operations must not be empty", false); //$NON-NLS-1$
        }
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        if (configuration == null) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Configuration unavailable for project: " + projectName, false); //$NON-NLS-1$
        }
        Role roleObject = findRole(configuration, roleFqn);
        IRightInfosService rightInfos = metadataService.resolveRightInfosService();
        List<String> applied = new ArrayList<>();

        metadataService.mutateTopObjectAndExport(projectName, roleFqn, transaction -> {
            Role txRole = transaction.toTransactionObject(roleObject);
            RoleDescription roleDescription = getOrCreateRoleDescription(transaction, project, txRole);
            for (Map<String, Object> operation : operations) {
                applyOperation(transaction, projectName, roleFqn, txRole, roleDescription, configuration,
                        rightInfos, operation, applied);
            }
        });
        LOG.info("mutateRoleRights role=%s applied=%d", roleFqn, Integer.valueOf(applied.size())); //$NON-NLS-1$
        return new MutateResult(projectName, roleFqn, applied.size(), applied);
    }

    private void applyOperation(IBmPlatformTransaction transaction, String projectName, String roleFqn, Role txRole,
            RoleDescription roleDescription, Configuration configuration, IRightInfosService rightInfos,
            Map<String, Object> operation, List<String> applied) {
        String type = operation.get("op") == null //$NON-NLS-1$
                ? "" : String.valueOf(operation.get("op")).trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        switch (type) {
            case "set_right" -> { //$NON-NLS-1$
                MdObject canonical = resolveTopObjectByFqn(configuration, asString(operation.get("object_fqn"))); //$NON-NLS-1$
                EObject txObject = transaction.toTransactionObject(canonical);
                setRightOnObject(projectName, roleFqn, roleDescription, txRole, txObject, rightInfos,
                        asString(operation.get("right")), operation.get("value"), applied); //$NON-NLS-1$ //$NON-NLS-2$
            }
            case "set_config_right" -> { //$NON-NLS-1$
                Configuration txConfiguration = transaction.toTransactionObject(configuration);
                setRightOnObject(projectName, roleFqn, roleDescription, txRole, txConfiguration, rightInfos,
                        asString(operation.get("right")), operation.get("value"), applied); //$NON-NLS-1$ //$NON-NLS-2$
            }
            case "set_flags" -> applyFlags(roleDescription, operation, applied); //$NON-NLS-1$
            case "clear_object" -> { //$NON-NLS-1$
                String objectFqn = asString(operation.get("object_fqn")); //$NON-NLS-1$
                MdObject canonical = resolveTopObjectByFqn(configuration, objectFqn);
                EObject txObject = transaction.toTransactionObject(canonical);
                ObjectRights existing = RightsModelUtil.filterObjectRightsByEObjectFastly(txObject, roleDescription);
                if (existing != null) {
                    roleDescription.getRights().remove(existing);
                    applied.add("clear " + objectFqn); //$NON-NLS-1$
                }
            }
            default -> throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "Unknown op: '" + type //$NON-NLS-1$
                            + "' (use set_right, set_config_right, set_flags, clear_object)", false); //$NON-NLS-1$
        }
    }

    private void setRightOnObject(String projectName, String roleFqn, RoleDescription roleDescription, Role txRole, EObject txObject,
            IRightInfosService rightInfos, String rightName, Object valueRaw, List<String> applied) {
        if (rightName == null || rightName.isBlank()) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "right is required for set_right/set_config_right", false); //$NON-NLS-1$
        }
        RightValue value = parseRightValue(valueRaw);
        EClass eClass = txObject.eClass();
        Set<Right> available = rightInfos.getEClassRights(txObject, eClass);
        Right right = findRight(available, rightName.trim());
        if (right == null) {
            throw unsupportedRightException(projectName, roleFqn, txObject, rightName, available);
        }
        ObjectRights objectRights = RightsModelUtil.getOrCreateObjectRights(txObject, roleDescription);
        RightValue defaultValue = RightsModelUtil.getDefaultRightValue(txObject, txRole);
        RightsModelUtil.changeObjectRight(value, defaultValue, objectRights, right);
        applied.add("set " + right.getName() + "=" + value.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        Set<RightName> dependencies = value == RightValue.SET
                ? RightsModelUtil.getCheckDependeces(right)
                : RightsModelUtil.getUncheckDependeces(right);
        if (dependencies != null) {
            for (RightName dependencyName : dependencies) {
                Right dependency = findRight(available, dependencyName.getName());
                if (dependency != null) {
                    RightsModelUtil.changeObjectRight(value, defaultValue, objectRights, dependency);
                    applied.add("  +dep " + dependency.getName() + "=" + value.getName()); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    private void applyFlags(RoleDescription roleDescription, Map<String, Object> operation, List<String> applied) {
        if (operation.containsKey("set_for_new_objects")) { //$NON-NLS-1$
            roleDescription.setSetForNewObjects(asBool(operation.get("set_for_new_objects"))); //$NON-NLS-1$
            applied.add("set_for_new_objects=" + roleDescription.isSetForNewObjects()); //$NON-NLS-1$
        }
        if (operation.containsKey("set_for_attributes_by_default")) { //$NON-NLS-1$
            roleDescription.setSetForAttributesByDefault(asBool(operation.get("set_for_attributes_by_default"))); //$NON-NLS-1$
            applied.add("set_for_attributes_by_default=" + roleDescription.isSetForAttributesByDefault()); //$NON-NLS-1$
        }
        if (operation.containsKey("independent_rights_of_child_objects")) { //$NON-NLS-1$
            roleDescription.setIndependentRightsOfChildObjects(
                    asBool(operation.get("independent_rights_of_child_objects"))); //$NON-NLS-1$
            applied.add("independent_rights_of_child_objects=" //$NON-NLS-1$
                    + roleDescription.isIndependentRightsOfChildObjects());
        }
    }

    /**
     * Returns the role's {@link RoleDescription}, creating one when absent. {@code Role.rights} is a
     * non-containment reference to a separate top object (the {@code .rights} file), so a new
     * description must be attached as a BM top object (with a generated external-property FQN) before
     * the reference can be bound — the same pattern EDT uses for {@code BasicForm.form}. A bare
     * {@code setRights(factory.create())} fails at commit with "Failed to persist reference value".
     */
    private RoleDescription getOrCreateRoleDescription(IBmPlatformTransaction transaction, IProject project,
            Role txRole) {
        AbstractRoleDescription description = txRole.getRights();
        if (description instanceof RoleDescription roleDescription) {
            return roleDescription;
        }
        RoleDescription created = RightsFactory.eINSTANCE.createRoleDescription();
        if (!(created instanceof IBmObject createdBm)) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Created RoleDescription is not a BM object", false); //$NON-NLS-1$
        }
        String externalFqn = gateway.getTopObjectFqnGenerator()
                .generateExternalPropertyFqn(txRole, MdClassPackage.Literals.ROLE__RIGHTS);
        if (externalFqn == null || externalFqn.isBlank()) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Cannot generate external FQN for Role.rights", false); //$NON-NLS-1$
        }
        IBmNamespace namespace = gateway.getBmModelManager().getBmNamespace(project);
        if (namespace == null) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve BM namespace for project: " + project.getName(), false); //$NON-NLS-1$
        }
        transaction.attachTopObject(namespace, createdBm, externalFqn);
        Object attached = transaction.getTopObjectByFqn(namespace, externalFqn);
        if (!(attached instanceof RoleDescription roleDescription)) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Attached RoleDescription is not resolvable: " + externalFqn, false); //$NON-NLS-1$
        }
        txRole.setRights(roleDescription);
        return roleDescription;
    }

    private Role findRole(Configuration configuration, String roleFqn) {
        String name = roleFqn.substring(ROLE_PREFIX.length());
        for (MdObject object : MetadataConfigurationCollections.topLevelForKind(configuration, MetadataKind.ROLE)) {
            if (object instanceof Role role && name.equals(role.getName())) {
                return role;
            }
        }
        throw new MetadataOperationException(MetadataOperationCode.METADATA_NOT_FOUND,
                "Role not found: " + roleFqn, false); //$NON-NLS-1$
    }

    private MdObject resolveTopObjectByFqn(Configuration configuration, String fqn) {
        String[] parts = splitObjectFqn(fqn);
        String kindToken = parts[0];
        String name = parts[1];
        MetadataKind kind = null;
        for (MetadataKind candidate : MetadataKind.values()) {
            if (candidate.getFqnPrefix().equalsIgnoreCase(kindToken)) {
                kind = candidate;
                break;
            }
        }
        if (kind == null) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "Unknown metadata kind in FQN: " + kindToken, false); //$NON-NLS-1$
        }
        MdObject current = null;
        for (MdObject object : MetadataConfigurationCollections.topLevelForKind(configuration, kind)) {
            if (object != null && name.equals(object.getName())) {
                current = object;
                break;
            }
        }
        if (current == null) {
            throw new MetadataOperationException(MetadataOperationCode.METADATA_NOT_FOUND,
                    "Object not found: " + fqn, false); //$NON-NLS-1$
        }
        // Walk nested <Marker>.<Name> pairs, e.g. HTTPService.FF.URLTemplate.OrderGet.Method.GetOrder
        // or Document.Foo.Attribute.Bar - rights targets may be child metadata objects (issue #61).
        for (int i = 2; i + 1 < parts.length; i += 2) {
            MdObject child = findChildObject(current, parts[i], parts[i + 1]);
            if (child == null) {
                throw new MetadataOperationException(MetadataOperationCode.METADATA_NOT_FOUND,
                        "Child object not found: " + parts[i] + "." + parts[i + 1] + " in " + fqn, false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            current = child;
        }
        return current;
    }

    /**
     * Splits an {@code object_fqn} into segments and validates its shape: {@code Kind.Name}
     * optionally followed by {@code <Marker>.<Name>} pairs. Pure string logic (unit-tested).
     */
    static String[] splitObjectFqn(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "object_fqn is required", false); //$NON-NLS-1$
        }
        String[] parts = fqn.trim().split("\\."); //$NON-NLS-1$
        if (parts.length < 2) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "object_fqn must be in Kind.Name form: " + fqn, false); //$NON-NLS-1$
        }
        if (parts.length % 2 != 0) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "object_fqn child path must be <Marker>.<Name> pairs: " + fqn, false); //$NON-NLS-1$
        }
        for (String part : parts) {
            if (part.isBlank()) {
                throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                        "object_fqn contains an empty segment: " + fqn, false); //$NON-NLS-1$
            }
        }
        return parts;
    }

    private static final String[] CHILD_CLASS_SUFFIXES = {
            "Attribute", "TabularSection", "Command", "Form", "Template", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "Dimension", "Resource", "Requisite", "EnumValue", "URLTemplate", "Method", "Operation" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
    };

    /**
     * Resolves a nested child metadata object of the given parent by a {@code <Marker>.<Name>} pair
     * via a generic walk over containment references (same semantics as the metadata inspector), so
     * FQNs like {@code HTTPService.FF.URLTemplate.OrderGet.Method.GetOrder} resolve to the method.
     */
    private MdObject findChildObject(MdObject parent, String marker, String childName) {
        String normalizedMarker = normalizeToken(marker);
        for (EReference reference : parent.eClass().getEAllReferences()) {
            if (!reference.isContainment() || !reference.isMany()) {
                continue;
            }
            Object raw = parent.eGet(reference);
            if (!(raw instanceof Collection<?> collection)) {
                continue;
            }
            for (Object element : collection) {
                if (!(element instanceof MdObject child) || !childName.equalsIgnoreCase(child.getName())) {
                    continue;
                }
                if (markerMatchesChild(normalizedMarker, reference, child)) {
                    return child;
                }
            }
        }
        return null;
    }

    private boolean markerMatchesChild(String marker, EReference reference, MdObject child) {
        if (marker.isEmpty()) {
            return true;
        }
        String refName = normalizeToken(reference.getName());
        if (marker.equals(refName) || marker.equals(singularizeToken(refName))) {
            return true;
        }
        String className = child.eClass().getName();
        if (marker.equals(normalizeToken(className))) {
            return true;
        }
        for (String suffix : CHILD_CLASS_SUFFIXES) {
            if (className.endsWith(suffix) && marker.equals(normalizeToken(suffix))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeToken(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        String lowered = value.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder sb = new StringBuilder(lowered.length());
        for (int i = 0; i < lowered.length(); i++) {
            char ch = lowered.charAt(i);
            if (ch == '_' || ch == '-' || ch == '.' || Character.isWhitespace(ch)) {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String singularizeToken(String value) {
        if (value == null || value.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        if (value.endsWith("ies")) { //$NON-NLS-1$
            return value.substring(0, value.length() - 3) + "y"; //$NON-NLS-1$
        }
        if (value.endsWith("s") && !value.endsWith("ss")) { //$NON-NLS-1$ //$NON-NLS-2$
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String unsupportedRightMessage(String projectName, String roleFqn, EObject txObject, EClass eClass,
            String requestedRight, Set<Right> available) {
        List<String> availableRights = new ArrayList<>();
        if (available != null) {
            for (Right candidate : available) {
                if (candidate != null && candidate.getName() != null) {
                    availableRights.add(candidate.getName());
                }
            }
        }
        boolean configurationTarget = txObject instanceof Configuration;
        boolean extensionProject = isExtensionConfigurationTarget(txObject);
        if (availableRights.isEmpty() && configurationTarget && extensionProject) {
            availableRights.addAll(FALLBACK_EXTENSION_CONFIG_RIGHTS);
        }
        String diagnostic = configurationTarget && extensionProject
                ? MetadataOperationCode.UNSUPPORTED_IN_EXTENSION.name()
                : RIGHT_NOT_AVAILABLE;
        return "{\"error\":\"" + escapeJson(diagnostic) + "\",\"project\":\"" + escapeJson(projectName) //$NON-NLS-1$ //$NON-NLS-2$
                + "\",\"role\":\"" + escapeJson(roleFqn) //$NON-NLS-1$
                + "\",\"right\":\"" + escapeJson(requestedRight) //$NON-NLS-1$
                + "\",\"targetKind\":\"" + escapeJson(eClass.getName()) //$NON-NLS-1$
                + "\",\"isExtensionProject\":" + extensionProject //$NON-NLS-1$
                + ",\"availableRights\":\"" + escapeJson(String.join(",", availableRights)) //$NON-NLS-1$ //$NON-NLS-2$
                + "\",\"hint\":\"" + escapeJson(AVAILABLE_CONFIG_RIGHTS_DIAGNOSTIC) + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    MetadataOperationException unsupportedRightException(String projectName, String roleFqn, EObject txObject,
            String requestedRight, Set<Right> available) {
        EClass eClass = txObject == null ? null : txObject.eClass();
        String targetKind = eClass == null ? "" : eClass.getName(); //$NON-NLS-1$
        EClass effectiveClass = eClass;
        if (effectiveClass == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "{\"error\":\"" + RIGHT_NOT_AVAILABLE + "\",\"project\":\"" + escapeJson(projectName) //$NON-NLS-1$ //$NON-NLS-2$
                            + "\",\"role\":\"" + escapeJson(roleFqn) //$NON-NLS-1$
                            + "\",\"right\":\"" + escapeJson(requestedRight) //$NON-NLS-1$
                            + "\",\"targetKind\":\"" + escapeJson(targetKind) //$NON-NLS-1$
                            + "\",\"isExtensionProject\":false,\"availableRights\":\"\",\"hint\":\"" //$NON-NLS-1$
                            + escapeJson(AVAILABLE_CONFIG_RIGHTS_DIAGNOSTIC) + "\"}", //$NON-NLS-1$
                    false);
        }
        boolean unsupportedExtensionConfigRight = isExtensionConfigurationTarget(txObject);
        return new MetadataOperationException(unsupportedExtensionConfigRight
                ? MetadataOperationCode.UNSUPPORTED_IN_EXTENSION
                : MetadataOperationCode.INVALID_PROPERTY_VALUE,
                unsupportedRightMessage(projectName, roleFqn, txObject, effectiveClass, requestedRight, available),
                false);
    }

    private boolean isExtensionConfigurationTarget(EObject txObject) {
        return txObject instanceof Configuration configuration
                && configuration.getNamePrefix() != null
                && !configuration.getNamePrefix().isBlank();
    }

    static List<String> fallbackExtensionConfigRights() {
        return FALLBACK_EXTENSION_CONFIG_RIGHTS;
    }

    static String escapeJson(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value
                .replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\"", "\\\"") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\n", "\\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\r", "\\r"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Right findRight(Set<Right> available, String name) {
        if (available == null) {
            return null;
        }
        for (Right right : available) {
            if (right == null) {
                continue;
            }
            if (name.equalsIgnoreCase(right.getName()) || name.equalsIgnoreCase(safeNameRu(right))) {
                return right;
            }
        }
        return null;
    }

    private RightValue parseRightValue(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
        return switch (value) {
            case "set", "allow", "grant", "true", "yes", "1" -> RightValue.SET; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            case "unset", "deny", "revoke", "false", "no", "0" -> RightValue.UNSET; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            default -> throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "Unknown right value: '" + raw + "' (use set/allow or unset/deny)", false); //$NON-NLS-1$ //$NON-NLS-2$
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    private String resolveObjectFqn(EObject object) {
        if (object == null) {
            return "(unknown)"; //$NON-NLS-1$
        }
        if (object instanceof IBmObject bmObject) {
            String fqn = BmObjectHelper.safeTopFqn(bmObject);
            if (fqn != null && !fqn.isBlank()) {
                return fqn;
            }
        }
        return object.eClass() != null ? object.eClass().getName() : "(unknown)"; //$NON-NLS-1$
    }

    private String resolveObjectKind(EObject object) {
        return object != null && object.eClass() != null ? object.eClass().getName() : ""; //$NON-NLS-1$
    }

    private String safeNameRu(Right right) {
        try {
            String nameRu = right.getNameRu();
            return nameRu == null ? "" : nameRu; //$NON-NLS-1$
        } catch (Exception e) {
            return ""; //$NON-NLS-1$
        }
    }

    private String normalizeRoleFqn(String role) {
        if (role == null || role.isBlank()) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "Role name or FQN is required", false); //$NON-NLS-1$
        }
        String trimmed = role.trim();
        return trimmed.startsWith(ROLE_PREFIX) ? trimmed : ROLE_PREFIX + trimmed;
    }

    private IProject requireProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new MetadataOperationException(MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Project not found: " + projectName, false); //$NON-NLS-1$
        }
        return project;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static boolean asBool(Object value) {
        if (value instanceof Boolean bool) {
            return bool.booleanValue();
        }
        String s = value == null ? "" : String.valueOf(value).trim(); //$NON-NLS-1$
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @FunctionalInterface
    private interface ReadTransactionTask<T> {
        T execute(IBmTransaction transaction);
    }

    /** Deterministic snapshot of a role's rights model. */
    public record RoleRightsSnapshot(
            String project,
            String roleFqn,
            String roleName,
            boolean hasRightsModel,
            boolean setForNewObjects,
            boolean setForAttributesByDefault,
            boolean independentRightsOfChildObjects,
            List<String> templates,
            List<ObjectRightsView> objects) {
    }

    /** Rights set on one metadata object within a role. */
    public record ObjectRightsView(String objectFqn, String objectKind, List<RightView> rights) {
    }

    /** A single right entry: English/Russian name, value (SET/UNSET/PROVIDED), and RLS presence. */
    public record RightView(String name, String nameRu, String value, boolean hasRls) {
    }

    /** Result of a {@link #mutateRoleRights} batch. */
    public record MutateResult(String project, String roleFqn, int operationsApplied, List<String> details) {
    }
}
