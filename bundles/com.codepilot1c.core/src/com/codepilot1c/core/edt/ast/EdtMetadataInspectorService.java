package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.codepilot1c.core.edt.BmObjectHelper;
import com.codepilot1c.core.edt.metadata.MdObjectFqnResolver;
import com.codepilot1c.core.edt.metadata.MetadataConfigurationCollections;
import com.codepilot1c.core.edt.metadata.MetadataKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Metadata inspection service using EDT configuration model and EMF reflection.
 */
public class EdtMetadataInspectorService {

    private final EdtServiceGateway gateway;
    private final ProjectReadinessChecker readinessChecker;

    public EdtMetadataInspectorService(EdtServiceGateway gateway, ProjectReadinessChecker readinessChecker) {
        this.gateway = gateway;
        this.readinessChecker = readinessChecker;
    }

    public MetadataDetailsResult getMetadataDetails(MetadataDetailsRequest req) {
        req.validate();
        return executeRead(req.getProjectName(), () -> doGetMetadataDetails(req));
    }

    MetadataDetailsResult doGetMetadataDetails(MetadataDetailsRequest req) {
        IProject project = gateway.resolveProject(req.getProjectName());
        readinessChecker.ensureReady(project);

        IConfigurationProvider provider = gateway.getConfigurationProvider();
        Configuration config = provider.getConfiguration(project);
        if (config == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Configuration is unavailable for project", false); //$NON-NLS-1$
        }

        List<MetadataNode> nodes = new ArrayList<>();
        for (String fqn : req.getObjectFqns()) {
            MdObject obj = findMdObjectByFqn(config, fqn);
            if (obj == null) {
                MetadataNode missing = new MetadataNode()
                        .setType("MdObject") //$NON-NLS-1$
                        .setName(fqn)
                        .setPath(fqn)
                        .setFormatStyle(MetadataNode.FormatStyle.SIMPLE_VALUE)
                        .putProperty("exists", Boolean.FALSE) //$NON-NLS-1$
                        .putProperty("message", //$NON-NLS-1$
                                MdObjectFqnResolver.hasCompleteSegmentPairs(fqn.split("\\.")) //$NON-NLS-1$
                                        ? "Object not found" //$NON-NLS-1$
                                        : "Malformed FQN: segments after <Type>.<Name> must be marker/name pairs, " //$NON-NLS-1$
                                                + "e.g. Subsystem.Родитель.Subsystem.Вложенная"); //$NON-NLS-1$
                nodes.add(missing);
                continue;
            }
            nodes.add(inspectEObject(obj, fqn, req.isFull(), 0));
        }

        return new MetadataDetailsResult(req.getProjectName(), "edt_emf", nodes); //$NON-NLS-1$
    }

    private <T> T executeRead(String projectName, ReadOnlyTask<T> task) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null) {
            return task.execute();
        }
        try {
            return gateway.getBmModelManager().executeReadOnlyTask(project, tx -> task.execute());
        } catch (EdtAstException e) {
            if (e.getCode() == EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE) {
                return task.execute();
            }
            throw e;
        } catch (RuntimeException e) {
            throw new EdtAstException(
                    EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Failed to execute metadata inspection read transaction: " + e.getMessage(), //$NON-NLS-1$
                    true,
                    e);
        }
    }

    private MetadataNode inspectEObject(EObject object, String path, boolean full, int depth) {
        MetadataNode node = new MetadataNode()
                .setType(object.eClass().getName())
                .setName(getObjectName(object))
                .setPath(path);

        for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures()) {
            if (feature.isDerived() || feature.isTransient() || feature.isVolatile()) {
                continue;
            }
            Object value = object.eGet(feature);
            if (value == null) {
                continue;
            }

            if (feature instanceof EReference ref && ref.isContainment()) {
                if (isStringMapContainment(ref) && value instanceof Collection<?> collection) {
                    Map<String, String> localized = extractStringMapEntries(collection);
                    if (!localized.isEmpty()) {
                        node.putProperty(feature.getName(), localized);
                    }
                    continue;
                }
                if (!full || depth >= 2) {
                    continue;
                }
                if (ref.isMany() && value instanceof Collection<?> collection) {
                    for (Object item : collection) {
                        if (item instanceof EObject child) {
                            node.addChild(inspectEObject(child, path + "." + feature.getName(), full, depth + 1)); //$NON-NLS-1$
                        }
                    }
                } else if (value instanceof EObject child) {
                    node.addChild(inspectEObject(child, path + "." + feature.getName(), full, depth + 1)); //$NON-NLS-1$
                }
                continue;
            }

            if (value instanceof Collection<?> collection) {
                node.putProperty(feature.getName(), formatCollectionValue(collection));
            } else {
                node.putProperty(feature.getName(), formatScalarValue(value));
            }
        }

        node.setFormatStyle(EObjectInspector.chooseFormatStyle(node));
        return node;
    }

    private String getObjectName(EObject object) {
        try {
            EStructuralFeature nameFeature = object.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
            if (nameFeature != null) {
                Object value = object.eGet(nameFeature);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value);
                }
            }
        } catch (Exception e) {
            // Ignore and fallback to class name.
        }
        return object.eClass().getName();
    }


    private MdObject findMdObjectByFqn(Configuration config, String fqn) {
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        // A dangling odd segment means the FQN is malformed, not that the object is missing.
        // The previous loop condition (i + 1 < parts.length) walked complete pairs only and
        // returned whatever had been resolved so far, so Subsystem.Фулфилмент.Инвентаризация
        // silently answered with the PARENT subsystem and echoed the requested path back as if
        // it were correct — a wrong answer that looks exactly like a right one.
        if (!MdObjectFqnResolver.hasCompleteSegmentPairs(parts)) {
            return null;
        }
        MdObject current = findTopLevelObject(config, parts[0], parts[1]);
        if (current == null) {
            return null;
        }
        // Walk nested marker/name pairs, e.g. Document.Foo.Attribute.Bar or Catalog.X.Form.ListForm.
        for (int i = 2; i + 1 < parts.length; i += 2) {
            current = MdObjectFqnResolver.findNestedChild(current, parts[i], parts[i + 1]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * Resolves a top-level metadata object by its {@code <Type>.<Name>} prefix.
     *
     * <p>Uses the typed {@code Configuration} accessors via {@link MetadataConfigurationCollections}
     * rather than a reflective {@code eGet} walk: the top-level mdclass collections are derived
     * features, so reflection that skips derived references sees nothing for them (that regression
     * made Task/Document/... report {@code exists=false}). Covers every metadata kind, not just the
     * previous ~9.</p>
     */
    private MdObject findTopLevelObject(Configuration config, String type, String name) {
        MetadataKind kind;
        try {
            kind = MetadataKind.fromString(type);
        } catch (MetadataOperationException e) {
            return null;
        }
        for (MdObject candidate : MetadataConfigurationCollections.topLevelForKind(config, kind)) {
            if (candidate != null && name.equalsIgnoreCase(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }



    private String normalizeToken(String value) {
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


    private Object formatCollectionValue(Collection<?> collection) {
        if (collection == null || collection.isEmpty()) {
            return List.of();
        }
        List<Object> formatted = new ArrayList<>();
        for (Object entry : collection) {
            formatted.add(formatScalarValue(entry));
        }
        return formatted;
    }

    private Object formatScalarValue(Object value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        if (value instanceof EObject eObject) {
            return formatEObjectReference(eObject);
        }
        return String.valueOf(value);
    }

    private String formatEObjectReference(EObject object) {
        if (object == null) {
            return ""; //$NON-NLS-1$
        }
        String fqn = resolveFqn(object);
        if (fqn != null && !fqn.isBlank()) {
            return fqn;
        }
        EStructuralFeature nameFeature = object.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        if (nameFeature != null) {
            Object rawName = object.eGet(nameFeature);
            if (rawName != null) {
                String name = String.valueOf(rawName).trim();
                if (!name.isBlank()) {
                    return object.eClass().getName() + "." + name; //$NON-NLS-1$
                }
            }
        }
        return object.eClass().getName();
    }

    private String resolveFqn(EObject object) {
        if (!(object instanceof IBmObject bmObject)) {
            return null;
        }
        String fqn = BmObjectHelper.safeTopFqn(bmObject);
        return fqn.isBlank() ? null : fqn;
    }

    private boolean isStringMapContainment(EReference reference) {
        if (reference == null || !reference.isContainment() || !reference.isMany()) {
            return false;
        }
        var entryType = reference.getEReferenceType();
        if (entryType == null) {
            return false;
        }
        EStructuralFeature keyFeature = entryType.getEStructuralFeature("key"); //$NON-NLS-1$
        EStructuralFeature valueFeature = entryType.getEStructuralFeature("value"); //$NON-NLS-1$
        if (!(keyFeature instanceof EAttribute keyAttr) || !(valueFeature instanceof EAttribute valueAttr)) {
            return false;
        }
        return isStringDataType(keyAttr.getEAttributeType()) && isStringDataType(valueAttr.getEAttributeType());
    }

    private boolean isStringDataType(EDataType dataType) {
        if (dataType == null) {
            return false;
        }
        Class<?> instanceClass = dataType.getInstanceClass();
        if (instanceClass == String.class) {
            return true;
        }
        String className = dataType.getInstanceClassName();
        return "java.lang.String".equals(className); //$NON-NLS-1$
    }

    private Map<String, String> extractStringMapEntries(Collection<?> collection) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Object item : collection) {
            if (!(item instanceof EObject entry)) {
                continue;
            }
            EStructuralFeature keyFeature = entry.eClass().getEStructuralFeature("key"); //$NON-NLS-1$
            EStructuralFeature valueFeature = entry.eClass().getEStructuralFeature("value"); //$NON-NLS-1$
            if (keyFeature == null || valueFeature == null) {
                continue;
            }
            Object rawKey = entry.eGet(keyFeature);
            Object rawValue = entry.eGet(valueFeature);
            if (rawKey == null || rawValue == null) {
                continue;
            }
            String key = String.valueOf(rawKey).trim();
            String value = String.valueOf(rawValue);
            if (!key.isBlank() && !value.isBlank()) {
                map.put(key, value);
            }
        }
        return map;
    }

    @FunctionalInterface
    private interface ReadOnlyTask<T> {
        T execute();
    }
}
