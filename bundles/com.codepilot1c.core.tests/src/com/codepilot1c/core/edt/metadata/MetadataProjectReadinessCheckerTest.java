package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;

public class MetadataProjectReadinessCheckerTest {

    @Test
    public void ensureReadyUsesWaitAllComputationsForEventDrivenWait() {
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicInteger waitCalls = new AtomicInteger();

        IDerivedDataManager ddManager = proxy(IDerivedDataManager.class, (proxy, method, args) -> switch (method.getName()) {
            case "isIdle", "isAllComputed" -> Boolean.valueOf(ready.get()); //$NON-NLS-1$ //$NON-NLS-2$
            case "waitAllComputations" -> { //$NON-NLS-1$
                waitCalls.incrementAndGet();
                ready.set(true);
                yield Boolean.TRUE;
            }
            case "getDerivedDataStatus" -> null; //$NON-NLS-1$
            default -> defaultValue(method.getReturnType());
        });
        IDtProject dtProject = proxy(IDtProject.class, (proxy, method, args) -> defaultValue(method.getReturnType()));
        IDtProjectManager dtProjectManager = proxy(IDtProjectManager.class, (proxy, method, args) -> {
            if ("getDtProject".equals(method.getName())) { //$NON-NLS-1$
                return dtProject;
            }
            return defaultValue(method.getReturnType());
        });
        IDerivedDataManagerProvider provider = proxy(IDerivedDataManagerProvider.class, (proxy, method, args) -> {
            if ("get".equals(method.getName())) { //$NON-NLS-1$
                return ddManager;
            }
            return defaultValue(method.getReturnType());
        });
        EdtMetadataGateway gateway = new EdtMetadataGateway() {
            @Override
            public IDtProjectManager getDtProjectManager() {
                return dtProjectManager;
            }

            @Override
            public IDerivedDataManagerProvider getDerivedDataManagerProvider() {
                return provider;
            }
        };
        IProject project = proxy(IProject.class, (proxy, method, args) -> switch (method.getName()) {
            case "exists", "isOpen" -> Boolean.TRUE; //$NON-NLS-1$ //$NON-NLS-2$
            case "getName" -> "DemoProject"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> defaultValue(method.getReturnType());
        });

        new MetadataProjectReadinessChecker(gateway).ensureReady(project);

        assertEquals(1, waitCalls.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        return null;
    }
}
