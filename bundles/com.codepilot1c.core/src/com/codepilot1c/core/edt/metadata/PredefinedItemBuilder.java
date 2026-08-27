package com.codepilot1c.core.edt.metadata;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Creates predefined items of a catalog-like metadata object (catalog, chart of characteristic
 * types, chart of accounts, chart of calculation types).
 *
 * <p>Predefined items are the one child kind of a catalog that {@code add_metadata_child} could not
 * produce: {@code MdClassFactory} names them {@code create<Owner>PredefinedItem}, but they are NOT
 * {@link MdObject}s — {@code PredefinedItem} extends plain {@code EObject}, and they hang off a
 * {@code predefined} container rather than off a containment list of children. Every generic path
 * in {@code EdtMetadataService} (factory lookup, {@code addChildToParent}, FQN post-verify) is
 * typed on {@code MdObject} and silently has no route for them. Hence a dedicated builder.</p>
 *
 * <p>The builder works through EMF structural features rather than the generated Java interfaces on
 * purpose: the four owners disagree on the shape of the very same logical property. A catalog's
 * {@code code} is an EReference to {@code mcore.Value}; a chart of characteristic types stores the
 * same code as a plain {@code String} EAttribute. Coding against features keeps one implementation
 * instead of a switch that will fall behind the model.</p>
 *
 * <p><b>Scope note.</b> Attribute values (e.g. a {@code ПубликоватьВЛК} flag) are deliberately NOT
 * settable here: the 1C metamodel gives a predefined item only name, description, code and the
 * folder flag. Attribute values of a predefined element live in the infobase data, not in the
 * configuration, so there is nothing in the model to write them into.</p>
 */
public final class PredefinedItemBuilder {

    private static final String FEATURE_PREDEFINED = "predefined"; //$NON-NLS-1$
    private static final String FEATURE_ITEMS = "items"; //$NON-NLS-1$
    private static final String FEATURE_CONTENT = "content"; //$NON-NLS-1$
    private static final String FEATURE_NAME = "name"; //$NON-NLS-1$
    private static final String FEATURE_DESCRIPTION = "description"; //$NON-NLS-1$
    private static final String FEATURE_CODE = "code"; //$NON-NLS-1$
    private static final String FEATURE_IS_FOLDER = "isFolder"; //$NON-NLS-1$
    private static final String FEATURE_ID = "id"; //$NON-NLS-1$
    private static final String FEATURE_CODE_LENGTH = "codeLength"; //$NON-NLS-1$
    private static final String FEATURE_CODE_TYPE = "codeType"; //$NON-NLS-1$

    private static final String MCORE_STRING_VALUE = "StringValue"; //$NON-NLS-1$
    private static final String MCORE_NUMBER_VALUE = "NumberValue"; //$NON-NLS-1$

    /** Fallback width when the owner declares no code length but siblings are zero-padded. */
    private static final int DEFAULT_CODE_WIDTH = 9;

    private PredefinedItemBuilder() {
        // utility
    }

    /**
     * Descriptor of one requested predefined item.
     *
     * @param name           metadata name of the item, mandatory.
     * @param description    "Наименование" of the item; {@code null} keeps the model default.
     * @param code           explicit code; {@code null} means "derive the next one".
     * @param codeExplicit   {@code true} when the caller passed {@code code} (including an empty
     *                       string, which means "leave the code empty").
     * @param isFolder       folder flag; {@code null} keeps the model default ({@code false}).
     * @param parentItemName name of an existing predefined FOLDER to nest the new item under;
     *                       {@code null} puts the item at the root of the predefined tree.
     */
    public record Descriptor(
            String name,
            String description,
            String code,
            boolean codeExplicit,
            Boolean isFolder,
            String parentItemName
    ) {
        public Descriptor(String name, String description) {
            this(name, description, null, false, null, null);
        }
    }

    /** Tells whether the owner has a {@code predefined} container at all. */
    public static boolean supports(EObject owner) {
        return owner != null && findFeature(owner.eClass(), FEATURE_PREDEFINED) instanceof EReference;
    }

    /**
     * Creates one predefined item under {@code owner} and returns it.
     *
     * @throws MetadataOperationException when the owner has no predefined container, the name is
     *         invalid, an item with that name already exists, or the requested nesting parent is
     *         missing / is not a folder.
     */
    public static EObject create(MdObject owner, Descriptor descriptor) {
        if (owner == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_PARENT_NOT_FOUND,
                    "Owner of a predefined item is required", false); //$NON-NLS-1$
        }
        if (descriptor == null || !MetadataNameValidator.isValidName(descriptor.name())) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Invalid predefined item name: " //$NON-NLS-1$
                            + (descriptor == null ? null : descriptor.name()), false);
        }
        EObject container = resolveOrCreateContainer(owner);
        List<EObject> rootItems = itemsOf(container);

        EObject duplicate = findByName(owner, descriptor.name());
        if (duplicate != null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_ALREADY_EXISTS,
                    "Predefined item already exists: " + descriptor.name() //$NON-NLS-1$
                            + " in " + owner.getName(), false); //$NON-NLS-1$
        }

        List<EObject> target = rootItems;
        if (descriptor.parentItemName() != null && !descriptor.parentItemName().isBlank()) {
            EObject parentItem = findByName(owner, descriptor.parentItemName());
            if (parentItem == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.METADATA_NOT_FOUND,
                        "Parent predefined item not found: " + descriptor.parentItemName(), false); //$NON-NLS-1$
            }
            if (!isFolder(parentItem)) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Parent predefined item is not a folder: " + descriptor.parentItemName(), false); //$NON-NLS-1$
            }
            target = contentOf(parentItem);
            if (target == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Predefined items of " + owner.eClass().getName() //$NON-NLS-1$
                                + " cannot be nested", false); //$NON-NLS-1$
            }
        }

        EObject item = instantiateItem(container);
        setIfPresent(item, FEATURE_NAME, descriptor.name());
        if (descriptor.description() != null) {
            setIfPresent(item, FEATURE_DESCRIPTION, descriptor.description());
        }
        applyIsFolder(item, descriptor, owner);
        assignId(item);
        applyCode(owner, item, descriptor, allItems(owner));

        target.add(item);
        return item;
    }

    /** Finds a predefined item by name anywhere in the predefined tree of {@code owner}. */
    public static EObject findByName(EObject owner, String name) {
        if (owner == null || name == null || name.isBlank()) {
            return null;
        }
        for (EObject item : allItems(owner)) {
            if (name.equalsIgnoreCase(nameOf(item))) {
                return item;
            }
        }
        return null;
    }

    /** Flattens the predefined tree of {@code owner}; empty when there is no predefined container. */
    public static List<EObject> allItems(EObject owner) {
        List<EObject> flat = new ArrayList<>();
        if (owner == null) {
            return flat;
        }
        EStructuralFeature feature = findFeature(owner.eClass(), FEATURE_PREDEFINED);
        if (feature == null || !(owner.eGet(feature) instanceof EObject container)) {
            return flat;
        }
        collect(itemsOf(container), flat);
        return flat;
    }

    public static String nameOf(EObject item) {
        EStructuralFeature feature = item == null ? null : findFeature(item.eClass(), FEATURE_NAME);
        return feature == null ? null : asString(item.eGet(feature));
    }

    /** Renders the code of an item as text, whatever shape the model stores it in. */
    public static String codeOf(EObject item) {
        EStructuralFeature feature = item == null ? null : findFeature(item.eClass(), FEATURE_CODE);
        if (feature == null) {
            return null;
        }
        Object raw = item.eGet(feature);
        if (raw == null) {
            return null;
        }
        if (raw instanceof EObject valueObject) {
            EStructuralFeature valueFeature = findFeature(valueObject.eClass(), "value"); //$NON-NLS-1$
            return valueFeature == null ? null : asString(valueObject.eGet(valueFeature));
        }
        return asString(raw);
    }

    public static boolean isFolder(EObject item) {
        EStructuralFeature feature = item == null ? null : findFeature(item.eClass(), FEATURE_IS_FOLDER);
        return feature != null && Boolean.TRUE.equals(item.eGet(feature));
    }

    // ---------------------------------------------------------------- internals

    private static EObject resolveOrCreateContainer(MdObject owner) {
        EStructuralFeature feature = findFeature(owner.eClass(), FEATURE_PREDEFINED);
        if (!(feature instanceof EReference reference)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_KIND,
                    owner.eClass().getName() + " has no predefined items, so child_kind=PredefinedItem " //$NON-NLS-1$
                            + "cannot be created on it. Predefined items exist for catalogs, charts of " //$NON-NLS-1$
                            + "characteristic types, charts of accounts and charts of calculation types.", //$NON-NLS-1$
                    false);
        }
        Object existing = owner.eGet(reference);
        if (existing instanceof EObject container) {
            return container;
        }
        EClass containerClass = reference.getEReferenceType();
        if (containerClass == null || containerClass.isAbstract()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Cannot instantiate predefined container for " + owner.eClass().getName(), false); //$NON-NLS-1$
        }
        EObject container = EcoreUtil.create(containerClass);
        owner.eSet(reference, container);
        return container;
    }

    private static EObject instantiateItem(EObject container) {
        EStructuralFeature feature = findFeature(container.eClass(), FEATURE_ITEMS);
        if (!(feature instanceof EReference reference) || reference.getEReferenceType() == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Predefined container " + container.eClass().getName() + " has no items list", false); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return EcoreUtil.create(reference.getEReferenceType());
    }

    @SuppressWarnings("unchecked")
    private static List<EObject> itemsOf(EObject container) {
        EStructuralFeature feature = findFeature(container.eClass(), FEATURE_ITEMS);
        if (feature == null || !(container.eGet(feature) instanceof List<?> list)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Predefined container " + container.eClass().getName() + " has no items list", false); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return (List<EObject>) list;
    }

    @SuppressWarnings("unchecked")
    private static List<EObject> contentOf(EObject item) {
        EStructuralFeature feature = findFeature(item.eClass(), FEATURE_CONTENT);
        if (feature == null || !(item.eGet(feature) instanceof List<?> list)) {
            return null;
        }
        return (List<EObject>) list;
    }

    private static void collect(Collection<EObject> items, List<EObject> sink) {
        if (items == null) {
            return;
        }
        for (EObject item : items) {
            sink.add(item);
            collect(contentOf(item), sink);
        }
    }

    private static void applyIsFolder(EObject item, Descriptor descriptor, MdObject owner) {
        if (descriptor.isFolder() == null) {
            return;
        }
        EStructuralFeature feature = findFeature(item.eClass(), FEATURE_IS_FOLDER);
        if (feature == null) {
            if (Boolean.TRUE.equals(descriptor.isFolder())) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_PROPERTY_VALUE,
                        "Predefined items of " + owner.eClass().getName() //$NON-NLS-1$
                                + " have no is_folder flag", false); //$NON-NLS-1$
            }
            return;
        }
        item.eSet(feature, descriptor.isFolder());
    }

    /**
     * Gives the item its own identity. The {@code id} attribute is REQUIRED by the metamodel
     * (verified: {@code lowerBound=1}, {@code isRequired=true}, default {@code null}, and a freshly
     * created item has it unset), and EDT writes it for every predefined item it produces itself.
     *
     * <p>Skipping it would be a SILENT defect: the {@code uuid_check} gate does not cover it. That
     * gate flags empty values by matching {@code uuid=""} literally, and this attribute is named
     * {@code id} — only DUPLICATE id values would be caught, never a missing one. Hence the unit
     * test rather than reliance on the gate.</p>
     */
    private static void assignId(EObject item) {
        EStructuralFeature feature = findFeature(item.eClass(), FEATURE_ID);
        if (feature == null) {
            return;
        }
        UUID id = UUID.randomUUID();
        if (feature instanceof EAttribute attribute
                && attribute.getEAttributeType() != null
                && String.class.getName().equals(attribute.getEAttributeType().getInstanceClassName())) {
            item.eSet(feature, id.toString());
            return;
        }
        item.eSet(feature, id);
    }

    private static void applyCode(MdObject owner, EObject item, Descriptor descriptor, List<EObject> siblings) {
        EStructuralFeature feature = findFeature(item.eClass(), FEATURE_CODE);
        if (feature == null) {
            return;
        }
        String code = descriptor.codeExplicit() ? descriptor.code() : nextCode(owner, siblings);
        if (code == null || code.isBlank()) {
            return;
        }
        if (feature instanceof EReference reference) {
            EObject value = createCodeValue(owner, reference, code);
            if (value != null) {
                item.eSet(feature, value);
            }
            return;
        }
        item.eSet(feature, code);
    }

    /**
     * Picks the code value subtype for the owner's {@code codeType}. Catalogs with a numeric code
     * need {@code NumberValue}; string codes (the default) need {@code StringValue}.
     */
    private static EObject createCodeValue(MdObject owner, EReference reference, String code) {
        EClass abstractValue = reference.getEReferenceType();
        if (abstractValue == null) {
            return null;
        }
        boolean numeric = "number".equals(codeTypeToken(owner)); //$NON-NLS-1$
        EClass valueClass = classifier(abstractValue, numeric ? MCORE_NUMBER_VALUE : MCORE_STRING_VALUE);
        if (valueClass == null) {
            return null;
        }
        EObject value = EcoreUtil.create(valueClass);
        EStructuralFeature valueFeature = findFeature(valueClass, "value"); //$NON-NLS-1$
        if (valueFeature == null) {
            return value;
        }
        if (numeric) {
            value.eSet(valueFeature, new BigDecimal(code.trim()));
        } else {
            value.eSet(valueFeature, code);
        }
        return value;
    }

    private static EClass classifier(EClass sibling, String name) {
        if (sibling.getEPackage() == null) {
            return null;
        }
        return sibling.getEPackage().getEClassifier(name) instanceof EClass found && !found.isAbstract()
                ? found
                : null;
    }

    private static String codeTypeToken(MdObject owner) {
        EStructuralFeature feature = findFeature(owner.eClass(), FEATURE_CODE_TYPE);
        Object raw = feature == null ? null : owner.eGet(feature);
        return raw == null ? "" : raw.toString().toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    /**
     * Continues the sibling numbering: the platform assigns predefined codes by hand in Designer,
     * so a new item created by an agent has to keep the same zero-padded sequence the object
     * already uses. Returns {@code null} when the siblings carry no parseable numeric codes — a
     * guess would be worse than an empty code the caller can fill in.
     */
    static String nextCode(MdObject owner, List<EObject> siblings) {
        long max = 0;
        int width = 0;
        boolean seen = false;
        for (EObject sibling : siblings) {
            String code = codeOf(sibling);
            if (code == null || !isNumericLiteral(code)) {
                continue;
            }
            String trimmed = code.trim();
            seen = true;
            width = Math.max(width, trimmed.length());
            max = Math.max(max, Long.parseLong(trimmed));
        }
        if (!seen) {
            return null;
        }
        int declared = codeLength(owner);
        int target = declared > 0 ? declared : (width > 0 ? width : DEFAULT_CODE_WIDTH);
        String next = Long.toString(max + 1);
        if ("number".equals(codeTypeToken(owner))) { //$NON-NLS-1$
            return next;
        }
        if (next.length() >= target) {
            return next;
        }
        return "0".repeat(target - next.length()) + next; //$NON-NLS-1$
    }

    private static int codeLength(MdObject owner) {
        EStructuralFeature feature = findFeature(owner.eClass(), FEATURE_CODE_LENGTH);
        Object raw = feature == null ? null : owner.eGet(feature);
        return raw instanceof Number number ? number.intValue() : 0;
    }

    private static boolean isNumericLiteral(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 18) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void setIfPresent(EObject object, String featureName, Object value) {
        EStructuralFeature feature = findFeature(object.eClass(), featureName);
        if (feature != null) {
            object.eSet(feature, value);
        }
    }

    private static EStructuralFeature findFeature(EClass eClass, String name) {
        if (eClass == null) {
            return null;
        }
        for (EStructuralFeature feature : eClass.getEAllStructuralFeatures()) {
            if (feature.getName().equalsIgnoreCase(name)) {
                return feature;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
