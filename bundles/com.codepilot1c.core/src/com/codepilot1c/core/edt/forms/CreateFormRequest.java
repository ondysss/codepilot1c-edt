package com.codepilot1c.core.edt.forms;

import com.codepilot1c.core.edt.metadata.MetadataNameValidator;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Request for creating a managed form under an existing metadata owner.
 */
public record CreateFormRequest(
        String projectName,
        String ownerFqn,
        String name,
        FormUsage usage,
        Boolean managed,
        Boolean setAsDefault,
        String synonym,
        String comment,
        Long waitMs
) {
    private static final long DEFAULT_WAIT_MS = 30_000L;
    private static final long MIN_WAIT_MS = 1_000L;
    private static final long MAX_WAIT_MS = 300_000L;

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (ownerFqn == null || ownerFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_PARENT_NOT_FOUND,
                    "ownerFqn is required", false); //$NON-NLS-1$
        }
        if (!MetadataNameValidator.isValidName(name)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Invalid form name: " + name, false); //$NON-NLS-1$
        }
        if (managed != null && !managed.booleanValue()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_FORM_USAGE,
                    "Only managed forms are supported in MVP", false); //$NON-NLS-1$
        }
        long effectiveWait = effectiveWaitMs();
        if (effectiveWait < MIN_WAIT_MS || effectiveWait > MAX_WAIT_MS) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "waitMs must be between " + MIN_WAIT_MS + " and " + MAX_WAIT_MS, false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    public FormUsage effectiveUsage() {
        return usage == null ? FormUsage.AUXILIARY : usage;
    }

    public boolean managedEnabled() {
        return managed == null || managed.booleanValue();
    }

    public boolean shouldSetAsDefault() {
        if (effectiveUsage() == FormUsage.AUXILIARY) {
            return false;
        }
        return setAsDefault == null || setAsDefault.booleanValue();
    }

    public long effectiveWaitMs() {
        return waitMs == null ? DEFAULT_WAIT_MS : waitMs.longValue();
    }
}
