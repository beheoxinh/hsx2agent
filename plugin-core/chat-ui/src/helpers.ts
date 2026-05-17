/** Decode a base64-encoded UTF-8 string. Pair with MessageFormatter.encodeBase64() on the Kotlin side. */
export function decodeBase64(s: string): string {
    const r = atob(s);
    const b = new Uint8Array(r.length);
    for (let i = 0; i < r.length; i++) b[i] = r.codePointAt(i)!;
    return new TextDecoder().decode(b);
}

/** Collapse all expanded chip sections in a container, optionally except one. */
export function collapseAllChips(container: Element | null, except?: Element): void {
    if (!container) return;
    container.querySelectorAll('tool-chip, thinking-chip, subagent-chip').forEach(chip => {
        if (chip === except) return;
        const section = (chip as any)._linkedSection as HTMLElement | undefined;
        if (!section || section.classList.contains('turn-hidden')) return;
        (chip as HTMLElement).style.opacity = '1';
        section.classList.add('turn-hidden');
        section.classList.remove('chip-expanded', 'collapsing', 'collapsed');
    });
}

/** HTML-escape a string. */
export function escHtml(s: string | null | undefined): string {
    return s ? s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;') : '';
}

/**
 * Collapse a bubble's timestamp when the previous chat-message shares the same minute
 * (timestamps are rendered as "HH:MM"). Reduces visual noise in streams of close messages
 * while still marking the first bubble of each minute.
 *
 * Call *after* the message's .ts span has been populated and the message has been
 * inserted into the DOM. A session-divider between two messages always re-shows the
 * next timestamp, since a new session is a natural break regardless of minute.
 */
export function hideRedundantTimestamp(msg: Element): void {
    const tsEl = msg.querySelector('.ts');
    if (!tsEl?.textContent) return;
    const current = tsEl.textContent.trim();
    if (!current) return;
    let prev = msg.previousElementSibling;
    while (prev) {
        const tag = prev.tagName;
        if (tag === 'SESSION-DIVIDER') return;
        if (tag === 'CHAT-MESSAGE') {
            const prevTs = prev.querySelector('.ts');
            if (prevTs && prevTs.textContent?.trim() === current) {
                tsEl.classList.add('ts-hidden');
            }
            return;
        }
        prev = prev.previousElementSibling;
    }
}

/**
 * Format an ISO timestamp for display.
 * - Today: shows time only ("14:30")
 * - Yesterday: "Yesterday 14:30"
 * - Older: "May 14 14:30"
 * Returns the raw string on parse failure.
 */
export function formatTs(isoOrLegacy: string): string {
    if (!isoOrLegacy) return '';
    const d = new Date(isoOrLegacy);
    if (Number.isNaN(d.getTime())) return isoOrLegacy;
    const timeStr = d.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'});
    const now = new Date();
    if (d.toDateString() === now.toDateString()) return timeStr;
    const yesterday = new Date(now);
    yesterday.setDate(now.getDate() - 1);
    const datePrefix = d.toDateString() === yesterday.toDateString()
        ? 'Yesterday'
        : d.toLocaleDateString([], {month: 'short', day: 'numeric'});
    return `${datePrefix} ${timeStr}`;
}

export function createActionBtn(title: string, iconHtml: string, onClick: () => void): HTMLElement {
    const btn = document.createElement('button');
    btn.className = 'message-action-btn';
    btn.setAttribute('data-tooltip', title);
    btn.title = ''; // clear native tooltip
    btn.innerHTML = iconHtml;
    btn.onclick = (e) => {
        e.stopPropagation();
        onClick();
    };
    return btn;
}

export function addMessageActions(container: HTMLElement, isUser: boolean, turnId: string, text: string): void {
    const actions = document.createElement('div');
    actions.className = 'message-actions';

    const copyIcon = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>';
    const restoreIcon = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="1 4 1 10 7 10"></polyline><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"></path></svg>';
    const continueIcon = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>';

    actions.appendChild(createActionBtn('Copy to clipboard', copyIcon, () => {
        (globalThis as any)._bridge?.copyToClipboard?.(text);
    }));

    if (isUser) {
        actions.appendChild(createActionBtn('Restore to input', restoreIcon, () => {
            (globalThis as any)._bridge?.resendMessage?.(text);
        }));
    } else {
        actions.appendChild(createActionBtn('Continue response', continueIcon, () => {
            (globalThis as any)._bridge?.continueTurn?.(turnId);
        }));
    }

    container.appendChild(actions);
}
