/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codepilot1c.ui.chat.MessagePart.CodeBlockPart;
import com.codepilot1c.ui.chat.MessagePart.TextPart;
import com.codepilot1c.ui.chat.MessagePart.TodoListPart;
import com.codepilot1c.ui.chat.MessagePart.TodoListPart.TodoItem;

/**
 * Parses raw message content into structured {@link MessagePart}s.
 *
 * <p>Recognizes and extracts:</p>
 * <ul>
 *   <li>Code blocks (```language ... ```)</li>
 *   <li>Todo lists (- [ ] item, - [x] item)</li>
 *   <li>Plain text (everything else)</li>
 * </ul>
 */
public class MessageContentParser {

    // Code block: ```language\ncode\n```
    private static final Pattern CODE_BLOCK_PATTERN =
            Pattern.compile("```(\\w*)\\n([\\s\\S]*?)```", Pattern.MULTILINE); //$NON-NLS-1$

    // Todo item: - [ ] text or - [x] text or * [ ] text or * [x] text
    private static final Pattern TODO_ITEM_PATTERN =
            Pattern.compile("^[\\s]*[-*]\\s*\\[([ xX])\\]\\s*(.+)$", Pattern.MULTILINE); //$NON-NLS-1$

    // Check if content contains todo items (for deciding whether to parse as todo list)
    private static final Pattern HAS_TODO_PATTERN =
            Pattern.compile("[-*]\\s*\\[[ xX]\\]"); //$NON-NLS-1$

    /**
     * Parses raw content into a list of message parts.
     *
     * @param rawContent the raw Markdown content
     * @return list of parsed message parts
     */
    public List<MessagePart> parse(String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) {
            return List.of();
        }

        List<MessagePart> parts = new ArrayList<>();

        // First, extract code blocks
        List<CodeBlockInfo> codeBlocks = extractCodeBlocks(rawContent);

        if (codeBlocks.isEmpty()) {
            // No code blocks - check for todo lists in the entire content
            parseTextOrTodo(rawContent, parts);
        } else {
            // Process text and code blocks alternately
            int lastEnd = 0;
            for (CodeBlockInfo block : codeBlocks) {
                // Text before this code block
                if (block.start > lastEnd) {
                    String textBefore = rawContent.substring(lastEnd, block.start);
                    if (!textBefore.trim().isEmpty()) {
                        parseTextOrTodo(textBefore, parts);
                    }
                }

                // The code block itself
                parts.add(new CodeBlockPart(block.code.trim(), block.language));

                lastEnd = block.end;
            }

            // Text after the last code block
            if (lastEnd < rawContent.length()) {
                String textAfter = rawContent.substring(lastEnd);
                if (!textAfter.trim().isEmpty()) {
                    parseTextOrTodo(textAfter, parts);
                }
            }
        }

        return parts;
    }

    /**
     * Extracts code blocks from content.
     *
     * @param content the content to search
     * @return list of code block info
     */
    private List<CodeBlockInfo> extractCodeBlocks(String content) {
        List<CodeBlockInfo> blocks = new ArrayList<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content);

        while (matcher.find()) {
            blocks.add(new CodeBlockInfo(
                    matcher.start(),
                    matcher.end(),
                    matcher.group(1), // language
                    matcher.group(2)  // code
            ));
        }

        return blocks;
    }

    /**
     * Parses text content that may contain todo lists.
     *
     * @param text the text to parse
     * @param parts the list to add parts to
     */
    private void parseTextOrTodo(String text, List<MessagePart> parts) {
        if (!HAS_TODO_PATTERN.matcher(text).find()) {
            // No todo items - add as plain text
            parts.add(new TextPart(text.trim()));
            return;
        }

        // Parse todo items and surrounding text
        parseTodoList(text, parts);
    }

    /**
     * Parses content containing todo items.
     *
     * @param text the text to parse
     * @param parts the list to add parts to
     */
    private void parseTodoList(String text, List<MessagePart> parts) {
        String[] lines = text.split("\\n"); //$NON-NLS-1$
        StringBuilder textBuffer = new StringBuilder();
        List<TodoItem> todoItems = new ArrayList<>();

        for (String line : lines) {
            Matcher todoMatcher = TODO_ITEM_PATTERN.matcher(line);
            if (todoMatcher.matches()) {
                // Flush any accumulated text before the todo list
                if (textBuffer.length() > 0 && todoItems.isEmpty()) {
                    String bufferedText = textBuffer.toString().trim();
                    if (!bufferedText.isEmpty()) {
                        parts.add(new TextPart(bufferedText));
                    }
                    textBuffer.setLength(0);
                }

                // Parse todo item
                boolean checked = !todoMatcher.group(1).trim().isEmpty();
                String itemText = todoMatcher.group(2).trim();
                todoItems.add(new TodoItem(itemText, checked));
            } else {
                // Not a todo item
                if (!todoItems.isEmpty()) {
                    // Flush accumulated todo items
                    parts.add(new TodoListPart(new ArrayList<>(todoItems)));
                    todoItems.clear();
                }

                // Accumulate text
                if (textBuffer.length() > 0) {
                    textBuffer.append("\n"); //$NON-NLS-1$
                }
                textBuffer.append(line);
            }
        }

        // Flush remaining todo items
        if (!todoItems.isEmpty()) {
            parts.add(new TodoListPart(new ArrayList<>(todoItems)));
        }

        // Flush remaining text
        if (textBuffer.length() > 0) {
            String remainingText = textBuffer.toString().trim();
            if (!remainingText.isEmpty()) {
                parts.add(new TextPart(remainingText));
            }
        }
    }

    /**
     * Extracts only the code blocks from content.
     *
     * @param content the content to search
     * @return list of code block parts
     */
    public List<CodeBlockPart> extractCodeBlockParts(String content) {
        List<CodeBlockPart> blocks = new ArrayList<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content);

        while (matcher.find()) {
            blocks.add(new CodeBlockPart(
                    matcher.group(2).trim(), // code
                    matcher.group(1)         // language
            ));
        }

        return blocks;
    }

    /**
     * Checks if content contains code blocks.
     *
     * @param content the content to check
     * @return true if code blocks are present
     */
    public boolean hasCodeBlocks(String content) {
        if (content == null) {
            return false;
        }
        return CODE_BLOCK_PATTERN.matcher(content).find();
    }

    /**
     * Checks if content contains todo items.
     *
     * @param content the content to check
     * @return true if todo items are present
     */
    public boolean hasTodoItems(String content) {
        if (content == null) {
            return false;
        }
        return HAS_TODO_PATTERN.matcher(content).find();
    }

    /**
     * Helper class to store code block positions.
     */
    private static class CodeBlockInfo {
        final int start;
        final int end;
        final String language;
        final String code;

        CodeBlockInfo(int start, int end, String language, String code) {
            this.start = start;
            this.end = end;
            this.language = language;
            this.code = code;
        }
    }

}
