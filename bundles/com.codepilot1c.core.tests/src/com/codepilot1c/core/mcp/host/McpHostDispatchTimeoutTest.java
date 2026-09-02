package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Сколько MCP-хост ждёт инструмент, прежде чем бросить диспетчеризацию.
 *
 * <p>До фикса ожидание было плоским: 120 секунд на всё, кроме одного захардкоженного имени
 * {@code qa_run}. Потолок при этом стоял НИЖЕ собственного дефолта долгих инструментов —
 * {@code run_yaxunit_tests} и {@code run_bsl_snippet} объявляют {@code timeout_s} со
 * значением по умолчанию 300 секунд. То есть вызывающий, попросивший 1800, получал 120, а
 * вызывающий, не попросивший ничего, получал меньше документированного дефолта: параметр был
 * объявлен в схеме инструмента и молча выброшен на этом слое.</p>
 *
 * <p>Симптом, ради которого тест написан: любой прогон длиннее двух минут отвечал
 * {@code TimeoutException}, хотя процесс продолжал работать и дописывал отчёт. Это читается
 * как «прогон не пошёл» и провоцирует повтор, а повтор поднимает ВТОРОЙ тест-клиент 1С на
 * той же базе — два прогона засевают одни фикстуры вслепую.</p>
 *
 * <p>Каждое «обязан дождаться» здесь спарено с «обязан не превысить»: правило, которое умеет
 * только ждать дольше, неотличимо от снятого потолка.</p>
 */
public class McpHostDispatchTimeoutTest {

    private static Map<String, Object> сТаймаутом(Object значение) {
        Map<String, Object> аргументы = new HashMap<>();
        аргументы.put("timeout_s", значение); //$NON-NLS-1$
        return аргументы;
    }

    // ---------- ожидание следует за просьбой вызывающего ----------

    /** Заявленный симптом: попросили 1800 секунд, получили обрыв на 120. */
    @Test
    public void ожиданиеСледуетЗаПросьбойВызывающего() {
        assertTrue("прогон, попросивший 1800 с, не должен бросаться через 120", //$NON-NLS-1$
                McpHostRequestRouter.resolveDispatchTimeoutSeconds(
                        "run_yaxunit_tests", сТаймаутом(Integer.valueOf(1800))) >= 1800); //$NON-NLS-1$
    }

    /**
     * Самая острая форма дефекта: потолок хоста стоял ниже дефолта, объявленного самим
     * инструментом, поэтому недостижим был даже документированный 300-секундный дефолт.
     */
    @Test
    public void дефолтИнструментаДостижим() {
        assertTrue("run_bsl_snippet объявляет timeout_s по умолчанию 300 — хост обязан его дать", //$NON-NLS-1$
                McpHostRequestRouter.resolveDispatchTimeoutSeconds(
                        "run_bsl_snippet", сТаймаутом(Integer.valueOf(300))) >= 300); //$NON-NLS-1$
    }

    /**
     * Инструмент держит {@code timeout_s} сам и превращает его в структурный ответ с именем
     * каталога прогона и лога. Одинаковые сроки у хоста и инструмента дают гонку, и когда
     * выигрывает хост, вызывающий получает голый {@code TimeoutException} вместо этого ответа.
     */
    @Test
    public void хостПереживаетСобственныйТаймаутИнструмента() {
        assertTrue("хост обязан ждать строго дольше инструмента, а не вровень с ним", //$NON-NLS-1$
                McpHostRequestRouter.resolveDispatchTimeoutSeconds(
                        "run_yaxunit_tests", сТаймаутом(Integer.valueOf(600))) > 600); //$NON-NLS-1$
    }

    // ---------- прежнее поведение сохранено ----------

    /** Инструмент таймаута не объявил — потолок прежний, молчаливого безлимита не появилось. */
    @Test
    public void безПросьбыПотолокПрежний() {
        assertEquals(120, McpHostRequestRouter.resolveDispatchTimeoutSeconds(
                "edt_metadata_details", null)); //$NON-NLS-1$
        assertEquals(120, McpHostRequestRouter.resolveDispatchTimeoutSeconds(
                "edt_metadata_details", new HashMap<String, Object>())); //$NON-NLS-1$
    }

    /** {@code qa_run} опирался на свой захардкоженный потолок до того, как timeout_s начал работать. */
    @Test
    public void потолокQaRunСохранён() {
        assertEquals(3600, McpHostRequestRouter.resolveDispatchTimeoutSeconds(
                "qa_run", null)); //$NON-NLS-1$
    }

    // ---------- границы ----------

    /** Случайный аргумент не должен занять слот диспетчеризации на сутки. */
    @Test
    public void нелепаяПросьбаОбрезается() {
        assertTrue("нелепый timeout_s обязан обрезаться", //$NON-NLS-1$
                McpHostRequestRouter.resolveDispatchTimeoutSeconds(
                        "run_yaxunit_tests", сТаймаутом(Long.valueOf(999999999L))) <= 7200); //$NON-NLS-1$
    }

    /**
     * Проверка значения принадлежит инструменту: он отвечает {@code INVALID_ARGUMENT} с
     * названием параметра. Отбить здесь значило бы подменить это сообщение ошибкой
     * маршрутизации о параметре, обработки которого вызывающий у хоста не видит.
     */
    @Test
    public void кривойТаймаутОтдаётсяИнструменту() {
        assertEquals(120, McpHostRequestRouter.resolveDispatchTimeoutSeconds(
                "run_yaxunit_tests", сТаймаутом("много"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(120, McpHostRequestRouter.resolveDispatchTimeoutSeconds(
                "run_yaxunit_tests", сТаймаутом(Integer.valueOf(-5)))); //$NON-NLS-1$
        assertEquals(120, McpHostRequestRouter.resolveDispatchTimeoutSeconds(
                "run_yaxunit_tests", сТаймаутом(Integer.valueOf(0)))); //$NON-NLS-1$
    }
}
