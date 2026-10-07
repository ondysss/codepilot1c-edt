package com.codepilot1c.core.edt.metadata;

import java.util.Collection;
import java.util.Locale;

import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Shared resolution of nested metadata FQN segments, e.g. the {@code Subsystem.Инвентаризация}
 * tail of {@code Subsystem.Фулфилмент.Subsystem.Инвентаризация}.
 *
 * <p>This logic used to live twice: once in the mutation path ({@code EdtMetadataService}) and once
 * in the inspection path ({@code EdtMetadataInspectorService}). Only the mutation copy learned that
 * nested subsystems are not plain EMF containment, so {@code update_metadata} could target a nested
 * subsystem while {@code edt_metadata_details} answered {@code Object not found} for the very same
 * FQN. Two copies of one rule diverge; one copy cannot.</p>
 */
public final class MdObjectFqnResolver {

    private static final String[] CHILD_CLASS_TAILS = {
            // Union of both former copies. The inspection copy carried three tails the mutation
            // copy lacked (URLTemplate, Method, Operation); dropping them while merging would have
            // quietly narrowed FQN resolution for HTTP and web services.
            "Attribute", "TabularSection", "Command", "Form", "Template", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "Dimension", "Resource", "Requisite", "EnumValue", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "URLTemplate", "Method", "Operation" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    };

    private MdObjectFqnResolver() {
        // utility
    }

    /**
     * Finds a nested child of {@code parent} by a {@code <Marker>.<Name>} FQN pair.
     *
     * <p>Nested metadata hierarchy is not always plain EMF containment. In the EDT metadata model,
     * nested subsystems ({@code Subsystem.subsystems}) are a NON-containment, resolve-proxies
     * EReference (verified via bytecode: {@code initEReference isContainment=false,
     * isResolveProxies=true}) — physically the subsystems are contained by {@code Configuration},
     * and the tree is expressed through cross-references. A containment-only walker drops that
     * feature and never finds nested children. A non-containment reference is followed only when
     * the FQN marker explicitly matches the feature or its type, so arbitrary back-references
     * (e.g. {@code parentSubsystem}) never produce false matches.</p>
     *
     * @return the resolved child, or {@code null} when nothing matches.
     */
    public static MdObject findNestedChild(MdObject parent, String marker, String childName) {
        if (parent == null || childName == null) {
            return null;
        }
        String normalizedMarker = normalizeToken(marker);
        for (EStructuralFeature feature : parent.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference reference) || !reference.isMany()) {
                continue;
            }
            boolean markerMatchesFeature = matchesMarker(
                    normalizedMarker, feature.getName(), reference.getEReferenceType().getName());
            if (!reference.isContainment() && !markerMatchesFeature) {
                continue;
            }
            Object raw = parent.eGet(feature);
            if (!(raw instanceof Collection<?> values)) {
                continue;
            }
            for (Object value : values) {
                if (!(value instanceof MdObject child)) {
                    continue;
                }
                // Non-containment references return proxies outside a BM transaction — resolve
                // them before reading the name (resolveProxies=true for these references).
                if (child.eIsProxy() || child.getName() == null) {
                    Object resolved = EcoreUtil.resolve(child, parent);
                    if (resolved instanceof MdObject resolvedChild) {
                        child = resolvedChild;
                    }
                }
                if (!childName.equalsIgnoreCase(child.getName())) {
                    continue;
                }
                if (markerMatchesFeature
                        || matchesMarker(normalizedMarker, feature.getName(), child.eClass().getName())) {
                    return child;
                }
            }
        }
        return null;
    }

    /**
     * Tells whether FQN segments after the leading {@code <Type>.<Name>} form complete
     * marker/name pairs.
     *
     * <p>A dangling odd segment means the FQN is malformed, not that the object is missing:
     * {@code Subsystem.Фулфилмент.Инвентаризация} is not a valid EDT FQN. Callers must not silently
     * fall back to the last resolved ancestor — that answers a question nobody asked and looks
     * exactly like a correct answer.</p>
     */
    public static boolean hasCompleteSegmentPairs(String[] parts) {
        return parts != null && parts.length >= 2 && (parts.length - 2) % 2 == 0;
    }

    public static boolean matchesMarker(String marker, String featureName, String className) {
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

    public static String normalizeToken(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                .toLowerCase(Locale.ROOT);
    }

    public static String singularize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
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

    private static String extractShortClassMarker(String className) {
        String normalized = className != null ? className : ""; //$NON-NLS-1$
        for (String tail : CHILD_CLASS_TAILS) {
            if (normalized.endsWith(tail)) {
                return tail;
            }
        }
        return normalized;
    }
}
