package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

public class EdtInfobaseConnectContextTest {
    @Test
    public void gitContextMakesAssociationVisibleToNativeDefaultInfobaseLookup() {
        checkContext(InfobaseAssociationContext.of("main"));
    }

    @Test
    public void projectWithoutGitStillUsesTheDefaultContext() {
        checkContext(InfobaseAssociationContext.empty());
    }

    private static void checkContext(InfobaseAssociationContext active) {
        AtomicReference<InfobaseAssociationContext> stored = new AtomicReference<>();
        AtomicReference<InfobaseAssociationContext> primary = new AtomicReference<>();
        IInfobaseAssociationManager manager = (IInfobaseAssociationManager) Proxy.newProxyInstance(
                IInfobaseAssociationManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseAssociationManager.class }, (proxy, method, args) -> {
                    if ("associate".equals(method.getName())) {
                        stored.set(((InfobaseAssociationSettings) args[2]).getContext());
                        return null;
                    }
                    if ("setDefaultInfobase".equals(method.getName())) {
                        // EDT's setDefaultInfobase looks up the project's active context.
                        if (!active.equals(stored.get())) {
                            throw new IllegalArgumentException("Project is not associated with infobase");
                        }
                        primary.set((InfobaseAssociationContext) args[2]);
                        return null;
                    }
                    throw new AssertionError("Unexpected association call: " + method.getName());
                });
        EdtRuntimeGateway gateway = new EdtRuntimeGateway() {
            @Override
            public IInfobaseAssociationManager getInfobaseAssociationManager() { return manager; }
            @Override
            public InfobaseAssociationContext getInfobaseAssociationContext(IProject project) { return active; }
        };
        InfobaseReference reference = InfobaseReferences.newFileInfobaseReference("context-regression");
        assertTrue(new EdtInfobaseConnectService(gateway).associate(null, reference, true));
        assertEquals(active, stored.get());
        assertEquals(active, primary.get());
    }
}
