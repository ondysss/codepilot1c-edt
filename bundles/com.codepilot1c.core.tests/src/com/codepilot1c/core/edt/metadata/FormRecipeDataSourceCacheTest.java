package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.dt.form.model.util.FormDataSourceInfoCache;

public class FormRecipeDataSourceCacheTest {
    private final FormDataSourceInfoCache cache = FormDataSourceInfoCache.getInstance();

    @Test
    public void invalidatesWarmDataSourcesOnlyInSelectedProject() {
        IBmEngine selected = engine();
        IBmEngine other = engine();
        cache.putValue(1L, "old-root", "PIC", selected);
        cache.putValue(1L, "old-element", FormDataSourceInfoCache.EDSI_REGION, selected);
        cache.putValue(1L, "other-root", "PIC", other);
        try (FormRecipeDataSourceCache scope = new FormRecipeDataSourceCache(selected)) {
            assertNull(cache.getValue(1L, "PIC", selected));
            assertNull(cache.getValue(1L, FormDataSourceInfoCache.EDSI_REGION, selected));
            assertEquals("other-root", cache.getValue(1L, "PIC", other));
        } finally {
            cache.evictAllValues(other);
        }
    }

    @Test
    public void refreshAfterAttributeEditsForcesLayoutToResolveNewDescription() {
        IBmEngine selected = engine();
        try (FormRecipeDataSourceCache scope = new FormRecipeDataSourceCache(selected)) {
            cache.putValue(2L, "description-read-during-attribute-edit", "PIC", selected);
            scope.refresh();
            assertNull(cache.getValue(2L, "PIC", selected));
            cache.putValue(2L, "description-with-new-attribute", "PIC", selected);
            assertEquals("description-with-new-attribute", cache.getValue(2L, "PIC", selected));
        }
    }

    @Test
    public void successfulOperationDoesNotLeaveTransactionDescriptionsCached() {
        IBmEngine selected = engine();
        try (FormRecipeDataSourceCache scope = new FormRecipeDataSourceCache(selected)) {
            cache.putValue(3L, "tentative-success", "PIC", selected);
        }
        assertNull(cache.getValue(3L, "PIC", selected));
    }

    @Test
    public void failedOperationDoesNotLeakRolledBackAttributeDescriptions() {
        IBmEngine selected = engine();
        try {
            try (FormRecipeDataSourceCache scope = new FormRecipeDataSourceCache(selected)) {
                cache.putValue(4L, "attribute-that-will-be-rolled-back", "PIC", selected);
                throw new IllegalArgumentException("invalid later layout operation");
            }
        } catch (IllegalArgumentException expected) {
            assertEquals("invalid later layout operation", expected.getMessage());
        }
        assertNull(cache.getValue(4L, "PIC", selected));
    }

    @Test
    public void requestsWithoutAttributesPreserveExistingCache() {
        IBmEngine selected = engine();
        cache.putValue(5L, "existing", "PIC", selected);
        try (FormRecipeDataSourceCache scope = FormRecipeDataSourceCache.open(null, false)) {
            scope.refresh();
            assertEquals("existing", cache.getValue(5L, "PIC", selected));
        } finally {
            cache.evictAllValues(selected);
        }
    }

    @Test
    public void standaloneFormWithoutBmEngineIsANoOp() {
        try (FormRecipeDataSourceCache scope = FormRecipeDataSourceCache.open(null, true)) {
            scope.refresh();
        }
    }

    private static IBmEngine engine() {
        return (IBmEngine) Proxy.newProxyInstance(IBmEngine.class.getClassLoader(),
                new Class<?>[] { IBmEngine.class }, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == arguments[0];
                        case "toString": return "FormRecipeCacheTestEngine";
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
