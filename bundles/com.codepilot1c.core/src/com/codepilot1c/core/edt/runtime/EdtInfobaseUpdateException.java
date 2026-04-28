package com.codepilot1c.core.edt.runtime;

/**
 * Structured failure raised while EDT updates an infobase.
 */
public class EdtInfobaseUpdateException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String provider;
    private final String bindingSource;
    private final String causeClass;

    public EdtInfobaseUpdateException(String message, Throwable cause, String provider,
            String bindingSource, String causeClass) {
        super(message, cause);
        this.provider = provider;
        this.bindingSource = bindingSource;
        this.causeClass = causeClass;
    }

    public String getProvider() {
        return provider;
    }

    public String getBindingSource() {
        return bindingSource;
    }

    public String getCauseClass() {
        return causeClass;
    }
}
