/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Extracts text previews from file attachments before they are sent to the model.
 */
public final class AttachmentTextExtractor {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(AttachmentTextExtractor.class);

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; //$NON-NLS-1$
    private static final String PDF_MIME = "application/pdf"; //$NON-NLS-1$

    private AttachmentTextExtractor() {
    }

    public static String extractPreviewText(Path path, String mimeType, int charLimit) {
        if (path == null || !Files.exists(path) || charLimit <= 0) {
            return null;
        }
        try {
            if (isPreviewableTextFile(path, mimeType)) {
                return truncate(Files.readString(path, StandardCharsets.UTF_8), charLimit);
            }
            if (isDocxFile(path, mimeType)) {
                return truncate(extractDocxText(path), charLimit);
            }
            if (isPdfFile(path, mimeType)) {
                return truncate(extractPdfText(path), charLimit);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to extract attachment preview from %s: %s", path, e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    static boolean isPreviewableTextFile(Path path, String mimeType) {
        if (mimeType != null && (mimeType.startsWith("text/") //$NON-NLS-1$
                || "application/json".equals(mimeType) //$NON-NLS-1$
                || "application/xml".equals(mimeType))) { //$NON-NLS-1$
            return true;
        }
        String lower = lowerName(path);
        return lower.endsWith(".bsl") || lower.endsWith(".txt") || lower.endsWith(".md") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".csv") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || lower.endsWith(".java") || lower.endsWith(".js") || lower.endsWith(".ts") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || lower.endsWith(".yaml") || lower.endsWith(".yml"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isDocxFile(Path path, String mimeType) {
        return DOCX_MIME.equalsIgnoreCase(mimeType) || lowerName(path).endsWith(".docx"); //$NON-NLS-1$
    }

    private static boolean isPdfFile(Path path, String mimeType) {
        return PDF_MIME.equalsIgnoreCase(mimeType) || lowerName(path).endsWith(".pdf"); //$NON-NLS-1$
    }

    private static String extractDocxText(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml"); //$NON-NLS-1$
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return cleanupStructuredText(readWordprocessingMl(in));
            } catch (XMLStreamException e) {
                throw new IOException("Failed to parse docx XML", e); //$NON-NLS-1$
            }
        }
    }

    private static String readWordprocessingMl(InputStream in) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        disableUnsafeXmlFeatures(factory);
        XMLStreamReader reader = factory.createXMLStreamReader(in);
        StringBuilder text = new StringBuilder();
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    text.append(reader.getText());
                    continue;
                }
                if (event != XMLStreamConstants.START_ELEMENT && event != XMLStreamConstants.END_ELEMENT) {
                    continue;
                }
                String name = reader.getLocalName();
                if ("tab".equals(name) && event == XMLStreamConstants.START_ELEMENT) { //$NON-NLS-1$
                    text.append('\t');
                } else if (("br".equals(name) || "cr".equals(name)) && event == XMLStreamConstants.START_ELEMENT) { //$NON-NLS-1$ //$NON-NLS-2$
                    text.append('\n');
                } else if ("p".equals(name) && event == XMLStreamConstants.END_ELEMENT) { //$NON-NLS-1$
                    text.append('\n');
                }
            }
        } finally {
            reader.close();
        }
        return text.toString();
    }

    private static void disableUnsafeXmlFeatures(XMLInputFactory factory) {
        trySet(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        trySet(factory, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
    }

    private static void trySet(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // Best-effort hardening for XML parsers with partial property support.
        }
    }

    private static String extractPdfText(Path path) throws IOException {
        try (PDDocument document = PDDocument.load(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return cleanupStructuredText(stripper.getText(document));
        }
    }

    private static String cleanupStructuredText(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replace("\r\n", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f]+", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll(" +", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll(" *\n *", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("\n{3,}", "\n\n") //$NON-NLS-1$ //$NON-NLS-2$
                .trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private static String truncate(String text, int charLimit) {
        if (text == null) {
            return null;
        }
        if (text.length() <= charLimit) {
            return text;
        }
        return text.substring(0, charLimit) + "\n...[truncated]"; //$NON-NLS-1$
    }

    private static String lowerName(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT);
    }
}
