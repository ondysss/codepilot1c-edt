package com.codepilot1c.core.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.Gson;

public class RemoteCommandResultTest {

    private final Gson gson = new Gson();

    @Test
    public void payloadIsCopiedAndImmutable() {
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("confirmationId", "confirm-1"); //$NON-NLS-1$ //$NON-NLS-2$

        RemoteCommandResult result = RemoteCommandResult.accepted(
                "confirmation_required", //$NON-NLS-1$
                "Ожидает подтверждения", //$NON-NLS-1$
                source);

        source.put("confirmationId", "mutated"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("confirm-1", result.getPayload().get("confirmationId")); //$NON-NLS-1$ //$NON-NLS-2$

        boolean immutable = false;
        try {
            result.getPayload().put("another", "value"); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        assertTrue(immutable);
    }

    @Test
    public void gsonSerializationPreservesStructuredShape() {
        RemoteCommandResult result = RemoteCommandResult.error(
                "lease_conflict", //$NON-NLS-1$
                "Управление уже удерживается другим клиентом", //$NON-NLS-1$
                Map.of("controllerClientId", "browser-1")); //$NON-NLS-1$ //$NON-NLS-2$

        @SuppressWarnings("unchecked")
        Map<String, Object> serialized = gson.fromJson(gson.toJson(result), Map.class);

        assertFalse(Boolean.TRUE.equals(serialized.get("ok"))); //$NON-NLS-1$
        assertEquals("lease_conflict", serialized.get("code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Управление уже удерживается другим клиентом", serialized.get("message")); //$NON-NLS-1$ //$NON-NLS-2$

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) serialized.get("payload"); //$NON-NLS-1$
        assertEquals("browser-1", payload.get("controllerClientId")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
