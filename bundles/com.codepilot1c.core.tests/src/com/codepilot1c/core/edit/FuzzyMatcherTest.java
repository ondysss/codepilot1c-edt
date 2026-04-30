package com.codepilot1c.core.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

@SuppressWarnings("nls")
public class FuzzyMatcherTest {

    @Test
    public void normalizedWhitespaceFindsDuplicatedBslCallForSafetyLayer() {
        String document = ""
                + "\t\t|\tИ Товары.СтатусУказанияСерий = 10\";\n"
                + "#Вставка\n"
                + "\tТекстЗапросаДокумента = СтрЗаменить(ТекстЗапросаДокумента, \"needle\",\n"
                + "\t\"replacement\");\n"
                + "#КонецВставки\t\n"
                + "\t\n"
                + "\tСкладыСервер.ЗапланироватьОтгрузкуТоваров(\tСкладыСервер.ЗапланироватьОтгрузкуТоваров(\n"
                + "\t\tЗапрос,\n"
                + "\t\tТекстыЗапроса,\n"
                + "\t\tРегистры,\n"
                + "\t\tТекстЗапросаДокумента);\n";

        String oldText = ""
                + "\t\t|\tИ Товары.СтатусУказанияСерий = 10\";\n"
                + "#Вставка\n"
                + "\tТекстЗапросаДокумента = СтрЗаменить(ТекстЗапросаДокумента, \"needle\",\n"
                + "\t\"replacement\");\n"
                + "#КонецВставки\n"
                + "\t\n"
                + "\tСкладыСервер.ЗапланироватьОтгрузкуТоваров(\tСкладыСервер.ЗапланироватьОтгрузкуТоваров(\n"
                + "\t\tЗапрос,\n"
                + "\t\tТекстыЗапроса,\n"
                + "\t\tРегистры,\n"
                + "\t\tТекстЗапросаДокумента);";

        MatchResult result = new FuzzyMatcher().findMatch(oldText, document);

        assertTrue(result.isSuccess());
        assertEquals(MatchStrategy.NORMALIZE_WHITESPACE, result.getStrategy());
    }

    @Test
    public void exactMatchStillSucceedsForPreciseBslBlock() {
        String block = ""
                + "\tТекстЗапроса = СтрЗаменить(ТекстЗапроса, \"needle\",\n"
                + "\t\"replacement\");\n"
                + "\tПараметры = Новый Структура();";

        MatchResult result = new FuzzyMatcher().findMatch(block, "До\n" + block + "\nПосле");

        assertTrue(result.isSuccess());
        assertEquals(MatchStrategy.EXACT, result.getStrategy());
    }

    @Test
    public void normalizedWhitespaceMatchKeepsOriginalBoundariesWithCrLfAndTrailingSpaces() {
        String document = ""
                + "Процедура ИзменитьМесяц()\r\n"
                + "\tОбновитьНаКлиенте();   \r\n"
                + "КонецПроцедуры\r\n"
                + "\r\n"
                + "Процедура Следующая()\r\n"
                + "КонецПроцедуры\r\n";
        String oldText = ""
                + "Процедура ИзменитьМесяц()\n"
                + "\tОбновитьНаКлиенте();\n"
                + "КонецПроцедуры";

        MatchResult result = new FuzzyMatcher().findMatch(oldText, document);

        assertTrue(result.isSuccess());
        assertEquals(MatchStrategy.NORMALIZE_WHITESPACE, result.getStrategy());
        assertEquals("Процедура ИзменитьМесяц()\r\n"
                + "\tОбновитьНаКлиенте();   \r\n"
                + "КонецПроцедуры", result.getLocation().orElseThrow().getMatchedText());
    }
}
