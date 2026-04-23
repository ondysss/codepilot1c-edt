package com.codepilot1c.core.edt.lang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com.codepilot1c.core.edt.ast.EdtServiceGateway;

public class BslSemanticServiceReadOnlyTaskTest {

    @Test
    public void symbolLookupRunsInsideBmReadOnlyTask() {
        AtomicInteger readCalls = new AtomicInteger();
        IProject project = project("DemoConfiguration"); //$NON-NLS-1$
        BslSymbolResult expected = new BslSymbolResult(
                "DemoConfiguration", "CommonModules/Orders/Module.bsl", 1, 1, 0, //$NON-NLS-1$ //$NON-NLS-2$
                "variable", "Сумма", "Сумма", "Variable", "uri://symbol", "Method", "Провести", 1, 1, 1, 6); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        TestBslSemanticService service = new TestBslSemanticService(new TestGateway(project, readCalls), expected);
        BslSymbolResult actual = service.getSymbolAtPosition(
                new BslPositionRequest("DemoConfiguration", "CommonModules/Orders/Module.bsl", 1, 1)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, readCalls.get());
        assertSame(expected, actual);
    }

    private static IProject project(String name) {
        return (IProject) Proxy.newProxyInstance(
                BslSemanticServiceReadOnlyTaskTest.class.getClassLoader(),
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
                BslSemanticServiceReadOnlyTaskTest.class.getClassLoader(),
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

    private static final class TestBslSemanticService extends BslSemanticService {
        private final BslSymbolResult expected;

        TestBslSemanticService(EdtServiceGateway gateway, BslSymbolResult expected) {
            super(gateway);
            this.expected = expected;
        }

        @Override
        BslSymbolResult doGetSymbolAtPosition(BslPositionRequest request) {
            return expected;
        }
    }
}
