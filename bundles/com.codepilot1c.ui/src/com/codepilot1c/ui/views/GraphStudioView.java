/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import com.codepilot1c.core.agent.langgraph.LangGraphStudioService;
import com.codepilot1c.ui.internal.VibeUiPlugin;
import com.codepilot1c.ui.theme.ThemeManager;
import com.codepilot1c.ui.theme.VibeTheme;

/**
 * View for LangGraph graph visualization (Mermaid).
 */
public class GraphStudioView extends ViewPart {

    public static final String ID = "com.codepilot1c.ui.views.GraphStudioView"; //$NON-NLS-1$
    private static final String MERMAID_RESOURCE = "web/mermaid.min.js"; //$NON-NLS-1$
    private static volatile String mermaidJs;

    private Browser browser;
    private Text fallbackText;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());

        browser = createBrowser(parent);
        if (browser == null) {
            fallbackText = createFallback(parent, "SWT Browser недоступен. Граф будет показан текстом."); //$NON-NLS-1$
            renderFallback();
            return;
        }

        renderMermaid();
    }

    @Override
    public void setFocus() {
        if (browser != null && !browser.isDisposed()) {
            browser.setFocus();
        } else if (fallbackText != null && !fallbackText.isDisposed()) {
            fallbackText.setFocus();
        }
    }

    private Browser createBrowser(Composite parent) {
        try {
            return new Browser(parent, SWT.EDGE);
        } catch (SWTError e1) {
            try {
                return new Browser(parent, SWT.NONE);
            } catch (SWTError e2) {
                VibeUiPlugin.log(e2); //$NON-NLS-1$
                return null;
            }
        }
    }

    private Text createFallback(Composite parent, String header) {
        Text text = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        if (header != null && !header.isBlank()) {
            text.setText(header + "\n\n"); //$NON-NLS-1$
        }
        return text;
    }

    private void renderMermaid() {
        if (browser == null || browser.isDisposed()) {
            return;
        }
        String mermaid = LangGraphStudioService.getInstance().getMermaidGraph();
        if (mermaid == null || mermaid.isBlank()) {
            renderFallback();
            return;
        }
        String script = getMermaidJs();
        if (script == null || script.isBlank()) {
            renderFallback();
            return;
        }
        VibeTheme theme = ThemeManager.getInstance().getTheme();
        String html = buildMermaidHtml(script, mermaid, theme.isDark(),
                theme.getBackground(), theme.getText());
        browser.setText(html);
    }

    private void renderFallback() {
        if (fallbackText == null || fallbackText.isDisposed()) {
            return;
        }
        String mermaid = LangGraphStudioService.getInstance().getMermaidGraph();
        StringBuilder sb = new StringBuilder();
        sb.append(fallbackText.getText());
        sb.append("=== Mermaid ===\n"); //$NON-NLS-1$
        sb.append(mermaid).append("\n"); //$NON-NLS-1$
        fallbackText.setText(sb.toString());
    }

    private String getMermaidJs() {
        String cached = mermaidJs;
        if (cached != null) {
            return cached;
        }
        synchronized (GraphStudioView.class) {
            if (mermaidJs != null) {
                return mermaidJs;
            }
            try (InputStream in = GraphStudioView.class.getClassLoader()
                    .getResourceAsStream(MERMAID_RESOURCE)) {
                if (in == null) {
                    VibeUiPlugin.log("Mermaid ресурс не найден: " + MERMAID_RESOURCE); //$NON-NLS-1$
                    return null;
                }
                mermaidJs = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return mermaidJs;
            } catch (IOException e) {
                VibeUiPlugin.log(e);
                return null;
            }
        }
    }

    private String buildMermaidHtml(String script, String code, boolean darkTheme,
            org.eclipse.swt.graphics.Color background, org.eclipse.swt.graphics.Color textColor) {
        StringBuilder sb = new StringBuilder();
        String bg = String.format("#%02x%02x%02x", background.getRed(), background.getGreen(), background.getBlue()); //$NON-NLS-1$
        String fg = String.format("#%02x%02x%02x", textColor.getRed(), textColor.getGreen(), textColor.getBlue()); //$NON-NLS-1$
        String themeName = darkTheme ? "dark" : "default"; //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"); //$NON-NLS-1$
        sb.append("<style>"); //$NON-NLS-1$
        sb.append("body{margin:0;padding:0;background:").append(bg).append(";color:")
                .append(fg).append(";font-family:Arial, sans-serif;}"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(".mermaid{width:100%;}"); //$NON-NLS-1$
        sb.append("</style></head><body>"); //$NON-NLS-1$
        sb.append("<div class=\"mermaid\">"); //$NON-NLS-1$
        sb.append(escapeHtml(code));
        sb.append("</div>"); //$NON-NLS-1$
        sb.append("<script>"); //$NON-NLS-1$
        sb.append(script);
        sb.append("</script>"); //$NON-NLS-1$
        sb.append("<script>"); //$NON-NLS-1$
        sb.append("try{mermaid.initialize({startOnLoad:true,securityLevel:'loose',theme:'")
                .append(themeName).append("'});}catch(e){console.error(e);}"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("</script>"); //$NON-NLS-1$
        sb.append("</body></html>"); //$NON-NLS-1$
        return sb.toString();
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.replace("&", "&amp;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("<", "&lt;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
