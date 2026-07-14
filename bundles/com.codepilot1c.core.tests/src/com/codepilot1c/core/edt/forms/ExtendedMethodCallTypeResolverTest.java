package com.codepilot1c.core.edt.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.ExtendedMethodCallType;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Covers EXT-01: {@link ExtendedMethodCallTypeResolver}'s explicit-or-default resolution,
 * case-insensitive matching against both the Java constant name and the EMF literal, and the
 * allowed-values-listing error on an unrecognized {@code call_type}.
 */
public class ExtendedMethodCallTypeResolverTest {

    private final ExtendedMethodCallTypeResolver resolver = new ExtendedMethodCallTypeResolver();

    @Test
    public void resolvesOmittedToBefore() {
        assertSame(ExtendedMethodCallType.BEFORE, resolver.resolve(null));
        assertSame(ExtendedMethodCallType.BEFORE, resolver.resolve("")); //$NON-NLS-1$
        assertSame(ExtendedMethodCallType.BEFORE, resolver.resolve("   ")); //$NON-NLS-1$
    }

    @Test
    public void resolvesValidLiteralsCaseInsensitively() {
        assertSame(ExtendedMethodCallType.BEFORE, resolver.resolve("Before")); //$NON-NLS-1$
        assertSame(ExtendedMethodCallType.BEFORE, resolver.resolve("BEFORE")); //$NON-NLS-1$
        assertSame(ExtendedMethodCallType.AFTER, resolver.resolve("after")); //$NON-NLS-1$
        assertSame(ExtendedMethodCallType.OVERRIDE, resolver.resolve("Override")); //$NON-NLS-1$
        assertSame(ExtendedMethodCallType.CHANGE_AND_VALIDATE, resolver.resolve("ChangeAndValidate")); //$NON-NLS-1$
        assertSame(ExtendedMethodCallType.CHANGE_AND_VALIDATE, resolver.resolve("CHANGE_AND_VALIDATE")); //$NON-NLS-1$
    }

    @Test
    public void invalidCallTypeThrowsWithAllowedValues() {
        try {
            resolver.resolve("Instead"); //$NON-NLS-1$
            fail("expected MetadataOperationException"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.METADATA_NOT_FOUND, e.getCode());
            assertTrue(e.getMessage().contains("Before")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("After")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("Override")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("ChangeAndValidate")); //$NON-NLS-1$
        }
    }
}
