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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationListener;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Pins EDT 2025.2 association-strictness fixes in {@code EdtInfobaseConnectService.associate}:
 * canonical-reference reconciliation, context-provider integration, force-true dissociate,
 * INFOBASE_ALREADY_BOUND typing, and graceful degradation when setDefaultInfobase fails.
 */
public class EdtInfobaseConnectAssociationReconciliationTest {

    @Test
    public void reconciliationRoutesCanonicalReferenceToSetDefaultInfobase() {
        IProject project = stubProject("cf"); //$NON-NLS-1$
        UUID uuid = UUID.randomUUID();
        InfobaseReference original = stubReferenceWithUuid(uuid, "feature"); //$NON-NLS-1$
        InfobaseReference canonical = stubReferenceWithUuid(uuid, "feature"); //$NON-NLS-1$
        Edt22StrictManager mgr = new Edt22StrictManager(canonical);
        TestableConnectService svc = new TestableConnectService(new StubGateway(mgr));

        boolean primary = svc.invokeAssociate(project, original, /* setPrimary= */ true);

        assertTrue("setPrimary=true must return true on success", primary); //$NON-NLS-1$
        assertSame("associate(...) must receive the original caller-supplied reference", //$NON-NLS-1$
                original, mgr.lastAssociated.get());
        assertNotNull("setDefaultInfobase must have been called", //$NON-NLS-1$
                mgr.lastDefaulted.get());
        assertSame("setDefaultInfobase must receive the CANONICAL reference (from getAssociation), " //$NON-NLS-1$
                + "not the caller's original — this is what fails on EDT 2025.2 without reconciliation",
                canonical, mgr.lastDefaulted.get());
        assertNotSame("regression contract: canonical and original must be different Java instances " //$NON-NLS-1$
                + "for the identity check in the stub manager to be meaningful",
                original, canonical);
    }

    @Test
    public void setDefaultInfobaseIllegalArgumentGracefullyDegrades() {
        // EDT 2025.2 context-provider drift: setDefaultInfobase IAE → primary=false, not fatal.
        IProject project = stubProject("cf"); //$NON-NLS-1$
        InfobaseReference ref = stubReferenceWithUuid(UUID.randomUUID(), "feature"); //$NON-NLS-1$
        AlwaysThrowingDefaultManager mgr = new AlwaysThrowingDefaultManager(ref);
        TestableConnectService svc = new TestableConnectService(new StubGateway(mgr));

        boolean primary = svc.invokeAssociate(project, ref, /* setPrimary= */ true);

        assertFalse("setDefaultInfobase IAE must not fail the whole connect — return primary=false", //$NON-NLS-1$
                primary);
    }

    @Test
    public void gracefulDegradeReportsPrimaryTrueWhenEdtAlreadyHasIt() {
        // After setDefaultInfobase IAE, query getAssociation to detect when EDT actually has
        // the infobase marked as default (e.g. set via GUI in a previous session). Report
        // primary=true truthfully instead of always returning false.
        IProject project = stubProject("cf"); //$NON-NLS-1$
        UUID uuid = UUID.randomUUID();
        InfobaseReference ref = stubReferenceWithUuid(uuid, "feature"); //$NON-NLS-1$
        DefaultAlreadySetThrowingManager mgr = new DefaultAlreadySetThrowingManager(ref);
        TestableConnectService svc = new TestableConnectService(new StubGateway(mgr));

        boolean primary = svc.invokeAssociate(project, ref, /* setPrimary= */ true);

        assertTrue("EDT had defaultInfobase set already — must report primary=true", primary); //$NON-NLS-1$
    }

    @Test
    public void setDefaultInfobaseNonAssociationIllegalArgumentStillThrows() {
        // Unexpected IAE (not "is not associated") must still surface as typed error.
        IProject project = stubProject("cf"); //$NON-NLS-1$
        InfobaseReference ref = stubReferenceWithUuid(UUID.randomUUID(), "feature"); //$NON-NLS-1$
        UnexpectedIaeManager mgr = new UnexpectedIaeManager(ref);
        TestableConnectService svc = new TestableConnectService(new StubGateway(mgr));

        try {
            svc.invokeAssociate(project, ref, /* setPrimary= */ true);
            fail("expected EdtToolException for non-association IAE"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.INFOBASE_ASSOCIATION_NOT_FOUND, e.getCode());
            assertTrue("message must carry through the unexpected detail, was: " + e.getMessage(), //$NON-NLS-1$
                    e.getMessage().contains("some unexpected internal precondition")); //$NON-NLS-1$
        }
    }

    @Test
    public void associateAndSetDefaultInfobaseShareProviderSuppliedContext() {
        // Provider ctx must reach both associate() and setDefaultInfobase().
        IProject project = stubProject("cf"); //$NON-NLS-1$
        UUID uuid = UUID.randomUUID();
        InfobaseReference ref = stubReferenceWithUuid(uuid, "feature"); //$NON-NLS-1$

        InfobaseAssociationContext providerContext = InfobaseAssociationContext.of("CUSTOM"); //$NON-NLS-1$
        RecordingContextProvider provider = new RecordingContextProvider(providerContext);
        ContextCapturingManager mgr = new ContextCapturingManager(ref);
        TestableConnectService svc = new TestableConnectService(new StubGateway(mgr, provider));

        boolean primary = svc.invokeAssociate(project, ref, /* setPrimary= */ true);

        assertTrue("setPrimary=true must succeed when context is consistent", primary); //$NON-NLS-1$
        assertEquals("IInfobaseAssociationContextProvider.get(project) must be called exactly once", //$NON-NLS-1$
                1, provider.getCalls.get());
        assertNotNull("associate() must be invoked with non-null settings", //$NON-NLS-1$
                mgr.lastSettings.get());
        assertSame("associate() settings must carry the provider-supplied context", //$NON-NLS-1$
                providerContext, mgr.lastSettings.get().getContext());
        assertSame("setDefaultInfobase() must receive the SAME context as associate() — " //$NON-NLS-1$
                + "this is the regression on EDT 2025.2",
                providerContext, mgr.lastSetDefaultContext.get());
    }

    @Test
    public void providerUnavailableFallsBackToEmptyContext() {
        // Provider unavailable → fall back to empty() ctx (EDT 2025.1 behaviour).
        IProject project = stubProject("cf"); //$NON-NLS-1$
        UUID uuid = UUID.randomUUID();
        InfobaseReference ref = stubReferenceWithUuid(uuid, "feature"); //$NON-NLS-1$

        ContextCapturingManager mgr = new ContextCapturingManager(ref);
        TestableConnectService svc = new TestableConnectService(new StubGateway(mgr));

        boolean primary = svc.invokeAssociate(project, ref, /* setPrimary= */ true);

        assertTrue("fallback path must still set primary successfully", primary); //$NON-NLS-1$
        assertEquals("fallback must use InfobaseAssociationContext.empty()", //$NON-NLS-1$
                InfobaseAssociationContext.empty(), mgr.lastSettings.get().getContext());
        assertEquals("setDefaultInfobase must also receive empty() — consistent with associate", //$NON-NLS-1$
                InfobaseAssociationContext.empty(), mgr.lastSetDefaultContext.get());
    }

    @Test
    public void associateIllegalArgumentWrappedNotEscaping() {
        IProject project = stubProject("cf"); //$NON-NLS-1$
        InfobaseReference ref = stubReferenceWithUuid(UUID.randomUUID(), "feature"); //$NON-NLS-1$
        // IAE from associate() must be caught, not escape to a generic handler.
        AssociateThrowingManager mgr = new AssociateThrowingManager();
        TestableConnectService svc = new TestableConnectService(new StubGateway(mgr));

        try {
            svc.invokeAssociate(project, ref, /* setPrimary= */ false);
            fail("expected EdtToolException — IAE from associate() must not escape"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, e.getCode());
            assertNotNull(e.getMessage());
            assertTrue("wrap message must include 'Failed to associate' prefix, was: " //$NON-NLS-1$
                    + e.getMessage(), e.getMessage().contains("Failed to associate")); //$NON-NLS-1$
            assertTrue("wrap message must preserve the EDT detail, was: " + e.getMessage(), //$NON-NLS-1$
                    e.getMessage().contains("simulated-2025.2-precondition-fail")); //$NON-NLS-1$
        }
    }

    @Test
    public void forceTrueWithSameUuidSkipsAssociateAndDissociate() {
        // Same UUID already bound → skip associate (avoid EDT "already connected"); still set primary.
        IProject project = stubProject("cf"); //$NON-NLS-1$
        UUID uuid = UUID.randomUUID();
        InfobaseReference bound = stubReferenceWithUuid(uuid, "feature"); //$NON-NLS-1$
        InfobaseReference incoming = stubReferenceWithUuid(uuid, "feature"); //$NON-NLS-1$

        ForceDissociateManager mgr = new ForceDissociateManager(bound);
        TestableConnectService svc = new TestableConnectService(new StubGateway(mgr));

        boolean primary = svc.invokeAssociate(project, incoming, /* setPrimary= */ true, /* force= */ true);

        assertTrue(primary);
        assertEquals("associate() must NOT be re-called when same UUID is already bound", //$NON-NLS-1$
                0, mgr.associateCalls.get());
        assertEquals("dissociate() must NOT be called when same UUID is already bound", //$NON-NLS-1$
                0, mgr.dissociateCalls.get());
        assertEquals("setDefaultInfobase must still be called to honour set_primary=true", //$NON-NLS-1$
                1, mgr.setDefaultCalls.get());
    }

    @Test
    public void forceTrueWithDifferentUuidDissociatesThenAssociates() {
        IProject project = stubProject("cf"); //$NON-NLS-1$
        InfobaseReference bound = stubReferenceWithUuid(UUID.randomUUID(), "old-base"); //$NON-NLS-1$
        InfobaseReference incoming = stubReferenceWithUuid(UUID.randomUUID(), "feature"); //$NON-NLS-1$

        ForceDissociateManager mgr = new ForceDissociateManager(bound);
        TestableConnectService svc = new TestableConnectService(new StubGateway(mgr));

        boolean primary = svc.invokeAssociate(project, incoming, /* setPrimary= */ true, /* force= */ true);

        assertTrue(primary);
        assertEquals("dissociate() must be called once to unbind the old infobase", //$NON-NLS-1$
                1, mgr.dissociateCalls.get());
        assertSame("dissociate() must receive the OLD canonical reference, not the incoming one", //$NON-NLS-1$
                bound, mgr.lastDissociated.get());
        assertEquals("associate() must be called once for the new infobase", //$NON-NLS-1$
                1, mgr.associateCalls.get());
        assertSame("associate() must receive the INCOMING (new) reference", //$NON-NLS-1$
                incoming, mgr.lastAssociatedRef.get());
    }

    @Test
    public void forceFalseWithDifferentUuidThrowsInfobaseAlreadyBound() {
        IProject project = stubProject("cf"); //$NON-NLS-1$
        InfobaseReference bound = stubReferenceWithUuid(UUID.randomUUID(), "old-base"); //$NON-NLS-1$
        InfobaseReference incoming = stubReferenceWithUuid(UUID.randomUUID(), "feature"); //$NON-NLS-1$

        ForceDissociateManager mgr = new ForceDissociateManager(bound);
        TestableConnectService svc = new TestableConnectService(new StubGateway(mgr));

        try {
            svc.invokeAssociate(project, incoming, /* setPrimary= */ true, /* force= */ false);
            fail("expected EdtToolException(INFOBASE_ALREADY_BOUND)"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.INFOBASE_ALREADY_BOUND, e.getCode());
            assertTrue("message must include the OLD infobase name, was: " + e.getMessage(), //$NON-NLS-1$
                    e.getMessage().contains("old-base")); //$NON-NLS-1$
            assertTrue("message must hint at force=true, was: " + e.getMessage(), //$NON-NLS-1$
                    e.getMessage().contains("force=true")); //$NON-NLS-1$
        }
        assertEquals("dissociate() must NOT be called when force=false", //$NON-NLS-1$
                0, mgr.dissociateCalls.get());
        assertEquals("associate() must NOT be called when blocked by force=false", //$NON-NLS-1$
                0, mgr.associateCalls.get());
    }

    @Test
    public void associateAlreadyConnectedExceptionMapsToInfobaseAlreadyBound() {
        // EDT's own "already connected" from associate() must surface as INFOBASE_ALREADY_BOUND.
        IProject project = stubProject("cf"); //$NON-NLS-1$
        InfobaseReference incoming = stubReferenceWithUuid(UUID.randomUUID(), "feature"); //$NON-NLS-1$

        AlreadyConnectedManager mgr = new AlreadyConnectedManager();
        TestableConnectService svc = new TestableConnectService(new StubGateway(mgr));

        try {
            svc.invokeAssociate(project, incoming, /* setPrimary= */ false, /* force= */ false);
            fail("expected EdtToolException(INFOBASE_ALREADY_BOUND)"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.INFOBASE_ALREADY_BOUND, e.getCode());
            assertTrue("message must preserve the EDT detail, was: " + e.getMessage(), //$NON-NLS-1$
                    e.getMessage().toLowerCase().contains("already connected")); //$NON-NLS-1$
        }
    }

    // -- helpers ------------------------------------------------------------------------------

    private static IProject stubProject(String name) {
        return (IProject) Proxy.newProxyInstance(
                IProject.class.getClassLoader(),
                new Class<?>[] { IProject.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name; //$NON-NLS-1$
                    case "exists" -> Boolean.TRUE; //$NON-NLS-1$
                    case "isOpen" -> Boolean.TRUE; //$NON-NLS-1$
                    case "toString" -> "StubProject[" + name + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                    case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                    default -> defaultReturnFor(method.getReturnType());
                });
    }

    private static InfobaseReference stubReferenceWithUuid(UUID initialUuid, String initialName) {
        AtomicReference<UUID> uuidSlot = new AtomicReference<>(initialUuid);
        AtomicReference<String> nameSlot = new AtomicReference<>(initialName);
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
        if (returnType.isPrimitive()) return Integer.valueOf(0);
        return null;
    }

    private static final class StubGateway extends EdtRuntimeGateway {
        private final IInfobaseAssociationManager associationManager;
        private final com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider contextProvider;

        StubGateway(IInfobaseAssociationManager associationManager) {
            this(associationManager, /* contextProvider= */ null);
        }

        StubGateway(IInfobaseAssociationManager associationManager,
                com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider contextProvider) {
            this.associationManager = associationManager;
            this.contextProvider = contextProvider;
        }

        @Override
        public IInfobaseAssociationManager getInfobaseAssociationManager() {
            return associationManager;
        }

        @Override
        public com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider
                getInfobaseAssociationContextProvider() {
            if (contextProvider == null) {
                // Default for legacy tests: simulate "service unavailable" so production code
                // falls back to InfobaseAssociationContext.empty(), preserving the historic
                // EDT 2025.1 behaviour the tests were written against.
                throw new IllegalStateException("test: IInfobaseAssociationContextProvider not provided"); //$NON-NLS-1$
            }
            return contextProvider;
        }
    }

    /** Exposes the protected {@code associate(...)} method for direct invocation. */
    private static final class TestableConnectService extends EdtInfobaseConnectService {
        TestableConnectService(EdtRuntimeGateway gateway) {
            super(gateway);
        }

        boolean invokeAssociate(IProject project, InfobaseReference reference, boolean setPrimary) {
            return associate(project, reference, setPrimary, /* force= */ false);
        }

        boolean invokeAssociate(IProject project, InfobaseReference reference, boolean setPrimary,
                boolean force) {
            return associate(project, reference, setPrimary, force);
        }
    }

    private abstract static class BaseAssociationManagerStub implements IInfobaseAssociationManager {
        @Override
        public void activate() {
            // IManagedService surface method — not exercised by these tests.
        }

        @Override
        public void deactivate() {
            // IManagedService surface method — not exercised by these tests.
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(InfobaseReference reference) {
            return Optional.empty();
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(InfobaseReference reference,
                InfobaseAssociationContext context) {
            return Optional.empty();
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project,
                InfobaseAssociationContext context) {
            return Optional.empty();
        }

        @Override
        public Collection<InfobaseAssociationContext> getAssociationContexts(IProject project) {
            return List.of();
        }

        @Override
        public void dissociate(IProject project, InfobaseReference reference,
                InfobaseAssociationContext context) { }

        @Override
        public void addInfobaseAssociationListener(IInfobaseAssociationListener listener) { }

        @Override
        public void removeInfobaseAssociationListener(IInfobaseAssociationListener listener) { }
    }

    /**
     * Mirrors EDT 2025.2 behaviour: associate() stores a canonical copy; setDefaultInfobase()
     * strict-checks identity and throws bare IllegalArgumentException when the supplied
     * reference is not the canonical instance.
     */
    private static final class Edt22StrictManager extends BaseAssociationManagerStub {
        private final InfobaseReference canonical;
        final AtomicReference<InfobaseReference> lastAssociated = new AtomicReference<>();
        final AtomicReference<InfobaseReference> lastDefaulted = new AtomicReference<>();
        private IInfobaseAssociation association;

        Edt22StrictManager(InfobaseReference canonical) {
            this.canonical = canonical;
        }

        @Override
        public void associate(IProject project, InfobaseReference reference,
                InfobaseAssociationSettings settings) {
            lastAssociated.set(reference);
            // Production hands in `reference`; EDT internally remembers `canonical` (a
            // different Java instance with the same UUID). Reconciliation must find this.
            association = new StubAssociation(project, canonical);
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project) {
            return Optional.ofNullable(association);
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project,
                InfobaseAssociationContext context) {
            // Production reconciliation now uses 2-arg getAssociation explicitly with the same
            // context that associate() was called under; mirror the 1-arg behaviour so the
            // stub returns the canonical association regardless of context.
            return Optional.ofNullable(association);
        }

        @Override
        public void setDefaultInfobase(IProject project, InfobaseReference reference,
                InfobaseAssociationContext context) {
            if (reference != canonical) {
                // EDT 2025.2's bare precondition failure — message mirrors what we see in the
                // field on EDT 2025.2.5+2.
                throw new IllegalArgumentException(
                        "Project " + (project == null ? "?" : "cf") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                + " is not associated with infobase " //$NON-NLS-1$
                                + (reference == null ? "?" : "feature")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            lastDefaulted.set(reference);
        }
    }

    private static final class AlwaysThrowingDefaultManager extends BaseAssociationManagerStub {
        private final InfobaseReference bound;
        private IInfobaseAssociation association;

        AlwaysThrowingDefaultManager(InfobaseReference bound) {
            this.bound = bound;
        }

        @Override
        public void associate(IProject project, InfobaseReference reference,
                InfobaseAssociationSettings settings) {
            // setDefaultInfobase failed-state: binding exists in getInfobases() but
            // defaultInfobase is null (storeProperty("DefaultInfobase", ...) never ran).
            association = new AssociationWithNullDefault(project, bound);
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project) {
            return Optional.ofNullable(association);
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project,
                InfobaseAssociationContext context) {
            return Optional.ofNullable(association);
        }

        @Override
        public void setDefaultInfobase(IProject project, InfobaseReference reference,
                InfobaseAssociationContext context) {
            throw new IllegalArgumentException("Project cf is not associated with infobase feature"); //$NON-NLS-1$
        }
    }

    /**
     * Like {@link AlwaysThrowingDefaultManager} but the underlying association ALSO reports
     * defaultInfobase as set (mirrors EDT state where defaultInfobase was set via GUI before
     * our call and setDefaultInfobase IAE happened on context lookup).
     */
    private static final class DefaultAlreadySetThrowingManager extends BaseAssociationManagerStub {
        private final InfobaseReference bound;
        private IInfobaseAssociation association;

        DefaultAlreadySetThrowingManager(InfobaseReference bound) {
            this.bound = bound;
        }

        @Override
        public void associate(IProject project, InfobaseReference reference,
                InfobaseAssociationSettings settings) {
            association = new StubAssociation(project, bound); // getDefaultInfobase returns `bound`
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project) {
            return Optional.ofNullable(association);
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project,
                InfobaseAssociationContext context) {
            return Optional.ofNullable(association);
        }

        @Override
        public void setDefaultInfobase(IProject project, InfobaseReference reference,
                InfobaseAssociationContext context) {
            throw new IllegalArgumentException("Project cf is not associated with infobase feature"); //$NON-NLS-1$
        }
    }

    /** Association where getDefaultInfobase() is null — mirrors EDT state after setDefault failed. */
    private static final class AssociationWithNullDefault implements IInfobaseAssociation {
        private final IProject project;
        private final InfobaseReference bound;

        AssociationWithNullDefault(IProject project, InfobaseReference bound) {
            this.project = project;
            this.bound = bound;
        }

        @Override
        public IProject getProject() { return project; }

        @Override
        public Collection<InfobaseReference> getInfobases() {
            return bound == null ? List.of() : List.of(bound);
        }

        @Override
        public InfobaseReference getDefaultInfobase() { return null; }
    }

    /**
     * Stub that records the {@link InfobaseAssociationSettings} passed to {@code associate(...)}
     * and the {@code InfobaseAssociationContext} passed to {@code setDefaultInfobase(...)} so the
     * test can verify both calls received the same context (the one returned by
     * {@code IInfobaseAssociationContextProvider}).
     */
    private static final class ContextCapturingManager extends BaseAssociationManagerStub {
        private final InfobaseReference bound;
        final AtomicReference<InfobaseAssociationSettings> lastSettings = new AtomicReference<>();
        final AtomicReference<InfobaseAssociationContext> lastSetDefaultContext = new AtomicReference<>();
        private IInfobaseAssociation association;

        ContextCapturingManager(InfobaseReference bound) {
            this.bound = bound;
        }

        @Override
        public void associate(IProject project, InfobaseReference reference,
                InfobaseAssociationSettings settings) {
            lastSettings.set(settings);
            association = new StubAssociation(project, bound);
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project) {
            return Optional.ofNullable(association);
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project,
                InfobaseAssociationContext context) {
            return Optional.ofNullable(association);
        }

        @Override
        public void setDefaultInfobase(IProject project, InfobaseReference reference,
                InfobaseAssociationContext context) {
            lastSetDefaultContext.set(context);
            // Don't throw — the assertion is on the captured context, not on identity.
        }
    }

    /**
     * Stub that pre-binds a project to a given {@code InfobaseReference} via {@code associate()}
     * and tracks calls to {@code associate / dissociate / setDefaultInfobase} so the test can
     * verify the force-true / force-false / same-uuid flows.
     */
    private static final class ForceDissociateManager extends BaseAssociationManagerStub {
        private IInfobaseAssociation association;
        final java.util.concurrent.atomic.AtomicInteger associateCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger dissociateCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger setDefaultCalls = new java.util.concurrent.atomic.AtomicInteger();
        final AtomicReference<InfobaseReference> lastAssociatedRef = new AtomicReference<>();
        final AtomicReference<InfobaseReference> lastDissociated = new AtomicReference<>();

        ForceDissociateManager(InfobaseReference preBound) {
            // Pre-seed the project as already associated with the given infobase.
            this.association = new StubAssociation(/* project= */ null, preBound);
        }

        @Override
        public void associate(IProject project, InfobaseReference reference,
                InfobaseAssociationSettings settings) {
            associateCalls.incrementAndGet();
            lastAssociatedRef.set(reference);
            this.association = new StubAssociation(project, reference);
        }

        @Override
        public void dissociate(IProject project, InfobaseReference reference,
                InfobaseAssociationContext context) {
            dissociateCalls.incrementAndGet();
            lastDissociated.set(reference);
            this.association = null;
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project) {
            return Optional.ofNullable(association);
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project,
                InfobaseAssociationContext context) {
            return Optional.ofNullable(association);
        }

        @Override
        public void setDefaultInfobase(IProject project, InfobaseReference reference,
                InfobaseAssociationContext context) {
            setDefaultCalls.incrementAndGet();
        }
    }

    /**
     * Stub whose {@code setDefaultInfobase()} throws an unexpected {@link IllegalArgumentException}
     * (message does NOT contain "is not associated" / "does not contain infobase") — used to
     * verify the catch is NOT over-eager and still surfaces unknown failures as typed errors.
     */
    private static final class UnexpectedIaeManager extends BaseAssociationManagerStub {
        private IInfobaseAssociation association;
        private final InfobaseReference bound;

        UnexpectedIaeManager(InfobaseReference bound) {
            this.bound = bound;
        }

        @Override
        public void associate(IProject project, InfobaseReference reference,
                InfobaseAssociationSettings settings) {
            association = new StubAssociation(project, bound);
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project) {
            return Optional.ofNullable(association);
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project,
                InfobaseAssociationContext context) {
            return Optional.ofNullable(association);
        }

        @Override
        public void setDefaultInfobase(IProject project, InfobaseReference reference,
                InfobaseAssociationContext context) {
            throw new IllegalArgumentException("some unexpected internal precondition failure"); //$NON-NLS-1$
        }
    }

    /**
     * Stub whose {@code associate()} throws {@link InfobaseAssociationException} with EDT's
     * "Infobase X is already connected" message — exercises the special-case mapping to
     * INFOBASE_ALREADY_BOUND in production.
     */
    private static final class AlreadyConnectedManager extends BaseAssociationManagerStub {
        @Override
        public void associate(IProject project, InfobaseReference reference,
                InfobaseAssociationSettings settings) {
            throw new InfobaseAssociationException("Infobase feature is already connected"); //$NON-NLS-1$
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project) {
            return Optional.empty();
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project,
                InfobaseAssociationContext context) {
            return Optional.empty();
        }

        @Override
        public void setDefaultInfobase(IProject project, InfobaseReference reference,
                InfobaseAssociationContext context) {
            fail("setDefaultInfobase must not be called when associate() fails"); //$NON-NLS-1$
        }
    }

    /** Records calls to {@code get(IProject)} and returns the pre-configured context. */
    private static final class RecordingContextProvider
            implements com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider {
        private final InfobaseAssociationContext ctx;
        final java.util.concurrent.atomic.AtomicInteger getCalls = new java.util.concurrent.atomic.AtomicInteger();

        RecordingContextProvider(InfobaseAssociationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public InfobaseAssociationContext get(IProject project) {
            getCalls.incrementAndGet();
            return ctx;
        }

        @Override
        public void addListener(IInfobaseAssociationContextProvider.IInfobaseAssociationContextListener l) { }

        @Override
        public void removeListener(IInfobaseAssociationContextProvider.IInfobaseAssociationContextListener l) { }
    }

    private static final class AssociateThrowingManager extends BaseAssociationManagerStub {
        @Override
        public void associate(IProject project, InfobaseReference reference,
                InfobaseAssociationSettings settings) {
            // EDT 2025.2 precondition fails at associate() — IAE escapes the legacy narrow catch.
            throw new IllegalArgumentException("simulated-2025.2-precondition-fail"); //$NON-NLS-1$
        }

        @Override
        public Optional<IInfobaseAssociation> getAssociation(IProject project) {
            return Optional.empty();
        }

        @Override
        public void setDefaultInfobase(IProject project, InfobaseReference reference,
                InfobaseAssociationContext context) {
            fail("setDefaultInfobase must not be called when associate() failed"); //$NON-NLS-1$
        }
    }

    private static final class StubAssociation implements IInfobaseAssociation {
        private final IProject project;
        private final InfobaseReference infobase;

        StubAssociation(IProject project, InfobaseReference infobase) {
            this.project = project;
            this.infobase = infobase;
        }

        @Override
        public IProject getProject() {
            return project;
        }

        @Override
        public Collection<InfobaseReference> getInfobases() {
            return infobase == null ? List.of() : List.of(infobase);
        }

        @Override
        public InfobaseReference getDefaultInfobase() {
            return infobase;
        }
    }

    /**
     * Anchor to keep the import for {@link InfobaseAssociationException} live — we don't need
     * to throw it from the stubs but the catch in production exercises both branches of the
     * combined {@code catch (InfobaseAssociationException | IllegalArgumentException)}.
     */
    @SuppressWarnings({"unused", "ResultOfMethodCallIgnored"})
    private static void anchorAssociationExceptionImport() {
        try {
            new RuntimeException(new InfobaseAssociationException("anchor")); //$NON-NLS-1$
        } catch (RuntimeException ignored) { }
    }
}
