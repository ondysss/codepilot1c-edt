package com.codepilot1c.core.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Content written over MCP carries bare LF, so a write into a CRLF file has to put the file's own
 * separator back: otherwise every line of it changes, and a repository with
 * {@code core.safecrlf=true} refuses the commit.
 */
public class LineSeparatorsTest {

    @Test
    public void detectsSeparatorOfExistingContent() {
        assertEquals("\r\n", LineSeparators.detect("Процедура А()\r\nКонецПроцедуры\r\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("\n", LineSeparators.detect("Процедура А()\nКонецПроцедуры\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("\r", LineSeparators.detect("Процедура А()\rКонецПроцедуры")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void takesTheSeparatorOfTheMajorityOfLines() {
        // Модуль CRLF, куда вставили фрагмент с LF: единственный LF в начале не должен
        // переворачивать весь файл при следующей записи.
        assertEquals("\r\n", LineSeparators.detect("// вставка\nПроцедура А()\r\nКонецПроцедуры\r\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("\n", LineSeparators.detect("// вставка\r\nПроцедура А()\nКонецПроцедуры\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void reportsNoSeparatorWhenContentHasNoLineBreak() {
        assertNull(LineSeparators.detect(null));
        assertNull(LineSeparators.detect("")); //$NON-NLS-1$
        assertNull(LineSeparators.detect("одна строка без переноса")); //$NON-NLS-1$
    }

    @Test
    public void rewritesEveryLineBreakAsTheTargetSeparator() {
        assertEquals("а\r\nб\r\nв", LineSeparators.normalize("а\nб\r\nв", "\r\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("а\nб\nв", LineSeparators.normalize("а\r\nб\rв", "\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void keepsTextWhenThereIsNothingToRewrite() {
        assertNull(LineSeparators.normalize(null, "\r\n")); //$NON-NLS-1$
        assertEquals("", LineSeparators.normalize("", "\r\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("а\nб", LineSeparators.normalize("а\nб", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void writingLfContentIntoCrlfFileKeepsTheFileCrlf() {
        // Ровно то, ради чего правка: содержимое пришло с голым LF, файл остаётся CRLF.
        String existing = "Процедура А()\r\nКонецПроцедуры\r\n"; //$NON-NLS-1$
        String incoming = "Процедура А()\nВыполнить();\nКонецПроцедуры\n"; //$NON-NLS-1$
        String written = LineSeparators.normalize(incoming, LineSeparators.detect(existing));
        assertEquals("Процедура А()\r\nВыполнить();\r\nКонецПроцедуры\r\n", written); //$NON-NLS-1$
    }

    @Test
    public void fallsBackToThePreferenceOnlyWhenContentHasNoSeparator() {
        assertEquals("\r\n", LineSeparators.of("а\r\nб", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("\n", LineSeparators.of("а\nб", null)); //$NON-NLS-1$ //$NON-NLS-2$
        // Без платформы преференса нет, и остаётся перенос самой машины.
        assertTrue(List.of("\r\n", "\n", "\r").contains(LineSeparators.of("без переносов", null))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(List.of("\r\n", "\n", "\r").contains(LineSeparators.of("", null))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
