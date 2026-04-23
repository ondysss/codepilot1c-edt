/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmAttachment;
import com.codepilot1c.ui.internal.ToolDisplayNames;
import com.codepilot1c.ui.internal.VibeUiPlugin;
import com.codepilot1c.ui.markdown.FlexmarkParser;
import com.codepilot1c.ui.theme.ThemeManager;

/**
 * Панель чата на основе SWT Browser для рендеринга HTML/CSS.
 *
 * <p>Преимущества перед StyledText:</p>
 * <ul>
 *   <li>Правильное отображение таблиц</li>
 *   <li>Подсветка синтаксиса кода</li>
 *   <li>Современные CSS-стили</li>
 *   <li>Поддержка тёмной/светлой темы</li>
 * </ul>
 */
public class BrowserChatPanel extends Composite {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(BrowserChatPanel.class);

    private static final String CSS_RESOURCE = "/resources/chat.css"; //$NON-NLS-1$
    private static final String JS_RESOURCE = "/resources/chat.js"; //$NON-NLS-1$

    private static String cachedCss;
    private static String cachedJs;

    private final Browser browser;
    private final FlexmarkParser markdownParser;
    private final List<ChatMessageData> messages = new ArrayList<>();

    private BrowserFunction copyFunction;
    private BrowserFunction openUrlFunction;
    private BrowserFunction applyCodeFunction;

    private boolean browserReady = false;
    private ApplyCodeCallback applyCodeCallback;
    private boolean typingIndicatorVisible = false;
    private String typingIndicatorStage;
    private final Map<String, ToolCallDisplayData> activeToolCalls = new HashMap<>();

    /**
     * Callback для применения кода.
     */
    @FunctionalInterface
    public interface ApplyCodeCallback {
        void apply(String code, String language, String filePath);
    }

    /**
     * Статус выполнения tool call.
     */
    public enum ToolCallStatus {
        PENDING("\u23F3", "pending"),     // ⏳
        RUNNING("\uD83D\uDD04", "running"), // 🔄
        SUCCESS("\u2713", "success"),      // ✓
        ERROR("\u2717", "error");          // ✗

        public final String icon;
        public final String cssClass;

        ToolCallStatus(String icon, String cssClass) {
            this.icon = icon;
            this.cssClass = cssClass;
        }
    }

    /**
     * Данные для отображения tool call.
     */
    public static class ToolCallDisplayData {
        private final String id;
        private final String name;
        private final String argsSummary;
        private final String argsJson;
        private ToolCallStatus status;
        private String resultSummary;
        private String resultPreview;

        public ToolCallDisplayData(String id, String name, String argsJson) {
            this.id = id;
            this.name = name;
            this.argsJson = argsJson;
            this.argsSummary = buildArgsSummary(argsJson);
            this.status = ToolCallStatus.PENDING;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getArgsSummary() { return argsSummary; }
        public String getArgsJson() { return argsJson; }
        public ToolCallStatus getStatus() { return status; }
        public String getResultSummary() { return resultSummary; }
        public String getResultPreview() { return resultPreview; }

        public void setStatus(ToolCallStatus status) { this.status = status; }
        public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
        public void setResultPreview(String resultPreview) { this.resultPreview = resultPreview; }

        /**
         * Builds a short summary of key arguments.
         */
        private static String buildArgsSummary(String json) {
            if (json == null || json.isEmpty() || "{}".equals(json)) { //$NON-NLS-1$
                return ""; //$NON-NLS-1$
            }

            try {
                JsonElement element = JsonParser.parseString(json);

                // Guard: only process if it's actually an object
                if (!element.isJsonObject()) {
                    LOG.debug("Tool arguments are not JSON object, skipping summary"); //$NON-NLS-1$
                    return ""; //$NON-NLS-1$
                }

                JsonObject obj = element.getAsJsonObject();
                StringBuilder sb = new StringBuilder();

                // Priority keys to show in summary
                String[] priorityKeys = {"path", "file_path", "query", "pattern", "kind", "name"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

                for (String key : priorityKeys) {
                    if (obj.has(key)) {
                        JsonElement val = obj.get(key);
                        if (val.isJsonPrimitive()) {
                            String value = val.getAsString();
                            // Truncate long values
                            if (value.length() > 40) {
                                value = "..." + value.substring(value.length() - 37); //$NON-NLS-1$
                            }
                            if (sb.length() > 0) {
                                sb.append(", "); //$NON-NLS-1$
                            }
                            sb.append(key).append("=").append(value); //$NON-NLS-1$
                            if (sb.length() > 60) {
                                break;
                            }
                        }
                    }
                }

                return sb.toString();
            } catch (JsonSyntaxException e) {
                LOG.warn("Failed to parse tool arguments: %s", e.getMessage()); //$NON-NLS-1$
                return ""; //$NON-NLS-1$
            }
        }
    }

    /**
     * Данные сообщения чата.
     */
    public static class ChatMessageData {
        public final String sender;
        public final String content;
        public final boolean isAssistant;
        public final boolean isSystem;
        public final String id;
        public final String reasoning;
        public final List<LlmAttachment> attachments;
        public final String modelName;

        public ChatMessageData(String sender, String content, boolean isAssistant, boolean isSystem) {
            this(sender, content, isAssistant, isSystem, null, List.of(), null);
        }

        public ChatMessageData(String sender, String content, boolean isAssistant, boolean isSystem, String reasoning) {
            this(sender, content, isAssistant, isSystem, reasoning, List.of(), null);
        }

        public ChatMessageData(String sender, String content, boolean isAssistant, boolean isSystem, String reasoning,
                List<LlmAttachment> attachments) {
            this(sender, content, isAssistant, isSystem, reasoning, attachments, null);
        }

        public ChatMessageData(String sender, String content, boolean isAssistant, boolean isSystem, String reasoning,
                List<LlmAttachment> attachments, String modelName) {
            this.sender = sender;
            this.content = content;
            this.isAssistant = isAssistant;
            this.isSystem = isSystem;
            this.reasoning = reasoning;
            this.attachments = attachments != null ? List.copyOf(attachments) : List.of();
            this.modelName = modelName;
            this.id = "msg-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 10000); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Создает панель чата.
     *
     * @param parent родительский composite
     */
    public BrowserChatPanel(Composite parent) {
        super(parent, SWT.NONE);
        this.markdownParser = new FlexmarkParser();

        setLayout(new FillLayout());

        // Create browser
        Browser tempBrowser = null;
        try {
            tempBrowser = new Browser(this, SWT.EDGE);
        } catch (SWTError e1) {
            try {
                tempBrowser = new Browser(this, SWT.NONE);
            } catch (SWTError e2) {
                VibeUiPlugin.log(e2);
            }
        }
        this.browser = tempBrowser;

        if (browser != null) {
            setupBrowser();
            initializeHtml();
        }
    }

    /**
     * Настраивает браузер.
     */
    private void setupBrowser() {
        // Disable context menu
        browser.addMenuDetectListener(e -> e.doit = false);

        // Copy to clipboard
        copyFunction = new BrowserFunction(browser, "copyToClipboard") { //$NON-NLS-1$
            @Override
            public Object function(Object[] arguments) {
                if (arguments.length > 0 && arguments[0] instanceof String) {
                    copyToClipboard((String) arguments[0]);
                }
                return null;
            }
        };

        // Open URL in external browser
        openUrlFunction = new BrowserFunction(browser, "openUrl") { //$NON-NLS-1$
            @Override
            public Object function(Object[] arguments) {
                if (arguments.length > 0 && arguments[0] instanceof String) {
                    openExternalUrl((String) arguments[0]);
                }
                return null;
            }
        };

        // Apply code callback
        applyCodeFunction = new BrowserFunction(browser, "applyCode") { //$NON-NLS-1$
            @Override
            public Object function(Object[] arguments) {
                if (applyCodeCallback != null && arguments.length >= 3) {
                    String code = arguments[0] instanceof String ? (String) arguments[0] : ""; //$NON-NLS-1$
                    String language = arguments[1] instanceof String ? (String) arguments[1] : ""; //$NON-NLS-1$
                    String filePath = arguments[2] instanceof String ? (String) arguments[2] : ""; //$NON-NLS-1$
                    Display.getDefault().asyncExec(() -> applyCodeCallback.apply(code, language, filePath));
                }
                return null;
            }
        };

        // Mark browser as ready when loaded
        browser.addProgressListener(new org.eclipse.swt.browser.ProgressAdapter() {
            @Override
            public void completed(org.eclipse.swt.browser.ProgressEvent event) {
                browserReady = true;
                applyTypingIndicatorState();
            }
        });
    }

    /**
     * Инициализирует HTML-документ.
     */
    private void initializeHtml() {
        String html = buildHtmlDocument(""); //$NON-NLS-1$
        browser.setText(html);
    }

    /**
     * Добавляет сообщение в чат.
     *
     * @param sender отправитель
     * @param content содержимое (Markdown)
     * @param isAssistant true если от AI
     */
    public void addMessage(String sender, String content, boolean isAssistant) {
        addMessage(sender, content, isAssistant, false);
    }

    /**
     * Добавляет сообщение в чат.
     *
     * @param sender отправитель
     * @param content содержимое (Markdown)
     * @param isAssistant true если от AI
     * @param isSystem true если системное сообщение
     */
    public void addMessage(String sender, String content, boolean isAssistant, boolean isSystem) {
        addMessage(sender, content, isAssistant, isSystem, List.of(), null);
    }

    public void addMessage(String sender, String content, boolean isAssistant, boolean isSystem,
            List<LlmAttachment> attachments) {
        addMessage(sender, content, isAssistant, isSystem, attachments, null);
    }

    public void addMessage(String sender, String content, boolean isAssistant, boolean isSystem,
            List<LlmAttachment> attachments, String modelName) {
        LOG.debug("addMessage: sender=%s, isAssistant=%b, isSystem=%b, contentLength=%d", //$NON-NLS-1$
                sender, isAssistant, isSystem, content != null ? content.length() : 0);

        ChatMessageData msg = new ChatMessageData(sender, content, isAssistant, isSystem, null, attachments, modelName);
        messages.add(msg);

        LOG.debug("addMessage: browserReady=%b, browser=%s, disposed=%b", //$NON-NLS-1$
                browserReady, browser != null ? "not null" : "null", //$NON-NLS-1$ //$NON-NLS-2$
                browser != null && browser.isDisposed());

        if (browserReady && browser != null && !browser.isDisposed()) {
            // Append message via JavaScript
            String messageHtml = buildMessageHtml(msg);
            LOG.debug("addMessage: messageHtml length=%d", messageHtml.length()); //$NON-NLS-1$

            String escapedHtml = escapeForJs(messageHtml);
            LOG.debug("addMessage: escapedHtml length=%d", escapedHtml.length()); //$NON-NLS-1$

            String jsCode =
                "var container = document.getElementById('messages');" + //$NON-NLS-1$
                "if (container) {" + //$NON-NLS-1$
                "  container.insertAdjacentHTML('beforeend', '" + escapedHtml + "');" + //$NON-NLS-1$ //$NON-NLS-2$
                "  scrollToBottom();" + //$NON-NLS-1$
                "  if (typeof hljs !== 'undefined') { hljs.highlightAll(); }" + //$NON-NLS-1$
                "}" + //$NON-NLS-1$
                "console.log('Message added, container:', container ? 'found' : 'NOT FOUND');"; //$NON-NLS-1$

            boolean success = browser.execute(jsCode);
            LOG.debug("addMessage: browser.execute returned %b", success); //$NON-NLS-1$

            if (!success) {
                LOG.warn("addMessage: browser.execute failed! Falling back to renderAllMessages"); //$NON-NLS-1$
                renderAllMessages();
            }
        } else {
            LOG.debug("addMessage: browser not ready, calling renderAllMessages"); //$NON-NLS-1$
            // Re-render all if browser not ready
            renderAllMessages();
        }
    }

    /**
     * Обновляет последнее сообщение (для streaming).
     *
     * @param content новое содержимое
     */
    public void updateLastMessage(String content) {
        if (messages.isEmpty()) return;

        ChatMessageData lastMsg = messages.get(messages.size() - 1);
        ChatMessageData updated = new ChatMessageData(lastMsg.sender, content, lastMsg.isAssistant, lastMsg.isSystem,
                null, lastMsg.attachments);
        messages.set(messages.size() - 1, updated);

        if (browserReady && browser != null && !browser.isDisposed()) {
            String messageHtml = buildMessageContentHtml(content);
            String escapedHtml = escapeForJs(messageHtml);
            browser.execute(
                "var lastMsg = document.querySelector('.message:last-child .message-content');" + //$NON-NLS-1$
                "if (lastMsg) {" + //$NON-NLS-1$
                "  lastMsg.innerHTML = '" + escapedHtml + "';" + //$NON-NLS-1$ //$NON-NLS-2$
                "  scrollToBottom();" + //$NON-NLS-1$
                "  if (typeof hljs !== 'undefined') { hljs.highlightAll(); }" + //$NON-NLS-1$
                "}" //$NON-NLS-1$
            );
        }
    }

    /**
     * Обновляет последнее сообщение с контентом и ходом рассуждений (для streaming с thinking mode).
     *
     * @param content содержимое ответа
     * @param reasoning ход рассуждений модели
     */
    public void updateLastMessageWithReasoning(String content, String reasoning) {
        if (messages.isEmpty()) return;

        // Update the backing messages list so re-renders preserve content and reasoning
        ChatMessageData lastMsg = messages.get(messages.size() - 1);
        ChatMessageData updated = new ChatMessageData(lastMsg.sender, content, lastMsg.isAssistant, lastMsg.isSystem,
                reasoning, lastMsg.attachments);
        messages.set(messages.size() - 1, updated);

        // Execute browser update only when browser is ready
        if (browserReady && browser != null && !browser.isDisposed()) {
            String reasoningHtml = ""; //$NON-NLS-1$
            if (reasoning != null && !reasoning.isEmpty()) {
                reasoningHtml = buildReasoningBlock(reasoning);
            }

            String contentHtml = content != null ? buildMessageContentHtml(content) : ""; //$NON-NLS-1$

            String script = String.format(
                "updateMessageWithReasoning('%s', '%s')", //$NON-NLS-1$
                escapeForJs(reasoningHtml),
                escapeForJs(contentHtml)
            );
            browser.execute(script);
        }
    }

    /**
     * Строит HTML-блок для хода рассуждений.
     *
     * @param reasoning текст рассуждений
     * @return HTML-блок с collapsible деталями
     */
    private String buildReasoningBlock(String reasoning) {
        String escapedReasoning = escapeHtml(reasoning)
                .replace("\n", "<br>"); //$NON-NLS-1$ //$NON-NLS-2$

        return "<details class=\"reasoning-block\">" + //$NON-NLS-1$
               "<summary>\uD83D\uDCAD Ход рассуждений</summary>" + //$NON-NLS-1$
               "<div class=\"reasoning-content\">" + escapedReasoning + "</div>" + //$NON-NLS-1$ //$NON-NLS-2$
               "</details>"; //$NON-NLS-1$
    }

    /**
     * Показывает индикатор набора.
     *
     * @param show true для показа
     */
    public void showTypingIndicator(boolean show) {
        showTypingIndicator(show, null);
    }

    /**
     * Показывает индикатор набора с указанным текстом этапа.
     *
     * @param show true для показа
     * @param stage текст этапа обработки (null для стандартного текста)
     */
    public void showTypingIndicator(boolean show, String stage) {
        if (browser == null || browser.isDisposed()) return;
        typingIndicatorVisible = show;
        typingIndicatorStage = stage;
        if (!browserReady) {
            return;
        }

        if (show) {
            String stageText = (stage != null && !stage.isEmpty())
                    ? escapeForJs(stage)
                    : "AI обрабатывает запрос"; //$NON-NLS-1$
            browser.execute(
                "var indicator = document.getElementById('typing-indicator');" + //$NON-NLS-1$
                "var stageText = document.getElementById('typing-stage');" + //$NON-NLS-1$
                "if (indicator) {" + //$NON-NLS-1$
                "  indicator.style.display = 'flex';" + //$NON-NLS-1$
                "  if (stageText) stageText.textContent = '" + stageText + "';" + //$NON-NLS-1$ //$NON-NLS-2$
                "}" + //$NON-NLS-1$
                "scrollToBottom();" //$NON-NLS-1$
            );
        } else {
            browser.execute(
                "var indicator = document.getElementById('typing-indicator');" + //$NON-NLS-1$
                "if (indicator) indicator.style.display = 'none';" //$NON-NLS-1$
            );
        }
    }

    /**
     * Обновляет текст этапа обработки без изменения видимости индикатора.
     *
     * @param stage текст этапа обработки
     */
    public void setProcessingStage(String stage) {
        if (browser == null || browser.isDisposed()) return;
        if (stage == null || stage.isEmpty()) return;
        typingIndicatorStage = stage;
        if (!browserReady) {
            return;
        }

        String stageText = escapeForJs(stage);
        browser.execute(
            "var stageText = document.getElementById('typing-stage');" + //$NON-NLS-1$
            "if (stageText) stageText.textContent = '" + stageText + "';" //$NON-NLS-1$ //$NON-NLS-2$
        );
    }

    /**
     * Очищает чат.
     */
    public void clearChat() {
        messages.clear();
        activeToolCalls.clear();
        if (browser != null && !browser.isDisposed()) {
            browser.execute(
                "var container = document.getElementById('messages');" + //$NON-NLS-1$
                "if (container) container.innerHTML = '';" //$NON-NLS-1$
            );
        }
    }

    /**
     * Перерисовывает все сообщения.
     */
    public void renderAllMessages() {
        if (browser == null || browser.isDisposed()) return;

        StringBuilder messagesHtml = new StringBuilder();
        for (ChatMessageData msg : messages) {
            messagesHtml.append(buildMessageHtml(msg));
        }

        String html = buildHtmlDocument(messagesHtml.toString());
        browser.setText(html);
    }

    private void applyTypingIndicatorState() {
        if (browser == null || browser.isDisposed() || !browserReady) return;
        if (typingIndicatorVisible) {
            String stageText = (typingIndicatorStage != null && !typingIndicatorStage.isEmpty())
                    ? escapeForJs(typingIndicatorStage)
                    : "AI обрабатывает запрос"; //$NON-NLS-1$
            browser.execute(
                "var indicator = document.getElementById('typing-indicator');" + //$NON-NLS-1$
                "var stageText = document.getElementById('typing-stage');" + //$NON-NLS-1$
                "if (indicator) {" + //$NON-NLS-1$
                "  indicator.style.display = 'flex';" + //$NON-NLS-1$
                "  if (stageText) stageText.textContent = '" + stageText + "';" + //$NON-NLS-1$ //$NON-NLS-2$
                "}" + //$NON-NLS-1$
                "scrollToBottom();" //$NON-NLS-1$
            );
        } else {
            browser.execute(
                "var indicator = document.getElementById('typing-indicator');" + //$NON-NLS-1$
                "if (indicator) indicator.style.display = 'none';" //$NON-NLS-1$
            );
        }
    }

    /**
     * Обновляет тему.
     *
     * @param isDark true для тёмной темы
     */
    public void updateTheme(boolean isDark) {
        if (browser != null && !browser.isDisposed() && browserReady) {
            String theme = isDark ? "dark" : "light"; //$NON-NLS-1$ //$NON-NLS-2$
            browser.execute("setTheme('" + theme + "');"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Устанавливает callback для применения кода.
     */
    public void setApplyCodeCallback(ApplyCodeCallback callback) {
        this.applyCodeCallback = callback;
    }

    /**
     * Проверяет доступность браузера.
     *
     * <p>Браузер считается доступным только когда SWT Browser создан,
     * не disposed, и загрузка HTML завершена (browserReady == true).</p>
     */
    public boolean isBrowserAvailable() {
        return browser != null && !browser.isDisposed() && browserReady;
    }

    /**
     * Добавляет карточку tool call в UI.
     *
     * @param toolCall данные tool call
     */
    public void addToolCallCard(ToolCallDisplayData toolCall) {
        if (browser == null || browser.isDisposed() || !browserReady) {
            LOG.warn("addToolCallCard: browser not ready"); //$NON-NLS-1$
            return;
        }

        activeToolCalls.put(toolCall.getId(), toolCall);

        String cardHtml = buildToolCallCardHtml(toolCall);
        String escapedHtml = escapeForJs(cardHtml);

        String jsCode =
            "var container = document.getElementById('messages');" + //$NON-NLS-1$
            "if (container) {" + //$NON-NLS-1$
            "  container.insertAdjacentHTML('beforeend', '" + escapedHtml + "');" + //$NON-NLS-1$ //$NON-NLS-2$
            "  scrollToBottom();" + //$NON-NLS-1$
            "}"; //$NON-NLS-1$

        boolean success = browser.execute(jsCode);
        if (!success) {
            LOG.warn("addToolCallCard: browser.execute failed"); //$NON-NLS-1$
        }
    }

    /**
     * Добавляет несколько карточек tool call в UI.
     *
     * @param toolCalls список данных tool call
     */
    public void addToolCallCards(List<ToolCallDisplayData> toolCalls) {
        if (browser == null || browser.isDisposed() || !browserReady) {
            LOG.warn("addToolCallCards: browser not ready"); //$NON-NLS-1$
            return;
        }

        StringBuilder allCardsHtml = new StringBuilder();
        for (ToolCallDisplayData toolCall : toolCalls) {
            activeToolCalls.put(toolCall.getId(), toolCall);
            allCardsHtml.append(buildToolCallCardHtml(toolCall));
        }

        // Wrap in collapsible group when multiple tool calls
        String groupId = "tcg-" + System.currentTimeMillis(); //$NON-NLS-1$
        String wrappedHtml;
        if (toolCalls.size() > 1) {
            wrappedHtml = "<div class=\"tool-calls-group\" id=\"" + groupId + "\">\n" + //$NON-NLS-1$ //$NON-NLS-2$
                "  <div class=\"tool-calls-group-header\" onclick=\"toggleToolCallGroup(this)\">\n" + //$NON-NLS-1$
                "    <span class=\"tool-calls-group-icon\">\uD83D\uDD27</span>\n" + //$NON-NLS-1$
                "    <span class=\"tool-calls-group-label\">\u0418\u0441\u043F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u043D\u043E " + //$NON-NLS-1$
                toolCalls.size() + " \u0438\u043D\u0441\u0442\u0440\u0443\u043C\u0435\u043D\u0442\u043E\u0432</span>\n" + //$NON-NLS-1$
                "    <span class=\"tool-calls-group-status\" data-group-id=\"" + groupId + "\"></span>\n" + //$NON-NLS-1$ //$NON-NLS-2$
                "  </div>\n" + //$NON-NLS-1$
                "  <div class=\"tool-calls-group-body\">\n" + //$NON-NLS-1$
                allCardsHtml.toString() +
                "  </div>\n" + //$NON-NLS-1$
                "</div>\n"; //$NON-NLS-1$
        } else {
            wrappedHtml = allCardsHtml.toString();
        }

        String escapedHtml = escapeForJs(wrappedHtml);

        String jsCode =
            "var container = document.getElementById('messages');" + //$NON-NLS-1$
            "if (container) {" + //$NON-NLS-1$
            "  container.insertAdjacentHTML('beforeend', '" + escapedHtml + "');" + //$NON-NLS-1$ //$NON-NLS-2$
            "  scrollToBottom();" + //$NON-NLS-1$
            "}"; //$NON-NLS-1$

        boolean success = browser.execute(jsCode);
        if (!success) {
            LOG.warn("addToolCallCards: browser.execute failed"); //$NON-NLS-1$
        }
    }

    /**
     * Обновляет карточку tool call с результатом.
     *
     * @param toolCallId ID tool call
     * @param status новый статус
     * @param resultSummary краткое описание результата
     * @param resultPreview первые 200 символов результата
     */
    public void updateToolCallResult(String toolCallId, ToolCallStatus status, String resultSummary, String resultPreview) {
        if (browser == null || browser.isDisposed() || !browserReady) {
            LOG.warn("updateToolCallResult: browser not ready"); //$NON-NLS-1$
            return;
        }

        ToolCallDisplayData toolCall = activeToolCalls.get(toolCallId);
        if (toolCall != null) {
            toolCall.setStatus(status);
            toolCall.setResultSummary(resultSummary);
            toolCall.setResultPreview(resultPreview);
        }

        // Only JS-escape here, HTML escaping is done in JavaScript
        String escapedSummary = escapeForJs(resultSummary != null ? resultSummary : ""); //$NON-NLS-1$
        String escapedPreview = escapeForJs(resultPreview != null ? resultPreview : ""); //$NON-NLS-1$

        String jsCode = String.format(
            "updateToolCallCard('%s', '%s', '%s', '%s', '%s');", //$NON-NLS-1$
            escapeForJs(toolCallId),
            status.cssClass,
            escapeForJs(status.icon),
            escapedSummary,
            escapedPreview
        );

        boolean success = browser.execute(jsCode);
        if (!success) {
            LOG.warn("updateToolCallResult: browser.execute failed for %s", toolCallId); //$NON-NLS-1$
        }
    }

    /**
     * Добавляет блок reasoning между вызовами инструментов.
     *
     * @param reasoning текст рассуждений
     */
    public void addReasoningBlock(String reasoning) {
        if (browser == null || browser.isDisposed() || !browserReady) {
            return;
        }
        if (reasoning == null || reasoning.isEmpty()) {
            return;
        }

        String reasoningHtml = buildReasoningBlock(reasoning);
        String escapedHtml = escapeForJs(reasoningHtml);

        String jsCode =
            "var container = document.getElementById('messages');" + //$NON-NLS-1$
            "if (container) {" + //$NON-NLS-1$
            "  container.insertAdjacentHTML('beforeend', '" + escapedHtml + "');" + //$NON-NLS-1$ //$NON-NLS-2$
            "  scrollToBottom();" + //$NON-NLS-1$
            "}"; //$NON-NLS-1$

        browser.execute(jsCode);
    }

    /**
     * Строит HTML для карточки tool call.
     */
    private String buildToolCallCardHtml(ToolCallDisplayData toolCall) {
        String displayName = getToolDisplayName(toolCall.getName());
        String argsSummary = toolCall.getArgsSummary();
        String argsJson = toolCall.getArgsJson();

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"tool-call\" data-tool-call-id=\"") //$NON-NLS-1$
          .append(escapeHtml(toolCall.getId()))
          .append("\">\n"); //$NON-NLS-1$

        // Header
        sb.append("  <div class=\"tool-call-header\" onclick=\"toggleToolCall(this)\">\n"); //$NON-NLS-1$
        sb.append("    <span class=\"tool-call-icon\">\uD83D\uDD27</span>\n"); // 🔧 //$NON-NLS-1$
        sb.append("    <span class=\"tool-call-name\">").append(escapeHtml(displayName)).append("</span>\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Args summary (short)
        if (argsSummary != null && !argsSummary.isEmpty()) {
            sb.append("    <span class=\"tool-call-args-summary\">") //$NON-NLS-1$
              .append(escapeHtml(argsSummary))
              .append("</span>\n"); //$NON-NLS-1$
        }

        // Status badge
        sb.append("    <span class=\"tool-call-status ").append(toolCall.getStatus().cssClass).append("\">") //$NON-NLS-1$ //$NON-NLS-2$
          .append(toolCall.getStatus().icon)
          .append("</span>\n"); //$NON-NLS-1$

        sb.append("  </div>\n"); //$NON-NLS-1$

        // Body (collapsible)
        sb.append("  <div class=\"tool-call-body\">\n"); //$NON-NLS-1$

        // Arguments section
        if (argsJson != null && !argsJson.isEmpty() && !"{}".equals(argsJson)) { //$NON-NLS-1$
            sb.append("    <div class=\"tool-call-section\">\n"); //$NON-NLS-1$
            sb.append("      <div class=\"tool-call-section-title\">Аргументы</div>\n"); //$NON-NLS-1$
            sb.append("      <pre class=\"tool-call-args\">").append(escapeHtml(formatJson(argsJson))).append("</pre>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("    </div>\n"); //$NON-NLS-1$
        }

        // Result section (initially empty, filled by updateToolCallResult)
        sb.append("    <div class=\"tool-call-result\" style=\"display:none;\"></div>\n"); //$NON-NLS-1$

        sb.append("  </div>\n"); //$NON-NLS-1$
        sb.append("</div>\n"); //$NON-NLS-1$

        return sb.toString();
    }

    /**
     * Форматирует JSON для отображения.
     */
    private String formatJson(String json) {
        if (json == null || json.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        try {
            Gson gson = new Gson();
            JsonElement el = JsonParser.parseString(json);
            return gson.toJson(el);
        } catch (JsonSyntaxException e) {
            return json;
        }
    }

    /**
     * Возвращает человекопонятное имя инструмента.
     */
    private String getToolDisplayName(String name) {
        return ToolDisplayNames.get(name);
    }

    /**
     * Строит полный HTML-документ.
     */
    private String buildHtmlDocument(String messagesHtml) {
        String css = loadCss();
        String js = loadJs();
        String themeClass = ThemeManager.getInstance().isDarkTheme() ? "dark" : "light"; //$NON-NLS-1$ //$NON-NLS-2$

        return "<!DOCTYPE html>\n" + //$NON-NLS-1$
               "<html>\n" + //$NON-NLS-1$
               "<head>\n" + //$NON-NLS-1$
               "    <meta charset=\"UTF-8\">\n" + //$NON-NLS-1$
               "    <style>\n" + css + "\n    </style>\n" + //$NON-NLS-1$ //$NON-NLS-2$
               "</head>\n" + //$NON-NLS-1$
               "<body class=\"" + themeClass + "\">\n" + //$NON-NLS-1$ //$NON-NLS-2$
               "    <div class=\"chat-root\">\n" + //$NON-NLS-1$
               "        <div class=\"message-container\" id=\"messages\">\n" + //$NON-NLS-1$
               messagesHtml +
               "        </div>\n" + //$NON-NLS-1$
               "        <div class=\"typing-indicator\" id=\"typing-indicator\" style=\"display:none;\">\n" + //$NON-NLS-1$
               "            <div class=\"typing-content\">\n" + //$NON-NLS-1$
               "                <div class=\"typing-spinner\"></div>\n" + //$NON-NLS-1$
               "                <span id=\"typing-stage\">AI обрабатывает запрос</span>\n" + //$NON-NLS-1$
               "            </div>\n" + //$NON-NLS-1$
               "            <div class=\"typing-dots\">\n" + //$NON-NLS-1$
               "                <span class=\"typing-dot\"></span>\n" + //$NON-NLS-1$
               "                <span class=\"typing-dot\"></span>\n" + //$NON-NLS-1$
               "                <span class=\"typing-dot\"></span>\n" + //$NON-NLS-1$
               "            </div>\n" + //$NON-NLS-1$
               "        </div>\n" + //$NON-NLS-1$
               "    </div>\n" + //$NON-NLS-1$
               "    <script>\n" + js + "\n    </script>\n" + //$NON-NLS-1$ //$NON-NLS-2$
               "</body>\n" + //$NON-NLS-1$
               "</html>"; //$NON-NLS-1$
    }

    /**
     * Строит HTML для одного сообщения.
     */
    private String buildMessageHtml(ChatMessageData msg) {
        String messageClass = msg.isSystem ? "system" : (msg.isAssistant ? "assistant" : "user"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String contentHtml = buildMessageContentHtml(msg.content);
        String attachmentsHtml = buildAttachmentsHtml(msg.attachments);

        // Include reasoning block if present (for thinking mode)
        String reasoningHtml = ""; //$NON-NLS-1$
        if (msg.reasoning != null && !msg.reasoning.isEmpty()) {
            reasoningHtml = buildReasoningBlock(msg.reasoning);
        }

        // Model badge for assistant messages
        String modelBadgeHtml = ""; //$NON-NLS-1$
        if (msg.isAssistant && msg.modelName != null && !msg.modelName.isEmpty()) {
            modelBadgeHtml = " <span class=\"model-badge\">" + escapeHtml(msg.modelName) + "</span>"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Copy response button for assistant messages
        String copyResponseHtml = ""; //$NON-NLS-1$
        if (msg.isAssistant && !msg.isSystem) {
            copyResponseHtml = "        <button class=\"copy-response-btn\" onclick=\"copyResponse(this)\" title=\"\u041A\u043E\u043F\u0438\u0440\u043E\u0432\u0430\u0442\u044C \u043E\u0442\u0432\u0435\u0442\">\uD83D\uDCCB</button>\n"; //$NON-NLS-1$
        }

        return "<div class=\"message " + messageClass + "\" id=\"" + msg.id + "\">\n" + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
               "    <div class=\"message-header\">\n" + //$NON-NLS-1$
               "        <span class=\"message-sender\">" + escapeHtml(msg.sender) + modelBadgeHtml + "</span>\n" + //$NON-NLS-1$ //$NON-NLS-2$
               copyResponseHtml +
               "    </div>\n" + //$NON-NLS-1$
               "    <div class=\"message-content\">\n" + //$NON-NLS-1$
               reasoningHtml + contentHtml + attachmentsHtml + "\n" + //$NON-NLS-1$
               "    </div>\n" + //$NON-NLS-1$
               "</div>\n"; //$NON-NLS-1$
    }

    /**
     * Строит HTML для содержимого сообщения.
     */
    private String buildMessageContentHtml(String content) {
        return markdownParser.toHtml(content);
    }

    private String buildAttachmentsHtml(List<LlmAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        boolean hasVisibleAttachments = false;
        for (LlmAttachment attachment : attachments) {
            if (attachment == null || !attachment.isImage()) {
                continue;
            }
            if (!hasVisibleAttachments) {
                sb.append("<div class=\"chat-attachments\">"); //$NON-NLS-1$
                hasVisibleAttachments = true;
            }
            sb.append(buildImageAttachmentHtml(attachment));
        }
        if (!hasVisibleAttachments) {
            return ""; //$NON-NLS-1$
        }
        sb.append("</div>"); //$NON-NLS-1$
        return sb.toString();
    }

    private String buildImageAttachmentHtml(LlmAttachment attachment) {
        String src = resolveAttachmentUrl(attachment);
        String label = escapeHtml(attachment.toDisplayLabel());
        if (src == null || src.isBlank()) {
            return "<div class=\"chat-attachment image missing\">" + label + "</div>"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "<div class=\"chat-attachment image\">" //$NON-NLS-1$
                + "<img style=\"max-width:240px;max-height:160px;border-radius:8px;display:block;margin-top:8px;\"" //$NON-NLS-1$
                + " src=\"" + escapeHtml(src) + "\" alt=\"" + label + "\" />" //$NON-NLS-1$ //$NON-NLS-2$
                + "<div class=\"chat-attachment-label\" style=\"margin-top:4px;font-size:12px;opacity:0.8;\">" //$NON-NLS-1$
                + label + "</div>" //$NON-NLS-1$
                + "</div>"; //$NON-NLS-1$
    }

    private String resolveAttachmentUrl(LlmAttachment attachment) {
        if (attachment == null || attachment.getEffectivePath() == null || attachment.getEffectivePath().isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(attachment.getEffectivePath());
            if (!Files.exists(path)) {
                return null;
            }
            return path.toUri().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Загружает CSS.
     */
    private String loadCss() {
        if (cachedCss != null) return cachedCss;
        cachedCss = loadResource(CSS_RESOURCE);
        if (cachedCss == null) cachedCss = getDefaultCss();
        return cachedCss;
    }

    /**
     * Загружает JavaScript.
     */
    private String loadJs() {
        if (cachedJs != null) return cachedJs;
        cachedJs = loadResource(JS_RESOURCE);
        if (cachedJs == null) cachedJs = getDefaultJs();
        return cachedJs;
    }

    /**
     * Загружает ресурс из bundle.
     */
    private String loadResource(String path) {
        try {
            URL url = VibeUiPlugin.getDefault().getBundle().getEntry(path);
            if (url != null) {
                try (InputStream is = url.openStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n")); //$NON-NLS-1$
                }
            }
        } catch (IOException e) {
            VibeUiPlugin.log(e);
        }
        return null;
    }

    private String getDefaultCss() {
        return "body { font-family: sans-serif; font-size: 14px; padding: 12px; }\n" + //$NON-NLS-1$
               "body.dark { background: #1e293b; color: #f1f5f9; }\n" + //$NON-NLS-1$
               "table { border-collapse: collapse; width: 100%; }\n" + //$NON-NLS-1$
               "th, td { border: 1px solid #ccc; padding: 8px; }\n" + //$NON-NLS-1$
               "pre { background: #f5f5f5; padding: 12px; overflow-x: auto; }\n" + //$NON-NLS-1$
               "body.dark pre { background: #0f172a; }"; //$NON-NLS-1$
    }

    private String getDefaultJs() {
        return "function scrollToBottom() {\n" + //$NON-NLS-1$
               "  var container = document.getElementById('messages');\n" + //$NON-NLS-1$
               "  if (container) container.scrollTop = container.scrollHeight;\n" + //$NON-NLS-1$
               "}\n" + //$NON-NLS-1$
               "function setTheme(theme) { document.body.className = theme; }\n" + //$NON-NLS-1$
               "function copyCode(btn) {\n" + //$NON-NLS-1$
               "  var code = btn.closest('.code-block').querySelector('code');\n" + //$NON-NLS-1$
               "  if (typeof copyToClipboard === 'function') copyToClipboard(code.textContent);\n" + //$NON-NLS-1$
               "  btn.textContent = 'Скопировано!';\n" + //$NON-NLS-1$
               "  setTimeout(function() { btn.textContent = 'Копировать'; }, 2000);\n" + //$NON-NLS-1$
               "}"; //$NON-NLS-1$
    }

    private String escapeHtml(String text) {
        if (text == null) return ""; //$NON-NLS-1$
        return text.replace("&", "&amp;") //$NON-NLS-1$ //$NON-NLS-2$
                   .replace("<", "&lt;") //$NON-NLS-1$ //$NON-NLS-2$
                   .replace(">", "&gt;") //$NON-NLS-1$ //$NON-NLS-2$
                   .replace("\"", "&quot;"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String escapeForJs(String text) {
        if (text == null) return ""; //$NON-NLS-1$
        return text.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                   .replace("'", "\\'") //$NON-NLS-1$ //$NON-NLS-2$
                   .replace("\n", "\\n") //$NON-NLS-1$ //$NON-NLS-2$
                   .replace("\r", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void copyToClipboard(String text) {
        Display.getDefault().asyncExec(() -> {
            Clipboard clipboard = new Clipboard(Display.getCurrent());
            try {
                clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
            } finally {
                clipboard.dispose();
            }
        });
    }

    private void openExternalUrl(String url) {
        Display.getDefault().asyncExec(() -> Program.launch(url));
    }

    @Override
    public void dispose() {
        if (copyFunction != null) copyFunction.dispose();
        if (openUrlFunction != null) openUrlFunction.dispose();
        if (applyCodeFunction != null) applyCodeFunction.dispose();
        super.dispose();
    }
}
