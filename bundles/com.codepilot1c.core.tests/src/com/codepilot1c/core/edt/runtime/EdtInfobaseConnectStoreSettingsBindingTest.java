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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettingsChangeListener;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.version.Version;

import org.eclipse.core.resources.IProject;

/**
 * Regression test for the EDT 2025.2.x API skew: the production code in
 * {@link EdtInfobaseConnectService#storeAccessSettings} must bind to
 * {@code IInfobaseAccessManager.updateSettings(InfobaseReference, IInfobaseAccessSettings)}
 * — not to {@code storeSettings(...)}, which was removed in EDT 2025.2 and causes a bare
 * {@link NoSuchMethodError} at runtime that breaks {@code connect_infobase} on every
 * project.
 *
 * <p>The stub manager below throws {@link NoSuchMethodError} from both
 * {@code storeSettings} overloads (mirroring the actual EDT 2025.2 runtime) and records
 * calls to {@code updateSettings}. If production regresses to call any {@code storeSettings}
 * overload, the test fails with the same diagnostic seen in the field.</p>
 *
 * <p>The simulated NoSuchMethodError mirrors EDT 2025.2.5+2 behaviour observed when calling
 * {@code IInfobaseAccessManager.storeSettings(InfobaseReference, IInfobaseAccessSettings)}
 * on the 2025.2.5+2 service registry while the bundle was compiled against the 2025.1.5
 * interface variant.</p>
 */
public class EdtInfobaseConnectStoreSettingsBindingTest {

    @Test
    public void storeAccessSettingsBindsToUpdateSettingsNotRemovedStoreSettings() {
        RecordingAccessManager accessManager = new RecordingAccessManager();
        StubGateway gateway = new StubGateway(accessManager);
        EdtInfobaseConnectService service = new EdtInfobaseConnectService(gateway);

        InfobaseReference reference = stubReferenceWithUuid(UUID.randomUUID(), "demo-ib"); //$NON-NLS-1$

        // Must not throw — production code must bind to updateSettings(), which is present
        // on both EDT 2025.1 and 2025.2. Calling any storeSettings overload on the stub
        // would throw NoSuchMethodError just like the real EDT 2025.2 runtime.
        service.storeAccessSettings(reference, "Administrator", "Hunter2"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("storeAccessSettings must call updateSettings exactly once", //$NON-NLS-1$
                1, accessManager.updateSettingsCalls.get());
        assertEquals("storeAccessSettings must NOT call the EDT 2025.2-removed storeSettings(IInfobaseAccessSettings)", //$NON-NLS-1$
                0, accessManager.storeSettingsObjectCalls.get());
        assertEquals("storeAccessSettings must NOT call the EDT 2025.2-removed storeSettings(InfobaseAccess, ...) overload", //$NON-NLS-1$
                0, accessManager.storeSettingsExpandedCalls.get());

        IInfobaseAccessSettings captured = accessManager.lastUpdatedSettings.get();
        assertNotNull("captor must observe the settings payload", captured); //$NON-NLS-1$
        assertEquals("infobase auth selected when login is non-blank", //$NON-NLS-1$
                InfobaseAccess.INFOBASE, captured.access());
        assertEquals("Administrator", captured.userName()); //$NON-NLS-1$
        assertEquals("Hunter2", captured.password()); //$NON-NLS-1$
    }

    @Test
    public void noSuchMethodErrorFromUpdateSettingsSurfacesAsTypedException() {
        // Defensive: if a future EDT renames updateSettings too, the user must see the
        // missing-method diagnostic instead of the opaque generic ': null' tail.
        StubGateway gateway = new StubGateway(new ThrowingUpdateSettingsManager());
        EdtInfobaseConnectService service = new EdtInfobaseConnectService(gateway);

        InfobaseReference reference = stubReferenceWithUuid(UUID.randomUUID(), "demo-ib"); //$NON-NLS-1$

        try {
            service.storeAccessSettings(reference, "Admin", "secret"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("expected EdtToolException when updateSettings is missing"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, e.getCode());
            String message = e.getMessage();
            assertNotNull(message);
            // The diagnostic must name the missing method so the next API-skew bug is
            // diagnosed in seconds, not hours.
            if (!message.contains("updateSettings")) { //$NON-NLS-1$
                fail("EdtToolException message must name the missing method, was: " + message); //$NON-NLS-1$
            }
        }
    }

    // -- stubs ----------------------------------------------------------------------------

    /** Reference stub with persistent UUID/Name slots — same pattern as ConnectInfobaseToolTest. */
    private static InfobaseReference stubReferenceWithUuid(UUID initialUuid, String name) {
        AtomicReference<UUID> uuidSlot = new AtomicReference<>(initialUuid);
        AtomicReference<String> nameSlot = new AtomicReference<>(name);
        return (InfobaseReference) Proxy.newProxyInstance(
                InfobaseReference.class.getClassLoader(),
                new Class<?>[] { InfobaseReference.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUuid" -> uuidSlot.get(); //$NON-NLS-1$
                    case "setUuid" -> { uuidSlot.set((UUID) args[0]); yield null; } //$NON-NLS-1$
                    case "getName" -> nameSlot.get(); //$NON-NLS-1$
                    case "setName" -> { nameSlot.set((String) args[0]); yield null; } //$NON-NLS-1$
                    case "toString" -> "StubReference[" + nameSlot.get() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                    case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                    default -> defaultReturnFor(method.getReturnType());
                });
    }

    private static Object defaultReturnFor(Class<?> returnType) {
        if (returnType == boolean.class) return Boolean.FALSE;
        if (returnType == int.class) return Integer.valueOf(0);
        if (returnType == long.class) return Long.valueOf(0L);
        if (returnType.isPrimitive()) return Integer.valueOf(0);
        return null;
    }

    /** Gateway whose only relevant getter is {@link #getInfobaseAccessManager()}. */
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
     * Stub IInfobaseAccessManager whose {@code storeSettings} overloads throw
     * {@link NoSuchMethodError} (mirroring EDT 2025.2.x) and whose {@code updateSettings}
     * records the call. If production binds to either {@code storeSettings} overload the
     * NoSuchMethodError propagates and the test fails with the same diagnostic seen in the
     * field on EDT 2025.2.5+2.
     */
    private static final class RecordingAccessManager implements IInfobaseAccessManager {
        final AtomicInteger updateSettingsCalls = new AtomicInteger();
        final AtomicInteger storeSettingsObjectCalls = new AtomicInteger();
        final AtomicInteger storeSettingsExpandedCalls = new AtomicInteger();
        final AtomicReference<IInfobaseAccessSettings> lastUpdatedSettings = new AtomicReference<>();

        @Override
        public IInfobaseAccessSettings getSettings(InfobaseReference reference) { return null; }

        @Override
        public IInfobaseAccessSettings getSettings(InfobaseReference reference, InfobaseAccess access) { return null; }

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
        public void storeSettings(InfobaseReference reference, IInfobaseAccessSettings settings) {
            storeSettingsObjectCalls.incrementAndGet();
            throw new NoSuchMethodError("IInfobaseAccessManager.storeSettings(InfobaseReference, " //$NON-NLS-1$
                    + "IInfobaseAccessSettings) — removed in EDT 2025.2"); //$NON-NLS-1$
        }

        @Override
        public void storeSettings(InfobaseReference reference, InfobaseAccess access, String userName,
                String password, String additionalProperties) {
            storeSettingsExpandedCalls.incrementAndGet();
            throw new NoSuchMethodError("IInfobaseAccessManager.storeSettings(InfobaseReference, " //$NON-NLS-1$
                    + "InfobaseAccess, String, String, String) — removed in EDT 2025.2"); //$NON-NLS-1$
        }

        @Override
        public void storeInstallation(IProject project, InfobaseReference reference,
                IResolvableRuntimeInstallation installation) {
            // No-op: not exercised by this test.
        }

        @Override
        public void addInfobaseAccessSettingsChangeListener(IInfobaseAccessSettingsChangeListener listener) {
            // No-op.
        }

        @Override
        public void removeInfobaseAccessSettingsChangeListener(IInfobaseAccessSettingsChangeListener listener) {
            // No-op.
        }

        @Override
        public void updateSettings(InfobaseReference reference, IInfobaseAccessSettings settings) {
            updateSettingsCalls.incrementAndGet();
            lastUpdatedSettings.set(settings);
        }
    }

    /**
     * Stub whose {@code updateSettings} throws NoSuchMethodError — used to verify that the
     * production-side defense-in-depth catch surfaces the missing-method diagnostic
     * instead of the generic ": null" tail.
     */
    private static final class ThrowingUpdateSettingsManager implements IInfobaseAccessManager {
        @Override
        public IInfobaseAccessSettings getSettings(InfobaseReference reference) { return null; }

        @Override
        public IInfobaseAccessSettings getSettings(InfobaseReference reference, InfobaseAccess access) { return null; }

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
        public void updateSettings(InfobaseReference reference, IInfobaseAccessSettings settings) {
            throw new NoSuchMethodError("simulated: updateSettings removed in some future EDT"); //$NON-NLS-1$
        }
    }
}
