package com.codepilot1c.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.LlmAttachment;
import com.codepilot1c.core.model.LlmContentPart;
import com.codepilot1c.core.model.LlmMessage;

public class SessionMultimodalMessageTest {

    @Test
    public void toLlmMessagesPreservesUserContentParts() {
        Session session = new Session("multimodal-session"); //$NON-NLS-1$
        LlmAttachment attachment = LlmAttachment.builder()
                .kind(LlmAttachment.Kind.FILE)
                .displayName("requirements.md") //$NON-NLS-1$
                .mimeType("text/markdown") //$NON-NLS-1$
                .originalPath("/tmp/requirements.md") //$NON-NLS-1$
                .previewText("# Requirements") //$NON-NLS-1$
                .sizeBytes(128)
                .build();
        List<LlmContentPart> parts = List.of(
                LlmContentPart.text("Посмотри документ"), //$NON-NLS-1$
                LlmContentPart.file(attachment));
        LlmMessage message = LlmMessage.user(parts);

        session.addMessage(SessionMessage.user(message.getContent(), parts));

        List<LlmMessage> llmMessages = session.toLlmMessages();
        assertEquals(1, llmMessages.size());

        LlmMessage restored = llmMessages.get(0);
        assertTrue(restored.hasContentParts());
        assertEquals(2, restored.getContentParts().size());
        assertEquals(1, restored.getAttachments().size());
        assertTrue(restored.getTextualContentFallback().contains("# Requirements")); //$NON-NLS-1$
        assertEquals("requirements.md", restored.getAttachments().get(0).getDisplayName()); //$NON-NLS-1$
    }

    @Test
    public void toLlmMessagesPreservesAssistantReasoningContent() {
        Session session = new Session("reasoning-session"); //$NON-NLS-1$
        session.addMessage(SessionMessage.assistant("visible answer", "private reasoning")); //$NON-NLS-1$ //$NON-NLS-2$

        List<LlmMessage> llmMessages = session.toLlmMessages();

        assertEquals(1, llmMessages.size());
        assertEquals("visible answer", llmMessages.get(0).getContent()); //$NON-NLS-1$
        assertEquals("private reasoning", llmMessages.get(0).getReasoningContent()); //$NON-NLS-1$
    }
}
