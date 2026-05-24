/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettingsChangeListener;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.version.Version;

import org.eclipse.core.resources.IProject;

/**
 * Cross-version compat for {@link EdtRuntimeService#resolveAccessSettings(InfobaseReference)}:
 * on EDT 2025.2 the method must bind to {@code IInfobaseAccessManager.resolveSettings(InfobaseReference)}
 * (reflective probe), on EDT 2025.1 it must fall back to {@code getSettings(InfobaseReference, InfobaseAccess)}.
 *
 * <p>The fork's target platform is pinned to EDT 2025.1.5, so {@code resolveSettings} cannot be
 * called directly in bytecode — the compat layer probes for it via {@code Class.getMethod}.
 * The stubs below simulate both EDT generations so the binding is verified in a plain unit
 * test (no live EDT runtime required).</p>
 */
public class EdtRuntimeServiceAccessSettingsCompatTest {

    @Test
    public void edt22Path_invokesResolveSettings_skipsGetSettings() {
        Edt22AccessManager mgr = new Edt22AccessManager();
        mgr.nextResult = new InfobaseAccessSettings(InfobaseAccess.INFOBASE,
                "Admin", "Hunter2", "Locale=ru;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        EdtRuntimeService svc = new EdtRuntimeService(new StubGateway(mgr));
        EdtRuntimeService.AccessSettings out = svc.resolveAccessSettings(stubReference());

        assertNotNull("EDT 2025.2 path must return the resolved settings", out); //$NON-NLS-1$
        assertEquals("resolveSettings must be invoked exactly once on EDT 2025.2", //$NON-NLS-1$
                1, mgr.resolveSettingsCalls.get());
        assertEquals("getSettings must NOT be invoked when resolveSettings is available", //$NON-NLS-1$
                0, mgr.getSettingsCalls.get());
        assertTrue("infobase auth flag must mirror access()=INFOBASE", out.isInfobaseAuthentication()); //$NON-NLS-1$
        assertFalse(out.isOsAuthentication());
        assertEquals("Admin", out.getUserName()); //$NON-NLS-1$
        assertEquals("Hunter2", out.getPassword()); //$NON-NLS-1$
        assertEquals("Locale=ru;", out.getAdditionalParameters()); //$NON-NLS-1$
    }

    @Test
    public void edt21Path_fallsBackToGetSettings() {
        Edt21AccessManager mgr = new Edt21AccessManager();
        mgr.nextResult = new InfobaseAccessSettings(InfobaseAccess.OS, null, null, "Locale=en;"); //$NON-NLS-1$

        EdtRuntimeService svc = new EdtRuntimeService(new StubGateway(mgr));
        EdtRuntimeService.AccessSettings out = svc.resolveAccessSettings(stubReference());

        assertNotNull("EDT 2025.1 fallback must return the resolved settings", out); //$NON-NLS-1$
        assertEquals("EDT 2025.1 must use getSettings(ref, InfobaseAccess.INFOBASE)", //$NON-NLS-1$
                1, mgr.getSettingsCalls.get());
        // Filter argument expected by the 2025.1 contract.
        assertEquals(InfobaseAccess.INFOBASE, mgr.lastGetSettingsAccess.get());
        assertTrue(out.isOsAuthentication());
        assertFalse(out.isInfobaseAuthentication());
        assertEquals("Locale=en;", out.getAdditionalParameters()); //$NON-NLS-1$
    }

    @Test
    public void notDefinedSentinelMapsToNull() {
        Edt22AccessManager mgr = new Edt22AccessManager();
        mgr.nextResult = IInfobaseAccessSettings.NOT_DEFINED;

        EdtRuntimeService svc = new EdtRuntimeService(new StubGateway(mgr));
        assertNull("NOT_DEFINED sentinel must collapse to null AccessSettings", //$NON-NLS-1$
                svc.resolveAccessSettings(stubReference()));
    }

    // -- stubs --------------------------------------------------------------------------------

    private static InfobaseReference stubReference() {
        return (InfobaseReference) Proxy.newProxyInstance(
                InfobaseReference.class.getClassLoader(),
                new Class<?>[] { InfobaseReference.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "StubReference"; //$NON-NLS-1$ //$NON-NLS-2$
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                    case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                    default -> defaultReturnFor(method.getReturnType());
                });
    }

    private static Object defaultReturnFor(Class<?> returnType) {
        if (returnType == boolean.class) return Boolean.FALSE;
        if (returnType.isPrimitive()) return Integer.valueOf(0);
        return null;
    }

    private static final class StubGateway extends EdtRuntimeGateway {
        private final IInfobaseAccessManager accessManager;

        StubGateway(IInfobaseAccessManager accessManager) {
            this.accessManager = accessManager;
        }

        @Override
        public IInfobaseAccessManager getInfobaseAccessManager() {
            return accessManager;
        }
    }

    /**
     * EDT 2025.2 stub: exposes an extra public method {@code resolveSettings(InfobaseReference)}
     * that's NOT on the 2025.1 IInfobaseAccessManager interface. The compat shim must find it
     * via {@code Class.getMethod("resolveSettings", ...)} and prefer it over {@code getSettings}.
     */
    private static final class Edt22AccessManager extends BaseAccessManagerStub {
        final AtomicInteger resolveSettingsCalls = new AtomicInteger();
        IInfobaseAccessSettings nextResult;

        @SuppressWarnings("unused") // invoked reflectively by EdtRuntimeService compat shim
        public IInfobaseAccessSettings resolveSettings(InfobaseReference reference) {
            resolveSettingsCalls.incrementAndGet();
            return nextResult;
        }

        @Override
        public IInfobaseAccessSettings getSettings(InfobaseReference reference, InfobaseAccess access) {
            getSettingsCalls.incrementAndGet();
            lastGetSettingsAccess.set(access);
            return nextResult;
        }
    }

    /**
     * EDT 2025.1 stub: only the interface methods. No {@code resolveSettings} declared, so
     * the compat shim's reflective probe returns NoSuchMethodException and falls back to
     * {@code getSettings(ref, InfobaseAccess.INFOBASE)}.
     */
    private static final class Edt21AccessManager extends BaseAccessManagerStub {
        IInfobaseAccessSettings nextResult;

        @Override
        public IInfobaseAccessSettings getSettings(InfobaseReference reference, InfobaseAccess access) {
            getSettingsCalls.incrementAndGet();
            lastGetSettingsAccess.set(access);
            return nextResult;
        }
    }

    /** Common no-op surface for {@link IInfobaseAccessManager}. */
    private abstract static class BaseAccessManagerStub implements IInfobaseAccessManager {
        final AtomicInteger getSettingsCalls = new AtomicInteger();
        final AtomicReference<InfobaseAccess> lastGetSettingsAccess = new AtomicReference<>();

        @Override
        public IInfobaseAccessSettings getSettings(InfobaseReference reference) { return null; }

        @Override
        public IResolvableRuntimeInstallation getInstallation(IProject project, InfobaseReference reference) {
            return null;
        }

        @Override
        public IResolvableRuntimeInstallation getInstallation(InfobaseReference reference) {
            return null;
        }

        @Override
        public IResolvableRuntimeInstallation getInstallation(InfobaseReference reference, Version version) {
            return null;
        }

        @Override
        public void storeSettings(InfobaseReference reference, IInfobaseAccessSettings settings) { }

        @Override
        public void storeSettings(InfobaseReference reference, InfobaseAccess access, String userName,
                String password, String additionalProperties) { }

        @Override
        public void storeInstallation(IProject project, InfobaseReference reference,
                IResolvableRuntimeInstallation installation) { }

        @Override
        public void addInfobaseAccessSettingsChangeListener(IInfobaseAccessSettingsChangeListener listener) { }

        @Override
        public void removeInfobaseAccessSettingsChangeListener(IInfobaseAccessSettingsChangeListener listener) { }

        @Override
        public void updateSettings(InfobaseReference reference, IInfobaseAccessSettings settings) { }
    }
}
