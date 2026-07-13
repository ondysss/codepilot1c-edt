/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.ui.views.BrowserChatPanel.ToolCallDisplayData;
import com.codepilot1c.ui.views.BrowserChatPanel.ToolCallStatus;

public class BrowserChatPanelToolCallTest {

    private static final long WAIT_TIMEOUT_MS = 10_000L;

    private Display display;
    private Shell shell;
    private BrowserChatPanel panel;

    @Before
    public void setUp() {
        display = Display.getCurrent();
        if (display == null) {
            display = Display.getDefault();
        }
        shell = new Shell(display);
        shell.setSize(900, 700);
        panel = new BrowserChatPanel(shell);
        shell.open();
        waitUntil(() -> panel.isBrowserAvailable(), "BrowserChatPanel did not become ready"); //$NON-NLS-1$
    }

    @After
    public void tearDown() {
        if (shell != null && !shell.isDisposed()) {
            shell.dispose();
        }
        drainEvents();
    }

    @Test
    public void addToolCallCardsRendersVisibleCards() {
        panel.addMessage("AI", "", true); //$NON-NLS-1$ //$NON-NLS-2$

        panel.addToolCallCards(List.of(
                new ToolCallDisplayData("call_1", "glob", "{\"pattern\":\"**/Configuration.mdo\"}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                new ToolCallDisplayData("call_2", "read_file", //$NON-NLS-1$ //$NON-NLS-2$
                        "{\"path\":\"Configuration.mdo\",\"end_line\":20}") //$NON-NLS-1$
        ));

        waitUntil(() -> number("return document.querySelectorAll('.tool-call').length;") == 2, //$NON-NLS-1$
                "tool-call cards were not rendered"); //$NON-NLS-1$

        assertEquals(2, number("return document.querySelectorAll('.tool-call').length;")); //$NON-NLS-1$
        assertTrue(booleanValue(
                "return document.querySelector('.tool-calls-group').classList.contains('expanded');")); //$NON-NLS-1$
        assertEquals(2, number("return document.querySelectorAll('.message.assistant .tool-call').length;")); //$NON-NLS-1$
    }

    @Test
    public void updateToolCallResultShowsSuccessPreview() {
        panel.addToolCallCards(List.of(
                new ToolCallDisplayData("call_1", "glob", "{\"pattern\":\"**/Configuration.mdo\"}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                new ToolCallDisplayData("call_2", "read_file", //$NON-NLS-1$ //$NON-NLS-2$
                        "{\"path\":\"Configuration.mdo\",\"end_line\":20}") //$NON-NLS-1$
        ));
        waitUntil(() -> number("return document.querySelectorAll('.tool-call').length;") == 2, //$NON-NLS-1$
                "tool-call cards were not rendered"); //$NON-NLS-1$

        panel.updateToolCallResult("call_2", ToolCallStatus.SUCCESS, "Готово · 2 символа", "line 1\nline 2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        waitUntil(() -> booleanValue("return document.querySelector('[data-tool-call-id=\"call_2\"] " //$NON-NLS-1$
                        + ".tool-call-status').classList.contains('success');"), //$NON-NLS-1$
                "success status was not applied"); //$NON-NLS-1$

        assertTrue(booleanValue("return document.querySelector('[data-tool-call-id=\"call_2\"] " //$NON-NLS-1$
                + ".tool-call-status').classList.contains('success');")); //$NON-NLS-1$
        assertNotEquals("none", string("return document.querySelector('[data-tool-call-id=\"call_2\"] " //$NON-NLS-1$ //$NON-NLS-2$
                + ".tool-call-result').style.display;")); //$NON-NLS-1$
        assertTrue(booleanValue("return document.querySelector('[data-tool-call-id=\"call_2\"] " //$NON-NLS-1$
                + ".tool-call-result-preview').textContent.indexOf('line 2') >= 0;")); //$NON-NLS-1$
    }

    @Test
    public void typingIndicatorStaysInMessageFlowAfterToolCallCard() {
        panel.addMessage("AI", "", true); //$NON-NLS-1$ //$NON-NLS-2$
        panel.addToolCallCards(List.of(
                new ToolCallDisplayData("call_1", "delegate_to_agent", //$NON-NLS-1$ //$NON-NLS-2$
                        "{\"agentType\":\"dcs\"}") //$NON-NLS-1$
        ));
        panel.showTypingIndicator(true, "Ожидание ответа модели..."); //$NON-NLS-1$

        waitUntil(() -> booleanValue("return document.getElementById('typing-indicator').parentElement.id === 'messages';"), //$NON-NLS-1$
                "typing indicator must be part of the scrollable message flow"); //$NON-NLS-1$
        assertTrue(booleanValue("return document.querySelector('.tool-call').compareDocumentPosition(" //$NON-NLS-1$
                + "document.getElementById('typing-indicator')) & Node.DOCUMENT_POSITION_FOLLOWING;")); //$NON-NLS-1$
    }

    @Test
    public void newMessagesAreInsertedBeforePersistentTypingIndicator() {
        panel.showTypingIndicator(true, "Ожидание ответа модели..."); //$NON-NLS-1$
        panel.addMessage("Вы", "test prompt", false); //$NON-NLS-1$ //$NON-NLS-2$
        panel.addMessage("AI", "", true); //$NON-NLS-1$ //$NON-NLS-2$

        waitUntil(() -> number("return document.querySelectorAll('#messages .message').length;") == 2, //$NON-NLS-1$
                "messages were not rendered"); //$NON-NLS-1$
        assertTrue(booleanValue(
                "var msgs = document.querySelectorAll('#messages .message');" //$NON-NLS-1$
              + "return msgs[msgs.length - 1].compareDocumentPosition(document.getElementById('typing-indicator')) " //$NON-NLS-1$
              + "& Node.DOCUMENT_POSITION_FOLLOWING;")); //$NON-NLS-1$
    }

    @Test
    public void toolExecutionStageDoesNotShowDuplicateTypingIndicator() {
        panel.showTypingIndicator(true, "Ожидание ответа модели..."); //$NON-NLS-1$
        waitUntil(() -> "flex".equals(string("return document.getElementById('typing-indicator').style.display;")), //$NON-NLS-1$ //$NON-NLS-2$
                "typing indicator should be visible while waiting for the model"); //$NON-NLS-1$

        panel.setProcessingStage("Выполнение: delegate_to_agent"); //$NON-NLS-1$

        waitUntil(() -> "none".equals(string("return document.getElementById('typing-indicator').style.display;")), //$NON-NLS-1$ //$NON-NLS-2$
                "tool execution stage should be represented by the tool card, not a duplicate typing indicator"); //$NON-NLS-1$
    }

    @Test
    public void streamingReasoningUpdatePreservesExistingToolCards() {
        panel.addMessage("AI", "Начало", true); //$NON-NLS-1$ //$NON-NLS-2$
        panel.addToolCallCards(List.of(
                new ToolCallDisplayData("call_stream", "read_file", //$NON-NLS-1$ //$NON-NLS-2$
                        "{\"path\":\"src/Configuration/Configuration.mdo\"}") //$NON-NLS-1$
        ));
        waitUntil(() -> number("return document.querySelectorAll('.tool-call').length;") == 1, //$NON-NLS-1$
                "tool-call card was not rendered before streaming update"); //$NON-NLS-1$

        panel.updateLastMessageWithReasoning("Финальный текст", "Проверяю файлы"); //$NON-NLS-1$ //$NON-NLS-2$

        waitUntil(() -> booleanValue("return document.querySelector('.message.assistant .message-text') " //$NON-NLS-1$
                        + "&& document.querySelector('.message.assistant .message-text').textContent.indexOf('Финальный текст') >= 0;"), //$NON-NLS-1$
                "streamed message text was not updated"); //$NON-NLS-1$
        assertEquals(1, number("return document.querySelectorAll('.message.assistant .tool-call').length;")); //$NON-NLS-1$
        assertTrue(booleanValue("return document.querySelector('[data-tool-call-id=\"call_stream\"]') !== null;")); //$NON-NLS-1$
        assertTrue(booleanValue("var tool = document.querySelector('[data-tool-call-id=\"call_stream\"]');" //$NON-NLS-1$
                + "var text = document.querySelector('.message.assistant .message-text');" //$NON-NLS-1$
                + "return !!(tool && text && (tool.compareDocumentPosition(text) " //$NON-NLS-1$
                + "& Node.DOCUMENT_POSITION_FOLLOWING));")); //$NON-NLS-1$
    }

    @Test
    public void duplicateToolCallIdUpdatesExistingCardInsteadOfAppending() {
        panel.addMessage("AI", "", true); //$NON-NLS-1$ //$NON-NLS-2$
        panel.addToolCallCards(List.of(
                new ToolCallDisplayData("call_duplicate", "read_file", //$NON-NLS-1$ //$NON-NLS-2$
                        "{\"path\":\"first.txt\"}") //$NON-NLS-1$
        ));
        waitUntil(() -> number("return document.querySelectorAll('.tool-call').length;") == 1, //$NON-NLS-1$
                "initial tool-call card was not rendered"); //$NON-NLS-1$

        panel.addToolCallCards(List.of(
                new ToolCallDisplayData("call_duplicate", "read_file", //$NON-NLS-1$ //$NON-NLS-2$
                        "{\"path\":\"second.txt\"}") //$NON-NLS-1$
        ));

        drainEvents();
        assertEquals(1, number("return document.querySelectorAll('[data-tool-call-id=\"call_duplicate\"]').length;")); //$NON-NLS-1$
    }

    @Test
    public void renderAllMessagesKeepsToolCardsWithOriginalAssistantTurn() {
        panel.addMessage("AI", "Первый ответ", true); //$NON-NLS-1$ //$NON-NLS-2$
        panel.addToolCallCards(List.of(
                new ToolCallDisplayData("call_first_turn", "glob", //$NON-NLS-1$ //$NON-NLS-2$
                        "{\"pattern\":\"**/*.java\"}") //$NON-NLS-1$
        ));
        waitUntil(() -> number("return document.querySelectorAll('.tool-call').length;") == 1, //$NON-NLS-1$
                "tool-call card was not rendered on first turn"); //$NON-NLS-1$

        panel.addMessage("AI", "Второй ответ", true); //$NON-NLS-1$ //$NON-NLS-2$
        panel.renderAllMessages();
        waitUntil(() -> number("return document.querySelectorAll('.message.assistant').length;") == 2, //$NON-NLS-1$
                "assistant messages were not re-rendered"); //$NON-NLS-1$

        assertEquals(1, number("return document.querySelectorAll('.message.assistant')[0].querySelectorAll('.tool-call').length;")); //$NON-NLS-1$
        assertEquals(0, number("return document.querySelectorAll('.message.assistant')[1].querySelectorAll('.tool-call').length;")); //$NON-NLS-1$
    }

    @Test
    public void finalAssistantMessageStaysAfterToolOnlyTurn() {
        panel.addMessage("AI", "", true); //$NON-NLS-1$ //$NON-NLS-2$
        panel.removeLastMessageIfEmptyAssistant();
        panel.addToolCallCards(List.of(
                new ToolCallDisplayData("call_tool_only", "edit_file", //$NON-NLS-1$ //$NON-NLS-2$
                        "{\"path\":\"plan.md\"}") //$NON-NLS-1$
        ));
        panel.addMessage("AI", "Финальный ответ", true); //$NON-NLS-1$ //$NON-NLS-2$

        waitUntil(() -> number("return document.querySelectorAll('#messages .message.assistant').length;") == 2, //$NON-NLS-1$
                "assistant messages were not rendered"); //$NON-NLS-1$
        waitUntil(() -> number("return document.querySelectorAll('#messages .tool-call').length;") == 1, //$NON-NLS-1$
                "tool-call card was not rendered"); //$NON-NLS-1$

        assertTrue(booleanValue(
                "var assistants = document.querySelectorAll('#messages .message.assistant');" //$NON-NLS-1$
              + "var first = assistants[0];" //$NON-NLS-1$
              + "var second = assistants[1];" //$NON-NLS-1$
              + "var tool = document.querySelector('[data-tool-call-id=\"call_tool_only\"]');" //$NON-NLS-1$
              + "return !!(first && second && tool " //$NON-NLS-1$
              + "&& first.contains(tool) " //$NON-NLS-1$
              + "&& !second.contains(tool) " //$NON-NLS-1$
              + "&& (tool.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING));")); //$NON-NLS-1$
    }

    private int number(String script) {
        Object value = panel.evaluateScript(script);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return -1;
    }

    private boolean booleanValue(String script) {
        Object value = panel.evaluateScript(script);
        return Boolean.TRUE.equals(value);
    }

    private String string(String script) {
        Object value = panel.evaluateScript(script);
        return value != null ? value.toString() : ""; //$NON-NLS-1$
    }

    private void waitUntil(BooleanSupplier condition, String failureMessage) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            drainEvents();
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failureMessage, e);
            }
        }
        throw new AssertionError(failureMessage);
    }

    private void drainEvents() {
        while (display != null && !display.isDisposed() && display.readAndDispatch()) {
            // Drain SWT queue so Browser progress and JavaScript updates settle.
        }
    }
}
