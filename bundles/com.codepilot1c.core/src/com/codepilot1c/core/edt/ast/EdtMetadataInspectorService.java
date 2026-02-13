package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

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
                        .putProperty("message", "Object not found"); //$NON-NLS-1$ //$NON-NLS-2$
                nodes.add(missing);
                continue;
            }
            nodes.add(inspectEObject(obj, fqn, req.isFull(), 0));
        }

        return new MetadataDetailsResult(req.getProjectName(), "edt_emf", nodes); //$NON-NLS-1$
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
                node.putProperty(feature.getName(), "Collection(size=" + collection.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                node.putProperty(feature.getName(), String.valueOf(value));
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
        if (parts.length < 2) {
            return null;
        }
        String type = parts[0].toLowerCase();
        String name = parts[1];

        List<? extends MdObject> objects = switch (type) {
            case "catalog", "catalogs" -> config.getCatalogs(); //$NON-NLS-1$ //$NON-NLS-2$
            case "document", "documents" -> config.getDocuments(); //$NON-NLS-1$ //$NON-NLS-2$
            case "commonmodule", "commonmodules" -> config.getCommonModules(); //$NON-NLS-1$ //$NON-NLS-2$
            case "informationregister", "informationregisters" -> config.getInformationRegisters(); //$NON-NLS-1$ //$NON-NLS-2$
            case "accumulationregister", "accumulationregisters" -> config.getAccumulationRegisters(); //$NON-NLS-1$ //$NON-NLS-2$
            case "report", "reports" -> config.getReports(); //$NON-NLS-1$ //$NON-NLS-2$
            case "dataprocessor", "dataprocessors" -> config.getDataProcessors(); //$NON-NLS-1$ //$NON-NLS-2$
            case "enum", "enums" -> config.getEnums(); //$NON-NLS-1$ //$NON-NLS-2$
            case "constant", "constants" -> config.getConstants(); //$NON-NLS-1$ //$NON-NLS-2$
            default -> List.of();
        };

        for (MdObject obj : objects) {
            if (name.equalsIgnoreCase(obj.getName())) {
                return obj;
            }
        }
        return null;
    }
}
