/**
 * Chat JavaScript - Interactive functionality
 */

/**
 * Copy code block content to clipboard.
 * @param {HTMLElement} button - The copy button element
 */
function copyCode(button) {
    var codeBlock = button.closest('.code-block');
    var code = codeBlock.querySelector('pre code');
    var text = code.textContent || code.innerText;

    // Try using the Java callback first
    if (typeof copyToClipboard === 'function') {
        copyToClipboard(text);
        showCopied(button);
        return;
    }

    // Fallback to Clipboard API
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function() {
            showCopied(button);
        }).catch(function(err) {
            console.error('Failed to copy:', err);
            fallbackCopy(text, button);
        });
    } else {
        fallbackCopy(text, button);
    }
}

/**
 * Fallback copy using textarea.
 */
function fallbackCopy(text, button) {
    var textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.left = '-9999px';
    document.body.appendChild(textarea);
    textarea.select();

    try {
        document.execCommand('copy');
        showCopied(button);
    } catch (err) {
        console.error('Fallback copy failed:', err);
    }

    document.body.removeChild(textarea);
}

/**
 * Show "Copied!" feedback on button.
 */
function showCopied(button) {
    var originalText = button.textContent;
    button.textContent = 'Скопировано!';
    button.classList.add('copied');

    setTimeout(function() {
        button.textContent = originalText;
        button.classList.remove('copied');
    }, 2000);
}

/**
 * Toggle tool call expansion.
 * @param {HTMLElement} header - The tool call header element
 */
function toggleToolCall(header) {
    var toolCall = header.closest('.tool-call');
    toolCall.classList.toggle('expanded');
}

/**
 * Update tool call card with result.
 * Called from Java when tool execution completes.
 * @param {string} id - The tool call ID
 * @param {string} status - CSS class for status (pending, running, success, error)
 * @param {string} statusIcon - Icon character for status
 * @param {string} summary - Result summary text (e.g., "1,240 chars")
 * @param {string} preview - Result preview text (first 200 chars)
 */
function updateToolCallCard(id, status, statusIcon, summary, preview) {
    var card = document.querySelector('[data-tool-call-id="' + id + '"]');
    if (!card) {
        console.warn('Tool call card not found:', id);
        return;
    }
    var shouldStickToBottom = isNearBottom();

    // Update status badge
    var statusEl = card.querySelector('.tool-call-status');
    if (statusEl) {
        // Remove old status classes
        statusEl.classList.remove('pending', 'running', 'success', 'error');
        statusEl.classList.add(status);
        statusEl.textContent = statusIcon + ' ' + summary;
    }

    // Show result section with preview if present
    var resultEl = card.querySelector('.tool-call-result');
    if (resultEl && preview && preview.trim()) {
        resultEl.innerHTML = '<div class="tool-call-section-title">Результат</div>' +
                             '<pre class="tool-call-result-preview">' + escapeHtml(preview) + '</pre>';
        resultEl.style.display = 'block';
    }

    // Auto-expand on error
    if (status === 'error') {
        card.classList.add('expanded');
    }

    // Keep user position if they are reading older messages
    if (shouldStickToBottom) {
        scrollToBottom();
    }
}

/**
 * Escape HTML special characters for safe insertion.
 * @param {string} text - Text to escape
 * @returns {string} Escaped text
 */
function escapeHtml(text) {
    if (!text) return '';
    var div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Scroll to bottom of message container.
 */
function scrollToBottom() {
    var container = document.querySelector('.message-container');
    if (container) {
        container.scrollTop = container.scrollHeight;
    }
}

/**
 * Returns true when user is close to the bottom of message list.
 */
function isNearBottom() {
    var container = document.querySelector('.message-container');
    if (!container) {
        return true;
    }
    var delta = container.scrollHeight - container.clientHeight - container.scrollTop;
    return delta <= 80;
}

/**
 * Update the last assistant message with reasoning and content.
 * Used for streaming updates with thinking mode.
 * @param {string} reasoningHtml - HTML for the reasoning block
 * @param {string} contentHtml - HTML for the main content
 */
function updateMessageWithReasoning(reasoningHtml, contentHtml) {
    var messages = document.querySelectorAll('.message.assistant');
    var lastMessage = messages[messages.length - 1];
    if (!lastMessage) return;

    var contentEl = lastMessage.querySelector('.message-content');
    if (!contentEl) return;

    // Build new content
    var html = '';

    // Add reasoning block if present
    if (reasoningHtml && reasoningHtml.trim()) {
        html += reasoningHtml;
    }

    // Add main content
    if (contentHtml && contentHtml.trim()) {
        html += '<div class="message-text">' + contentHtml + '</div>';
    }

    contentEl.innerHTML = html;

    // Re-highlight code blocks
    contentEl.querySelectorAll('pre code').forEach(function(block) {
        if (typeof hljs !== 'undefined') {
            hljs.highlightElement(block);
        }
    });

    // Scroll to bottom
    scrollToBottom();
}

/**
 * Initialize highlight.js if available.
 */
function initHighlighting() {
    if (typeof hljs !== 'undefined') {
        hljs.highlightAll();
    }
}

/**
 * Set theme (light/dark).
 * @param {string} theme - 'light' or 'dark'
 */
function setTheme(theme) {
    document.body.className = theme;
}

/**
 * Handle link clicks - open in external browser.
 * @param {Event} event - Click event
 */
function handleLinkClick(event) {
    var link = event.target.closest('a');
    if (link && link.href) {
        event.preventDefault();
        // Call Java function to open URL
        if (typeof openUrl === 'function') {
            openUrl(link.href);
        } else {
            window.open(link.href, '_blank');
        }
    }
}

/**
 * Initialize event listeners.
 */
function init() {
    // Link click handler
    document.addEventListener('click', function(event) {
        if (event.target.tagName === 'A' || event.target.closest('a')) {
            handleLinkClick(event);
        }
    });

    // Initialize highlighting
    initHighlighting();

    // Scroll to bottom on load
    scrollToBottom();
}

// Auto-init when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
