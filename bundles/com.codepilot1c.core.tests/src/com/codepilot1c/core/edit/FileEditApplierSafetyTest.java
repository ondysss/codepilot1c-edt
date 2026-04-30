package com.codepilot1c.core.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.edit.FileEditApplier.ApplyResult;
import com.codepilot1c.core.edit.FileEditApplier.Hunk;
import com.codepilot1c.core.edit.FileEditApplier.HunkStatus;

@SuppressWarnings("nls")
public class FileEditApplierSafetyTest {

    @Test
    public void fuzzySearchReplaceHunkIsRejectedWhenReplacementRemovesRequiredCallArguments() {
        String beforeContent = ""
                + "\tСкладыСервер.ЗапланироватьПоступлениеТоваров(\n"
                + "\t\tЗапрос, \n"
                + "\t\tТекстыЗапроса,\n"
                + "\t\tРегистры,\n"
                + "\t\tТекстЗапросаДокумента);";
        String searchText = ""
                + "\tСкладыСервер.ЗапланироватьПоступлениеТоваров(\n"
                + "\t\tЗапрос,\n"
                + "\t\tТекстыЗапроса,\n"
                + "\t\tРегистры,\n"
                + "\t\tТекстЗапросаДокумента);";
        String replaceText = ""
                + "\tСкладыСервер.ЗапланироватьПоступлениеТоваров(\n"
                + "\t\tРегистры,\n"
                + "\t\tТекстЗапросаДокумента);";

        ApplyResult result = new FileEditApplier().apply(beforeContent,
                List.of(new EditBlock(searchText, replaceText)));

        assertFalse(result.allSuccessful());
        assertEquals(beforeContent, result.afterContent());
        assertTrue(result.getAppliedHunks().isEmpty());
        assertEquals(1, result.getFailedHunks().size());
        Hunk failedHunk = result.getFailedHunks().get(0);
        assertEquals(HunkStatus.FAILED, failedHunk.status());
        assertTrue(failedHunk.message().contains("Unsafe fuzzy edit"));
    }

    @Test
    public void exactSearchReplaceHunkKeepsExistingBehavior() {
        String beforeContent = ""
                + "\tСкладыСервер.ЗапланироватьПоступлениеТоваров(\n"
                + "\t\tЗапрос,\n"
                + "\t\tТекстыЗапроса,\n"
                + "\t\tРегистры,\n"
                + "\t\tТекстЗапросаДокумента);";
        String replaceText = ""
                + "\tСкладыСервер.ЗапланироватьПоступлениеТоваров(\n"
                + "\t\tРегистры,\n"
                + "\t\tТекстЗапросаДокумента);";

        ApplyResult result = new FileEditApplier().apply(beforeContent,
                List.of(new EditBlock(beforeContent, replaceText)));

        assertTrue(result.allSuccessful());
        assertEquals(replaceText, result.afterContent());
        assertEquals(1, result.getAppliedHunks().size());
        assertTrue(result.getFailedHunks().isEmpty());
    }

    @Test
    public void fuzzySearchReplaceHunkIsRejectedWhenResultWouldGlueBslProcedureEnd() {
        String beforeContent = ""
                + "Процедура ИзменитьМесяц()\n"
                + "\tОбновитьНаКлиенте(); \n"
                + "КонецПроцедуры";
        String searchText = beforeContent.replace("(); ", "();");
        String replaceText = ""
                + "Процедура ИзменитьМесяц()\n"
                + "\tОбновитьНаКлиенте();\n"
                + "КонецПроцедурыКонецПроцедуры";

        ApplyResult result = new FileEditApplier().apply(beforeContent,
                List.of(new EditBlock(searchText, replaceText)));

        assertFalse(result.allSuccessful());
        assertEquals(beforeContent, result.afterContent());
        assertTrue(result.getAppliedHunks().isEmpty());
        assertEquals(1, result.getFailedHunks().size());
        assertTrue(result.getFailedHunks().get(0).message().contains("glued BSL"));
    }
}
