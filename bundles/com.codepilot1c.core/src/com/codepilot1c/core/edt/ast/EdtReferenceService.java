package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.lang.reflect.Method;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Reference search service using EDT BM and Xtext reference finder.
 */
@SuppressWarnings("restriction")
public class EdtReferenceService {

    private static final URI BSL_URI = URI.createURI("/nopr/module.bsl"); //$NON-NLS-1$

    private final EdtServiceGateway gateway;
    private final ProjectReadinessChecker readinessChecker;

    public EdtReferenceService(EdtServiceGateway gateway, ProjectReadinessChecker readinessChecker) {
        this.gateway = gateway;
        this.readinessChecker = readinessChecker;
    }

    public ReferenceSearchResult findReferences(FindReferencesRequest req) {
        req.validate();

        IProject project = gateway.resolveProject(req.getProjectName());
        readinessChecker.ensureReady(project);

        IConfigurationProvider configProvider = gateway.getConfigurationProvider();
        Configuration configuration = configProvider.getConfiguration(project);
        if (configuration == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Configuration provider returned null configuration", false); //$NON-NLS-1$
        }

        MdObject target = findMdObjectByFqn(configuration, req.getObjectFqn());
        if (target == null) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "Metadata object not found: " + req.getObjectFqn(), false); //$NON-NLS-1$
        }

        IBmModelManager modelManager = gateway.getBmModelManager();
        IBmModel bmModel = modelManager.getModel(project);
        if (bmModel == null) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "BM model is unavailable", false); //$NON-NLS-1$
        }

        CollectorTask task = new CollectorTask(project, target, req.getLimit());
        bmModel.executeReadonlyTask(task, true);
        return new ReferenceSearchResult(req.getObjectFqn(), "edt_bm", task.references); //$NON-NLS-1$
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

    private class CollectorTask extends AbstractBmTask<Void> {

        private final MdObject target;
        private final IProject project;
        private final int limit;
        private final List<ReferenceSearchResult.ReferenceItem> references = new ArrayList<>();
        private final Set<String> dedup = new HashSet<>();

        CollectorTask(IProject project, MdObject target, int limit) {
            super("Find references: " + target.getName()); //$NON-NLS-1$
            this.project = project;
            this.target = target;
            this.limit = limit;
        }

        @Override
        public Void execute(com._1c.g5.v8.bm.core.IBmTransaction transaction,
                org.eclipse.core.runtime.IProgressMonitor progressMonitor) {
            collectBackReferences(transaction);
            collectBslReferences();
            return null;
        }

        @SuppressWarnings("unchecked")
        private void collectBackReferences(com._1c.g5.v8.bm.core.IBmTransaction transaction) {
            Collection<IBmCrossReference> refs = null;
            // Preferred path from BM docs: transaction.getReferences(IBmObject, IBmNamespace).
            try {
                Method getNamespace = transaction.getClass().getMethod("getNamespace"); //$NON-NLS-1$
                Object namespace = getNamespace.invoke(transaction);
                Method getReferences = transaction.getClass().getMethod("getReferences", //$NON-NLS-1$
                        IBmObject.class, namespace.getClass());
                Object result = getReferences.invoke(transaction, (IBmObject) target, namespace);
                if (result instanceof Collection<?>) {
                    refs = (Collection<IBmCrossReference>) result;
                }
            } catch (Exception e) {
                // Fallback for EDT runtimes that expose only engine-level back references.
                IBmEngine engine = ((IBmObject) target).bmGetEngine();
                if (engine != null) {
                    refs = engine.getBackReferences((IBmObject) target);
                }
            }
            if (refs == null) {
                return;
            }
            for (IBmCrossReference ref : refs) {
                if (references.size() >= limit) {
                    break;
                }
                IBmObject source = ref.getObject();
                if (source == null) {
                    continue;
                }
                EStructuralFeature feature = ref.getFeature();
                String featureName = feature != null ? feature.getName() : "reference"; //$NON-NLS-1$
                String path = source.bmGetFqn();
                if (path == null || path.isBlank()) {
                    path = source.eClass().getName();
                }
                add("Metadata", path, 0, featureName); //$NON-NLS-1$
            }
        }

        private void collectBslReferences() {
            try {
                IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BSL_URI);
                if (rsp == null) {
                    return;
                }
                IReferenceFinder finder = rsp.get(IReferenceFinder.class);
                if (finder == null) {
                    return;
                }

                List<URI> uris = List.of(EcoreUtil.getURI(target));
                finder.findAllReferences(uris, null, this::collectBslReference, new NullProgressMonitor());
            } catch (Exception e) {
                throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                        "Failed to collect BSL references: " + e.getMessage(), false, e); //$NON-NLS-1$
            }
        }

        private void collectBslReference(IReferenceDescription refDesc) {
            if (references.size() >= limit) {
                return;
            }
            URI sourceUri = refDesc.getSourceEObjectUri();
            if (sourceUri == null) {
                return;
            }
            String path = sourceUri.path() != null ? sourceUri.path() : sourceUri.toString();
            int line = extractLineNumberFromSourceUri(sourceUri);
            add("BSL", normalizePath(path), line, "ast-reference"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private int extractLineNumberFromSourceUri(URI sourceUri) {
            try {
                org.eclipse.emf.ecore.resource.ResourceSet set =
                        gateway.getResourceSetProvider().get(projectForUri(sourceUri));
                org.eclipse.emf.ecore.resource.Resource resource = set.getResource(sourceUri.trimFragment(), true);
                if (resource == null) {
                    return 0;
                }
                EObject object = resource.getEObject(sourceUri.fragment());
                if (object == null) {
                    return 0;
                }
                org.eclipse.xtext.nodemodel.INode node =
                        org.eclipse.xtext.nodemodel.util.NodeModelUtils.findActualNodeFor(object);
                return node != null ? node.getStartLine() : 0;
            } catch (Exception e) {
                return 0;
            }
        }

        private IProject projectForUri(URI sourceUri) {
            String path = sourceUri.path();
            if (path == null) {
                return project;
            }
            String[] segments = path.split("/"); //$NON-NLS-1$
            for (String segment : segments) {
                IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(segment);
                if (p != null && p.exists()) {
                    return p;
                }
            }
            return project;
        }

        private String normalizePath(String path) {
            int src = path.indexOf("/src/"); //$NON-NLS-1$
            if (src >= 0) {
                return path.substring(src + 5);
            }
            return path;
        }

        private void add(String category, String path, int line, String snippet) {
            String key = category + '|' + path + '|' + line + '|' + snippet.hashCode();
            if (dedup.add(key)) {
                references.add(new ReferenceSearchResult.ReferenceItem(category, path, line, snippet));
            }
        }
    }
}
