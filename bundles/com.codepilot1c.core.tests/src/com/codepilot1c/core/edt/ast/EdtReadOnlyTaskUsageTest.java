package com.codepilot1c.core.edt.ast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;

public class EdtReadOnlyTaskUsageTest {

    @Test
    public void metadataDetailsRunsInsideBmReadOnlyTask() {
        AtomicInteger readCalls = new AtomicInteger();
        IProject project = project("DemoConfiguration"); //$NON-NLS-1$
        MetadataDetailsResult expected = new MetadataDetailsResult(
                "DemoConfiguration", "edt_emf", List.of(new MetadataNode().setName("Items"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        TestMetadataInspectorService service = new TestMetadataInspectorService(
                new TestGateway(project, readCalls),
                new ProjectReadinessChecker(new TestGateway(project, readCalls)),
                expected);

        MetadataDetailsResult actual = service.getMetadataDetails(
                new MetadataDetailsRequest("DemoConfiguration", List.of("Catalog.Items"), false, "ru")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(1, readCalls.get());
        assertSame(expected, actual);
    }

    @Test
    public void contentAssistRunsInsideBmReadOnlyTask() {
        AtomicInteger readCalls = new AtomicInteger();
        IProject project = project("DemoConfiguration"); //$NON-NLS-1$
        ContentAssistResult expected = new ContentAssistResult(
                "edt_xtext", 1, false, List.of(new ContentAssistResult.Item("Провести", "proposal", null))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        TestContentAssistService service = new TestContentAssistService(
                new TestGateway(project, readCalls),
                new ProjectReadinessChecker(new TestGateway(project, readCalls)),
                expected);

        ContentAssistResult actual = service.getContentAssist(
                new ContentAssistRequest("DemoConfiguration", "CommonModules/Orders/Module.bsl", 1, 1, 20, 0, null, false)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, readCalls.get());
        assertSame(expected, actual);
    }

    private static IProject project(String name) {
        return (IProject) Proxy.newProxyInstance(
                EdtReadOnlyTaskUsageTest.class.getClassLoader(),
                new Class<?>[] {IProject.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) { //$NON-NLS-1$
                        return name;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return Boolean.FALSE;
                    }
                    if (method.getReturnType() == int.class) {
                        return Integer.valueOf(0);
                    }
                    return null;
                });
    }

    private static IBmModelManager bmModelManager(AtomicInteger readCalls) {
        return (IBmModelManager) Proxy.newProxyInstance(
                EdtReadOnlyTaskUsageTest.class.getClassLoader(),
                new Class<?>[] {IBmModelManager.class},
                (proxy, method, args) -> {
                    if ("executeReadOnlyTask".equals(method.getName()) && args != null && args.length == 2
                            && args[1] instanceof IBmSingleNamespaceTask<?> task) {
                        readCalls.incrementAndGet();
                        return task.execute(null);
                    }
                    if (method.getReturnType() == boolean.class) {
                        return Boolean.FALSE;
                    }
                    if (method.getReturnType() == int.class) {
                        return Integer.valueOf(0);
                    }
                    return null;
                });
    }

    private static final class TestGateway extends EdtServiceGateway {
        private final IProject project;
        private final IBmModelManager modelManager;

        TestGateway(IProject project, AtomicInteger readCalls) {
            this.project = project;
            this.modelManager = bmModelManager(readCalls);
        }

        @Override
        public IProject resolveProject(String projectName) {
            return project;
        }

        @Override
        public IBmModelManager getBmModelManager() {
            return modelManager;
        }
    }

    private static final class TestMetadataInspectorService extends EdtMetadataInspectorService {
        private final MetadataDetailsResult expected;

        TestMetadataInspectorService(EdtServiceGateway gateway, ProjectReadinessChecker readinessChecker,
                MetadataDetailsResult expected) {
            super(gateway, readinessChecker);
            this.expected = expected;
        }

        @Override
        MetadataDetailsResult doGetMetadataDetails(MetadataDetailsRequest req) {
            return expected;
        }
    }

    private static final class TestContentAssistService extends EdtContentAssistService {
        private final ContentAssistResult expected;

        TestContentAssistService(EdtServiceGateway gateway, ProjectReadinessChecker readinessChecker,
                ContentAssistResult expected) {
            super(gateway, readinessChecker);
            this.expected = expected;
        }

        @Override
        ContentAssistResult doGetContentAssist(ContentAssistRequest req) {
            return expected;
        }
    }
}
