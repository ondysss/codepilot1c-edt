package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.Test;

/** Checks the actual type normalizer for inline literals wrapped in qualifier maps. */
public class EdtMetadataInlineTypeQualifierTest {

    @Test
    public void plainInlineNumberStillWorks() throws Exception {
        Object spec = normalize("Number(5,0)"); //$NON-NLS-1$
        assertEquals("Number", field(spec, "typeQuery")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(5, field(spec, "numberPrecision")); //$NON-NLS-1$
        assertEquals(0, field(spec, "numberScale")); //$NON-NLS-1$
    }

    @Test
    public void mappedInlineNumberKeepsScaleAndNonNegative() throws Exception {
        Object spec = normalize(Map.of("type", "Number(5,0)", //$NON-NLS-1$ //$NON-NLS-2$
                "numberQualifiers", Map.of("nonNegative", true))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Number", field(spec, "typeQuery")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(5, field(spec, "numberPrecision")); //$NON-NLS-1$
        assertEquals(0, field(spec, "numberScale")); //$NON-NLS-1$
        assertEquals(true, field(spec, "numberNonNegative")); //$NON-NLS-1$
    }

    @Test
    public void nestedMapKeepsInlineNumberPrecisionAndScale() throws Exception {
        Object spec = normalize(Map.of("type", Map.of("type", "Number(12,3)"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "numberQualifiers", Map.of("nonNegative", true))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Number", field(spec, "typeQuery")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(12, field(spec, "numberPrecision")); //$NON-NLS-1$
        assertEquals(3, field(spec, "numberScale")); //$NON-NLS-1$
        assertEquals(true, field(spec, "numberNonNegative")); //$NON-NLS-1$
    }

    @Test
    public void explicitNumberQualifiersOverrideInlineValues() throws Exception {
        Object spec = normalize(Map.of("type", "Number(5,0)", //$NON-NLS-1$ //$NON-NLS-2$
                "numberQualifiers", Map.of("precision", 10, "scale", 2))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Number", field(spec, "typeQuery")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(10, field(spec, "numberPrecision")); //$NON-NLS-1$
        assertEquals(2, field(spec, "numberScale")); //$NON-NLS-1$
    }

    @Test
    public void mappedUnlimitedStringKeepsZeroLength() throws Exception {
        Object spec = normalize(Map.of("type", "String(0)", //$NON-NLS-1$ //$NON-NLS-2$
                "stringQualifiers", Map.of("fixed", false))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("String", field(spec, "typeQuery")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, field(spec, "stringLength")); //$NON-NLS-1$
        assertEquals(false, field(spec, "stringFixed")); //$NON-NLS-1$
    }

    @Test
    public void explicitStringLengthOverridesInlineValue() throws Exception {
        Object spec = normalize(Map.of("type", "String(50)", //$NON-NLS-1$ //$NON-NLS-2$
                "stringQualifiers", Map.of("length", 10, "fixed", true))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("String", field(spec, "typeQuery")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(10, field(spec, "stringLength")); //$NON-NLS-1$
        assertEquals(true, field(spec, "stringFixed")); //$NON-NLS-1$
    }

    @Test
    public void mappedInlineDateKeepsFractions() throws Exception {
        Object expected = field(normalize("Date(Time)"), "dateFractions"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(expected);
        Object spec = normalize(Map.of("type", "Date(Time)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Date", field(spec, "typeQuery")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(expected, field(spec, "dateFractions")); //$NON-NLS-1$
    }

    @Test
    public void unqualifiedMapRemainsValid() throws Exception {
        Object spec = normalize(Map.of("type", "Number", //$NON-NLS-1$ //$NON-NLS-2$
                "numberQualifiers", Map.of("precision", 10, "scale", 2))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Number", field(spec, "typeQuery")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(10, field(spec, "numberPrecision")); //$NON-NLS-1$
        assertEquals(2, field(spec, "numberScale")); //$NON-NLS-1$
    }

    @Test
    public void metadataReferenceRemainsUnchanged() throws Exception {
        Object spec = normalize(Map.of("type", "CatalogRef.Example")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CatalogRef.Example", field(spec, "typeQuery")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Object normalize(Object value) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("normalizeTypeSpec", Object.class); //$NON-NLS-1$
        method.setAccessible(true);
        return method.invoke(new EdtMetadataService(), value);
    }

    private Object field(Object spec, String name) throws Exception {
        Method accessor = spec.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(spec);
    }
}
