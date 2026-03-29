package com.codepilot1c.core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public class AttachmentTextExtractorTest {

    @Test
    public void extractsDocxPreviewText() throws Exception {
        Path file = Files.createTempFile("codepilot-docx-", ".docx"); //$NON-NLS-1$ //$NON-NLS-2$
        try {
            writeDocx(file, "Hello from DOCX\nSecond paragraph"); //$NON-NLS-1$

            String preview = AttachmentTextExtractor.extractPreviewText(file,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 4000); //$NON-NLS-1$

            assertEquals("Hello from DOCX\nSecond paragraph", preview); //$NON-NLS-1$
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void extractsPdfPreviewText() throws Exception {
        Path file = Files.createTempFile("codepilot-pdf-", ".pdf"); //$NON-NLS-1$ //$NON-NLS-2$
        try {
            writePdf(file, "Hello PDF"); //$NON-NLS-1$

            String preview = AttachmentTextExtractor.extractPreviewText(file, "application/pdf", 4000); //$NON-NLS-1$

            assertTrue(preview.contains("Hello PDF")); //$NON-NLS-1$
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void truncatesLongPreviewText() throws Exception {
        Path file = Files.createTempFile("codepilot-text-", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        try {
            Files.writeString(file, "abcdefghij", StandardCharsets.UTF_8); //$NON-NLS-1$

            String preview = AttachmentTextExtractor.extractPreviewText(file, "text/plain", 5); //$NON-NLS-1$

            assertEquals("abcde\n...[truncated]", preview); //$NON-NLS-1$
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void writeDocx(Path file, String text) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("word/document.xml")); //$NON-NLS-1$
            zip.write(docxXml(text).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static String docxXml(String text) {
        String[] paragraphs = text.split("\\n", -1); //$NON-NLS-1$
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>") //$NON-NLS-1$
                .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"); //$NON-NLS-1$
        for (String paragraph : paragraphs) {
            xml.append("<w:p><w:r><w:t>") //$NON-NLS-1$
                    .append(escapeXml(paragraph))
                    .append("</w:t></w:r></w:p>"); //$NON-NLS-1$
        }
        xml.append("</w:body></w:document>"); //$NON-NLS-1$
        return xml.toString();
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("<", "&lt;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void writePdf(Path file, String text) throws IOException {
        byte[] content = ("BT\n/F1 18 Tf\n50 100 Td\n(" + escapePdf(text) + ") Tj\nET\n") //$NON-NLS-1$ //$NON-NLS-2$
                .getBytes(StandardCharsets.US_ASCII);
        List<String> objects = new ArrayList<>();
        objects.add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"); //$NON-NLS-1$
        objects.add("2 0 obj\n<< /Type /Pages /Count 1 /Kids [3 0 R] >>\nendobj\n"); //$NON-NLS-1$
        objects.add("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] /Contents 4 0 R " //$NON-NLS-1$
                + "/Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n"); //$NON-NLS-1$
        objects.add("4 0 obj\n<< /Length " + content.length + " >>\nstream\n" //$NON-NLS-1$ //$NON-NLS-2$
                + new String(content, StandardCharsets.US_ASCII)
                + "endstream\nendobj\n"); //$NON-NLS-1$
        objects.add("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"); //$NON-NLS-1$

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII)); //$NON-NLS-1$
        List<Integer> offsets = new ArrayList<>();
        for (String object : objects) {
            offsets.add(Integer.valueOf(out.size()));
            out.write(object.getBytes(StandardCharsets.US_ASCII));
        }
        int xrefOffset = out.size();
        out.write(("xref\n0 " + (objects.size() + 1) + "\n").getBytes(StandardCharsets.US_ASCII)); //$NON-NLS-1$ //$NON-NLS-2$
        out.write("0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII)); //$NON-NLS-1$
        for (Integer offset : offsets) {
            out.write(String.format("%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII)); //$NON-NLS-1$
        }
        out.write(("trailer\n<< /Root 1 0 R /Size " + (objects.size() + 1) + " >>\n") //$NON-NLS-1$ //$NON-NLS-2$
                .getBytes(StandardCharsets.US_ASCII));
        out.write(("startxref\n" + xrefOffset + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII)); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(file, out.toByteArray());
    }

    private static String escapePdf(String text) {
        return text.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("(", "\\(") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(")", "\\)"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
