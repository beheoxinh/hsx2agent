import {PollableView} from './PollableView';
import type {ToolCallData} from '../ToolCallsController';
import ToolCallsController from '../ToolCallsController';
import {formatTs} from '../helpers';

/**
 * Web component for displaying MCP tool calls with an interactive pipeline visualization.
 *
 * <p>In the IDE (JCEF), data is pushed by Java via {@code ToolCallsController.upsert()}.
 * In the PWA, this component polls {@code /tool-calls} and feeds data through
 * {@code ToolCallsController.setAll()}.
 *
 * <p>When a tool call row is expanded, the detail view shows a visual pipeline:
 * {@code Input → [Permission] → [Pre-hook] → Tool Execution → [Post-hook] → Output}.
 * Each stage is clickable and shows the corresponding data.
 */
export class ToolCallsView extends PollableView {
    private _list!: HTMLElement;
    private _empty!: HTMLElement;
    private _container!: HTMLElement;
    private _unsubscribe: (() => void) | null = null;
    /** True when running inside a JCEF panel (data pushed by Java). */
    private _pushMode = false;
    /** Auto-scroll to bottom when new items arrive. Disabled when user scrolls up. */
    private _autoScroll = true;
    /** Prevents re-entrant auto-load calls. */
    private _loadingMore = false;

    constructor() {
        super(2000);
    }

    connectedCallback(): void {
        this.innerHTML = `
            <div class="tcv-container">
                <div class="tcv-load-more" hidden>↑ Load earlier tool calls</div>
                <div class="tcv-empty">No tool calls yet</div>
                <div class="tcv-list"></div>
            </div>`;
        this._container = this.querySelector<HTMLElement>('.tcv-container')!;
        this._list = this.querySelector<HTMLElement>('.tcv-list')!;
        this._empty = this.querySelector<HTMLElement>('.tcv-empty')!;
        this._list.addEventListener('click', (e) => this._handleClick(e));

        const loadMoreBtn = this.querySelector<HTMLElement>('.tcv-load-more')!;
        loadMoreBtn.addEventListener('click', () => this._loadMore());

        // Scrolling upward disables auto-scroll; reaching the bottom re-enables it.
        this._container.addEventListener('wheel', (e: WheelEvent) => {
            if (e.deltaY < 0 && this._autoScroll) {
                this._autoScroll = false;
            }
        }, {passive: true});
        this._container.addEventListener('scroll', () => {
            if (!this._autoScroll && this._isAtBottom()) {
                this._autoScroll = true;
            }
            // Auto-load more when scrolled to top
            if (this._container.scrollTop < 30) {
                this._loadMore();
            }
        }, {passive: true});

        this._unsubscribe = ToolCallsController.onChange(() => this._render());
    }

    disconnectedCallback(): void {
        super.disconnectedCallback();
        this._unsubscribe?.();
        this._unsubscribe = null;
    }

    /** Enable push mode (JCEF) — disables polling. */
    setPushMode(enabled: boolean): void {
        this._pushMode = enabled;
        if (enabled) this.deactivate();
    }

    async refresh(): Promise<void> {
        if (this._pushMode) return;
        try {
            const resp = await fetch('/tool-calls');
            if (!resp.ok) return;
            const data = await resp.json() as { items: ToolCallData[] };
            ToolCallsController.setAll(data.items);
        } catch {
            // Network error — will retry on next poll
        }
    }

    private _handleClick(e: MouseEvent): void {
        const target = e.target as HTMLElement;

        // View diff button click
        if ((target as HTMLElement).classList.contains('tcv-diff-btn')) {
            const row = target.closest<HTMLElement>('.tcv-item');
            if (row?.dataset.id) {
                const item = ToolCallsController.get(row.dataset.id!);
                if (item?.originalArguments) {
                    const fn = (window as any).openInputDiff;
                    if (typeof fn === 'function') fn(item.originalArguments, item.arguments, item.toolName);
                }
            }
            e.stopPropagation();
            return;
        }

        // Show tool popup
        const row = target.closest<HTMLElement>('.tcv-item');
        if (!row?.dataset.id) return;
        const id = row.dataset.id;

        const fn = (window as any).showToolPopup;
        if (typeof fn === 'function') {
            fn(id);
        }
    }

    private _render(): void {
        // Controller returns newest-first; reverse for chronological order (newest at bottom).
        const items = ToolCallsController.getAll().reverse();
        const noItems = items.length === 0;
        if (this.toggleEmptyState(this._empty, this._list, noItems)) return;

        // Show/hide "Load earlier" button
        const loadMoreEl = this.querySelector<HTMLElement>('.tcv-load-more');
        if (loadMoreEl) {
            const hasHistoric = items.some(i => i.historic);
            loadMoreEl.hidden = ToolCallsController.isHistoryExhausted() || !hasHistoric;
        }

        const html: string[] = [];
        let lastMinuteKey = '';
        for (const item of items) {
            if (item.timestamp) {
                const d = new Date(item.timestamp);
                const minuteKey = `${d.toDateString()} ${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
                if (minuteKey !== lastMinuteKey) {
                    html.push(`<div class="tcv-time-sep">${formatTs(item.timestamp)}</div>`);
                    lastMinuteKey = minuteKey;
                }
            }
            html.push(this._renderItem(item));
        }
        this._list.innerHTML = html.join('');

        // Only auto-scroll when no item is expanded — don't yank the view away from what the user is reading.
        if (this._autoScroll) {
            this._container.scrollTop = this._container.scrollHeight;
        }
    }

    private _isAtBottom(): boolean {
        return this._container.scrollHeight - this._container.scrollTop - this._container.clientHeight < 50;
    }

    /** Requests an older page of tool calls from the host (push mode only). */
    private _loadMore(): void {
        if (!this._pushMode || this._loadingMore || ToolCallsController.isHistoryExhausted()) return;
        this._loadingMore = true;
        const oldestId = ToolCallsController.oldestHistoricId();
        const fn = (window as any).loadMoreToolCalls;
        if (typeof fn === 'function') {
            fn(oldestId ?? '');
        }
        // Reset guard after a short delay to allow re-triggers if the call failed silently.
        setTimeout(() => {
            this._loadingMore = false;
        }, 2000);
    }

    private _renderItem(item: ToolCallData): string {
        const kindClass = this._kindCssClass(item.kind);
        const status = item.status || 'running';
        const duration = item.durationMs >= 0 ? this._formatDuration(item.durationMs) : '';

        // Mirror chat panel chip classes exactly: turn-chip tool is-agentbridge-tool kind-* status-*
        // so chip-ring CSS (spinning/filled/broken circle) renders identically in both panels.
        const historicBadge = item.historic
            ? '<span class="tcv-historic" title="Historic tool call" aria-label="Historic tool call">◷</span>'
            : '';

        return `<div class="tcv-item turn-chip tool is-agentbridge-tool ${kindClass} status-${status}" data-id="${item.id}">
            <span class="chip-ring" aria-hidden="true"></span>
            ${historicBadge}
            <span class="tcv-title">${this.esc(item.title)}</span>
            ${duration ? `<span class="tcv-duration">${duration}</span>` : ''}
        </div>`;
    }

    private _kindCssClass(kind?: string): string {
        const k = (kind || '').toLowerCase().trim();
        return k ? `kind-${k}` : 'kind-other';
    }

    /**
     * Formats a duration in milliseconds.
     * Shows one decimal place for durations under 10 seconds (e.g. "3.2s") for
     * precision when quick tool calls are being compared.
     */
    private _formatDuration(ms: number): string {
        if (ms < 0) return '';
        if (ms === 0) return '0s';
        if (ms < 10_000) return (ms / 1000).toFixed(1) + 's';
        const totalSec = Math.round(ms / 1000);
        if (totalSec < 60) return totalSec + 's';
        const min = Math.floor(totalSec / 60);
        const sec = totalSec % 60;
        if (min < 60) return sec > 0 ? min + 'm ' + sec + 's' : min + 'm';
        const hr = Math.floor(min / 60);
        const remMin = min % 60;
        return remMin > 0 ? hr + 'h ' + remMin + 'm' : hr + 'h';
    }
}
