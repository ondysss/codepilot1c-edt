package com.codepilot1c.core.edt.metadata;

import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.util.FormDataSourceInfoCache;

/**
 * Keeps EDT's derived form data-source descriptions consistent with a recipe.
 * The cache is keyed by BM engine and may still describe the form before the
 * current write transaction. It must also forget tentative descriptions when
 * the operation fails. No workspace files or other project engines are touched.
 */
final class FormRecipeDataSourceCache implements AutoCloseable {
    private final IBmEngine engine;

    static FormRecipeDataSourceCache open(Form form, boolean attributesRequested) {
        IBmEngine target = attributesRequested && form != null
                ? ((IBmObject) form).bmGetEngine() : null;
        return new FormRecipeDataSourceCache(target);
    }

    FormRecipeDataSourceCache(IBmEngine engine) {
        this.engine = engine;
        refresh();
    }

    void refresh() {
        if (engine != null) {
            FormDataSourceInfoCache.getInstance().evictAllValues(engine);
        }
    }

    @Override
    public void close() {
        refresh();
    }
}
