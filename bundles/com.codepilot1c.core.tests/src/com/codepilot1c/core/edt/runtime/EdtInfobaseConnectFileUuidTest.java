package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/** Checks the identity actually saved by EDT, rather than only the final in-memory reference. */
public class EdtInfobaseConnectFileUuidTest {
    @Test
    public void firstRegistrationPersistsTheSameNonNullUuidUsedForAssociation() {
        AtomicReference<InfobaseReference> stored = new AtomicReference<>();
        InfobaseReference reference = newReference();
        service(manager(stored)).persistReference(reference);
        assertNotNull(stored.get());
        assertNotNull("The saved launcher entry must not contain ID=null", stored.get().getUuid());
        assertEquals("Project association must resolve the saved row", stored.get().getUuid(), reference.getUuid());
    }

    @Test
    public void retryReusesThePersistedIdentityWithoutAddingAnotherRow() {
        AtomicReference<InfobaseReference> stored = new AtomicReference<>(newReference());
        UUID original = UUID.randomUUID();
        stored.get().setUuid(original);
        InfobaseReference reference = newReference();
        service(manager(stored)).persistReference(reference);
        assertEquals(original, reference.getUuid());
        assertEquals(original, stored.get().getUuid());
    }

    @Test
    public void corruptPersistedIdentityIsReportedInsteadOfInventingAnUnresolvableUuid() {
        AtomicReference<InfobaseReference> stored = new AtomicReference<>(newReference());
        InfobaseReference reference = newReference();
        try {
            service(manager(stored)).persistReference(reference);
            fail("An unsaved replacement identity would leave EDT partially connected");
        } catch (EdtToolException expected) {
            assertEquals(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, expected.getCode());
            assertTrue(expected.getMessage().contains("persisted ID"));
        }
        assertNull(stored.get().getUuid());
        assertNull(reference.getUuid());
    }

    private static InfobaseReference newReference() {
        InfobaseReference reference = InfobaseReferences.newFileInfobaseReference("file-uuid-regression");
        reference.setName("file-uuid-regression");
        return reference;
    }

    private static EdtInfobaseConnectService service(IInfobaseManager manager) {
        return new EdtInfobaseConnectService(new EdtRuntimeGateway() {
            @Override
            public IInfobaseManager getInfobaseManager() { return manager; }
        });
    }

    private static IInfobaseManager manager(AtomicReference<InfobaseReference> stored) {
        return (IInfobaseManager) Proxy.newProxyInstance(IInfobaseManager.class.getClassLoader(),
                new Class<?>[] { IInfobaseManager.class }, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "isPersistenceSupported" -> true;
                        case "findInfobaseByUuid", "findInfobaseByName" -> Optional.empty();
                        case "findInfobasesByNames" -> stored.get() == null ? List.of() : List.of(stored.get());
                        case "add" -> {
                            assertNull("Registration must not add a duplicate entry", stored.get());
                            // EDT serializes at this boundary, before persistReference returns.
                            stored.set(EcoreUtil.copy((InfobaseReference) args[0]));
                            yield null;
                        }
                        default -> throw new AssertionError("Unexpected manager call: " + method.getName());
                    };
                });
    }
}
