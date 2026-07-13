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
 * Minimal class selector matcher for SWT Browser builds that do not support
 * Element.closest()/matches(). We only need class selectors in the chat chrome.
 * @param {HTMLElement} element - element to inspect
 * @param {string} selector - class selector, e.g. '.tool-call'
 * @returns {boolean} true when the element has the class
 */
function hasClassSelector(element, selector) {
    if (!element || !selector || selector.charAt(0) !== '.') return false;
    var className = selector.substring(1);
    return element.classList
        ? element.classList.contains(className)
        : (' ' + (element.className || '') + ' ').indexOf(' ' + className + ' ') >= 0;
}

function addClassName(element, className) {
    if (!element || !className || hasClassSelector(element, '.' + className)) return;
    if (element.classList && element.classList.add) {
        element.classList.add(className);
    } else {
        element.className = ((element.className || '') + ' ' + className).replace(/^\s+|\s+$/g, '');
    }
}

function removeClassName(element, className) {
    if (!element || !className) return;
    if (element.classList && element.classList.remove) {
        element.classList.remove(className);
    } else {
        element.className = (' ' + (element.className || '') + ' ')
            .replace(' ' + className + ' ', ' ')
            .replace(/^\s+|\s+$/g, '');
    }
}

/**
 * Safe closest() replacement for SWT Browser / older WebKit engines.
 * @param {HTMLElement} element - starting element
 * @param {string} selector - class selector
 * @returns {HTMLElement|null} nearest matching ancestor
 */
function closestByClass(element, selector) {
    var current = element;
    while (current && current !== document) {
        if (hasClassSelector(current, selector)) return current;
        current = current.parentElement || current.parentNode;
    }
    return null;
}

function firstDescendantByClass(element, className) {
    if (!element) return null;
    if (element.getElementsByClassName) {
        var matches = element.getElementsByClassName(className);
        return matches && matches.length ? matches[0] : null;
    }
    if (element.querySelector) {
        return element.querySelector('.' + className);
    }
    return null;
}

function setExpanded(element, bodyClassName, expanded) {
    if (!element) return;
    if (expanded) {
        addClassName(element, 'expanded');
    } else {
        removeClassName(element, 'expanded');
    }
    if (element.style) {
        element.style.height = expanded ? 'auto' : '';
        element.style.maxHeight = expanded ? 'none' : '';
        element.style.overflow = expanded ? 'visible' : '';
    }
    var body = firstDescendantByClass(element, bodyClassName);
    if (body && body.style) {
        body.style.display = expanded ? 'block' : 'none';
        body.style.overflow = expanded ? 'visible' : '';
        body.style.height = expanded ? 'auto' : '';
        body.style.maxHeight = expanded ? 'none' : '';
    }
}

function syncToolCallExpansionStates(root) {
    root = root || document;
    var groups = root.getElementsByClassName ? root.getElementsByClassName('tool-calls-group') : [];
    for (var i = 0; i < groups.length; i++) {
        setExpanded(groups[i], 'tool-calls-group-body', hasClassSelector(groups[i], '.expanded'));
    }
    var cards = root.getElementsByClassName ? root.getElementsByClassName('tool-call') : [];
    for (var j = 0; j < cards.length; j++) {
        setExpanded(cards[j], 'tool-call-body', hasClassSelector(cards[j], '.expanded'));
    }
}

/**
 * Toggle tool call expansion.
 * @param {HTMLElement} header - The tool call header element
 */
function toggleToolCall(header) {
    var toolCall = closestByClass(header, '.tool-call');
    if (toolCall) {
        setExpanded(toolCall, 'tool-call-body', !hasClassSelector(toolCall, '.expanded'));
    }
}

/**
 * Toggle tool call group expansion.
 * @param {HTMLElement} header - The tool call group header element
 */
function toggleToolCallGroup(header) {
    var group = closestByClass(header, '.tool-calls-group');
    if (group) {
        setExpanded(group, 'tool-calls-group-body', !hasClassSelector(group, '.expanded'));
    }
}

/**
 * Bind tool-call expand/collapse through delegated listeners. SWT Browser can
 * preserve inserted markup while dropping inline onclick in some re-render paths;
 * delegated binding keeps cards expandable after streaming updates and inserts.
 */
function bindToolCallInteractions() {
    var container = document.getElementById('messages') || document;
    if (container.__toolCallInteractionsBound) {
        return;
    }
    container.__toolCallInteractionsBound = true;
    syncToolCallExpansionStates(container);
    container.addEventListener('click', function(event) {
        var target = event.target || event.srcElement;
        var header = closestByClass(target, '.tool-call-header');
        if (header) {
            event.preventDefault();
            toggleToolCall(header);
            return;
        }
        var groupHeader = closestByClass(target, '.tool-calls-group-header');
        if (groupHeader) {
            event.preventDefault();
            toggleToolCallGroup(groupHeader);
        }
    });
}

/**
 * Copy entire AI response to clipboard.
 * @param {HTMLElement} button - The copy response button element
 */
function copyResponse(button) {
    var message = button.closest('.message');
    if (!message) return;

    var contentEl = message.querySelector('.message-content');
    if (!contentEl) return;

    // Get text content, skipping reasoning blocks
    var text = '';
    var children = contentEl.children;
    for (var i = 0; i < children.length; i++) {
        if (!children[i].classList.contains('reasoning-block')) {
            text += children[i].textContent + '\n';
        }
    }
    // Fallback: use full textContent if no child elements
    if (!text.trim()) {
        text = contentEl.textContent;
    }

    // Try using the Java callback first
    if (typeof copyToClipboard === 'function') {
        copyToClipboard(text.trim());
        showCopied(button);
        return;
    }

    // Fallback to Clipboard API
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text.trim()).then(function() {
            showCopied(button);
        }).catch(function(err) {
            fallbackCopy(text.trim(), button);
        });
    } else {
        fallbackCopy(text.trim(), button);
    }
}

/**
 * Update tool call card with result.
 * Called from Java when tool execution completes.
 * @param {string} id - The tool call ID
 * @param {string} status - CSS class for status (pending, running, success, error)
 * @param {string} statusIcon - Icon character for status
 * @param {string} summary - Result summary text (e.g., "1,240 chars")
 * @param {string} preview - Result preview text
 */
function updateToolCallCard(id, status, statusIcon, summary, preview) {
    var cards = document.querySelectorAll('[data-tool-call-id="' + id + '"]');
    var card = cards.length ? cards[cards.length - 1] : null;
    if (!card) {
        console.warn('Tool call card not found:', id);
        return;
    }
    var shouldStickToBottom = isNearBottom();

    // Update status badge
    var statusEl = card.querySelector('.tool-call-status');
    if (statusEl) {
        // Remove old status classes
        removeClassName(statusEl, 'pending');
        removeClassName(statusEl, 'running');
        removeClassName(statusEl, 'success');
        removeClassName(statusEl, 'error');
        addClassName(statusEl, status);
        statusEl.textContent = statusIcon + ' ' + summary;
    }

    var resultEl = card.querySelector('.tool-call-result');
    if (resultEl) {
        if (status === 'success' && preview && preview.trim()) {
            resultEl.innerHTML = '<div class="tool-call-section-title">Результат</div>' +
                                 '<pre class="tool-call-result-preview">' + escapeHtml(preview) + '</pre>';
            resultEl.style.display = 'block';
        } else if (status === 'error' && preview && preview.trim()) {
            resultEl.innerHTML = '<div class="tool-call-section-title">Ошибка</div>' +
                                 '<pre class="tool-call-result-preview">' + escapeHtml(preview) + '</pre>';
            resultEl.style.display = 'block';
        } else {
            resultEl.innerHTML = '';
            resultEl.style.display = 'none';
        }
    }

    // Auto-expand finished cards with readable content; errors should always open.
    if ((status === 'success' || status === 'error') && preview && preview.trim()) {
        setExpanded(card, 'tool-call-body', true);
        var group = closestByClass(card, '.tool-calls-group');
        if (group) {
            setExpanded(group, 'tool-calls-group-body', true);
        }
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
 * Keep the typing indicator as the last normal flow item. It must not sit
 * between a tool-call header and its expanded body, because that visually
 * overlays the card contents in SWT Browser.
 */
function ensureTypingIndicatorAtBottom() {
    var container = document.getElementById('messages');
    var typing = document.getElementById('typing-indicator');
    if (container && typing && typing.parentElement === container && typing !== container.lastElementChild) {
        container.appendChild(typing);
    }
    return typing;
}

/**
 * Insert chat-flow HTML before the persistent typing indicator.
 * The typing indicator lives inside the scroll container so it scrolls naturally,
 * but it must remain after normal messages.
 */
function insertMessageFlowHtml(html) {
    var container = document.getElementById('messages');
    if (!container) return null;

    var typing = ensureTypingIndicatorAtBottom();
    if (typing && typing.parentElement === container) {
        typing.insertAdjacentHTML('beforebegin', html);
    } else {
        container.insertAdjacentHTML('beforeend', html);
    }
    ensureTypingIndicatorAtBottom();
    syncToolCallExpansionStates(container);
    return container;
}

/**
 * Remove chat messages while preserving the persistent typing indicator node.
 */
function clearMessageFlow() {
    var container = document.getElementById('messages');
    if (!container) return;

    var children = Array.prototype.slice.call(container.children);
    children.forEach(function(child) {
        if (child.id !== 'typing-indicator') {
            container.removeChild(child);
        }
    });
}

/**
 * Convert an empty assistant placeholder into a visual tool-only turn.
 * The Java model keeps this node as the ordering anchor for tool-call cards.
 */
function markMessageAsToolTurn(id) {
    var message = document.getElementById(id);
    if (!message) return;

    addClassName(message, 'tool-turn');

    var contentEl = message.querySelector('.message-content');
    if (contentEl) {
        contentEl.innerHTML = '';
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
    var lastMessage = null;
    for (var i = messages.length - 1; i >= 0; i--) {
        if (!hasClassSelector(messages[i], '.tool-turn')) {
            lastMessage = messages[i];
            break;
        }
    }
    if (!lastMessage) return;

    var contentEl = lastMessage.querySelector('.message-content');
    if (!contentEl) return;

    var toolNodes = [];
    Array.prototype.slice.call(contentEl.children).forEach(function(child) {
        if (child.classList.contains('tool-call') || child.classList.contains('tool-calls-group')) {
            toolNodes.push(child);
        } else {
            contentEl.removeChild(child);
        }
    });

    if (reasoningHtml && reasoningHtml.trim()) {
        var reasoningWrapper = document.createElement('div');
        reasoningWrapper.innerHTML = reasoningHtml;
        Array.prototype.slice.call(reasoningWrapper.childNodes).forEach(function(node) {
            contentEl.appendChild(node);
        });
    }

    if (contentHtml && contentHtml.trim()) {
        var textEl = document.createElement('div');
        textEl.className = 'message-text';
        textEl.innerHTML = contentHtml;
        contentEl.appendChild(textEl);
    }

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
    bindToolCallInteractions();

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
