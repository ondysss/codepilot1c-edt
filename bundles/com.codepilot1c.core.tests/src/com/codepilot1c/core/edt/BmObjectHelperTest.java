package com.codepilot1c.core.edt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Proxy;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;

public class BmObjectHelperTest {

    @Test
    public void safeTopFqnResolvesThroughTopObject() {
        IBmObject top = bmObject(true, false, null, "Catalog.Items"); //$NON-NLS-1$
        IBmObject nested = bmObject(false, false, top, null);

        assertEquals("Catalog.Items", BmObjectHelper.safeTopFqn(nested)); //$NON-NLS-1$
    }

    @Test
    public void safeTopFqnSwallowsDetachedRuntimeFailures() {
        IBmObject broken = (IBmObject) Proxy.newProxyInstance(
                IBmObject.class.getClassLoader(),
                new Class<?>[]{IBmObject.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "bmIsTransient" -> Boolean.FALSE; //$NON-NLS-1$
                        case "bmIsTop" -> Boolean.FALSE; //$NON-NLS-1$
                        case "bmGetTopObject" -> throw new RuntimeException("detached"); //$NON-NLS-1$ //$NON-NLS-2$
                        default -> defaultValue(method.getReturnType());
                    };
                });

        assertEquals("", BmObjectHelper.safeTopFqn(broken)); //$NON-NLS-1$
    }

    @Test
    public void safeIdReadsNumericGetter() {
        assertEquals(Integer.valueOf(42), BmObjectHelper.safeId(new HasId(42)));
        assertNull(BmObjectHelper.safeId(new Object()));
    }

    private static IBmObject bmObject(boolean top, boolean transientObject, IBmObject topObject, String fqn) {
        return (IBmObject) Proxy.newProxyInstance(
                IBmObject.class.getClassLoader(),
                new Class<?>[]{IBmObject.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "bmIsTop" -> Boolean.valueOf(top); //$NON-NLS-1$
                        case "bmIsTransient" -> Boolean.valueOf(transientObject); //$NON-NLS-1$
                        case "bmGetTopObject" -> topObject; //$NON-NLS-1$
                        case "bmGetFqn" -> fqn; //$NON-NLS-1$
                        default -> defaultValue(method.getReturnType());
                    };
                });
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
        if (returnType == Double.TYPE) {
            return Double.valueOf(0D);
        }
        if (returnType == Float.TYPE) {
            return Float.valueOf(0F);
        }
        return null;
    }

    private static final class HasId {
        private final int id;

        private HasId(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }
}
