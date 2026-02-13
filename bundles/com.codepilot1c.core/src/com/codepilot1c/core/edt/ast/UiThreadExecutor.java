package com.codepilot1c.core.edt.ast;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * Executes work synchronously on UI thread.
 */
public class UiThreadExecutor {

    public <T> T callSync(Callable<T> callable) {
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) {
            throw new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "UI display is unavailable", false); //$NON-NLS-1$
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        display.syncExec(() -> {
            try {
                result.set(callable.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });

        if (error.get() != null) {
            Throwable thrown = error.get();
            if (thrown instanceof EdtAstException eae) {
                throw eae;
            }
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "UI-thread execution failed: " + thrown.getMessage(), false, thrown); //$NON-NLS-1$
        }
        return result.get();
    }
}
