/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.PathMatcher;
import org.junit.Test;

/**
 * Regressions of the file filter of {@code grep}.
 *
 * <p>Both cases are field-measured, not imagined. A bare search over an EDT project answered
 * "0 matches" for a name that a role rights file did contain, because {@code .rights} was missing
 * from the default extension set — the miss was read as "no references" and three constants were
 * deleted as unused. And an ordinary brace glob blew up with {@code PatternSyntaxException} because
 * the pattern was converted to a regex by hand.</p>
 */
public class GrepToolFilePatternTest {

    @Test
    public void defaultSetCoversEdtDescriptorsIncludingRoleRights() {
        PathMatcher none = GrepTool.compileFilePattern(null);

        for (String name : new String[] {"Module.bsl", "Catalog.mdo", "Form.form", "Rights.rights",
                                         "CommandInterface.cmi", "Template.dcs", "Configuration.xml"}) {
            assertTrue("default grep must look inside " + name,
                    GrepTool.matchesFilePattern(name, none));
        }
        assertFalse("binary picture is not a text source",
                GrepTool.matchesFilePattern("Picture.png", none));
    }

    @Test
    public void bracePatternMatchesInsteadOfThrowing() {
        PathMatcher matcher = GrepTool.compileFilePattern("*.{form,mdo}");

        assertTrue(GrepTool.matchesFilePattern("Catalog.mdo", matcher));
        assertTrue(GrepTool.matchesFilePattern("Form.form", matcher));
        assertFalse(GrepTool.matchesFilePattern("Module.bsl", matcher));
    }

    @Test
    public void plainGlobStillWorks() {
        PathMatcher matcher = GrepTool.compileFilePattern("*.bsl");

        assertTrue(GrepTool.matchesFilePattern("Module.bsl", matcher));
        assertFalse(GrepTool.matchesFilePattern("Catalog.mdo", matcher));
        assertFalse("a glob must not match a name that merely contains the extension",
                GrepTool.matchesFilePattern("Module.bsl.bak", matcher));
    }

    @Test
    public void brokenPatternIsRejectedWithItsText() {
        try {
            GrepTool.compileFilePattern("*.{form,mdo");
            fail("an unclosed brace group must be rejected, not silently accepted");
        } catch (IllegalArgumentException e) {
            assertTrue("the message must carry the offending pattern",
                    e.getMessage() != null && e.getMessage().contains("*.{form,mdo"));
        }
    }
}
