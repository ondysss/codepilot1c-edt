package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

@SuppressWarnings("nls")
public class EditFileToolSafetyTest {

    @Test
    public void replacementIsUnsafeWhenNewTextWouldRemoveRequiredCallArguments() {
        String oldSlice = ""
                + "\tСкладыСервер.ЗапланироватьПоступлениеТоваров(\n"
                + "\t\tЗапрос,\n"
                + "\t\tТекстыЗапроса,\n"
                + "\t\tРегистры,\n"
                + "\t\tТекстЗапросаДокумента);";
        String newText = ""
                + "\tСкладыСервер.ЗапланироватьПоступлениеТоваров(\n"
                + "\t\tРегистры,\n"
                + "\t\tТекстЗапросаДокумента);";

        assertFalse(EditFileTool.isReplacementShapeSafeForTest(oldSlice, newText));
    }

    @Test
    public void replacementIsUnsafeWhenMatchedSliceContainsDuplicatedCallOnOneLine() {
        String oldSlice = "\tСкладыСервер.ЗапланироватьОтгрузкуТоваров(\t"
                + "СкладыСервер.ЗапланироватьОтгрузкуТоваров(";
        String newText = "\tСкладыСервер.ЗапланироватьОтгрузкуТоваров(";

        assertFalse(EditFileTool.isReplacementShapeSafeForTest(oldSlice, newText));
    }

    @Test
    public void replacementIsSafeWhenLineShapeIsStable() {
        String oldSlice = "\tТекстЗапроса = СтрЗаменить(ТекстЗапроса, \"A\", \"B\");";
        String newText = "\tТекстЗапроса = СтрЗаменить(ТекстЗапроса, \"A\", \"B, C\");";

        assertTrue(EditFileTool.isReplacementShapeSafeForTest(oldSlice, newText));
    }
}
