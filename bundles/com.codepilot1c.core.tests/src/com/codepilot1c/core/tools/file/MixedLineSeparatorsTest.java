package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class MixedLineSeparatorsTest {

    @Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
            {"LF", "alpha\nbeta\ngamma\n", "\n"},
            {"CRLF", "alpha\r\nbeta\r\ngamma\r\n", "\r\n"},
            {"CR", "alpha\rbeta\rgamma\r", "\r"},
            {"LF then CRLF", "alpha\nbeta\r\ngamma\n", "\n"},
            {"CRLF then LF", "alpha\r\nbeta\ngamma\r\n", "\r\n"},
            {"leading LF then CRLF", "\nalpha\r\nbeta\n", "\n"},
            {"LF then lone CR", "alpha\nbeta\rgamma\n", "\n"},
            {"empty", "", System.lineSeparator()},
            {"single line", "alpha", System.lineSeparator()}
        });
    }

    private final String input;
    private final String expected;

    public MixedLineSeparatorsTest(String label, String input, String expected) {
        this.input = input;
        this.expected = expected;
    }

    @Test
    public void editFileKeepsFirstLfConvention() throws Exception {
        assertEquals(expected, detect(EditFileTool.class));
    }

    @Test
    public void workspaceCopyKeepsFirstLfConvention() throws Exception {
        assertEquals(expected, detect(WorkspaceCopyTransformSupport.class));
    }

    private String detect(Class<?> owner) throws Exception {
        Method method = owner.getDeclaredMethod("detectLineSeparator", String.class);
        method.setAccessible(true);
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : new EditFileTool();
        return (String) method.invoke(receiver, input);
    }
}
