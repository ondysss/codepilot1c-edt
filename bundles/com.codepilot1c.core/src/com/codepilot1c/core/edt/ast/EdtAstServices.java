package com.codepilot1c.core.edt.ast;

/**
 * Singleton holder for EDT AST service facade.
 */
public final class EdtAstServices {

    private static volatile IEdtAstService instance;

    private EdtAstServices() {
    }

    public static IEdtAstService getInstance() {
        IEdtAstService local = instance;
        if (local == null) {
            synchronized (EdtAstServices.class) {
                local = instance;
                if (local == null) {
                    local = new EdtAstService(new EdtServiceGateway());
                    instance = local;
                }
            }
        }
        return local;
    }
}
