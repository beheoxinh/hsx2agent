import {collapseAllChips} from '../helpers';

const BRAIN_ICON = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9.5 2A2.5 2.5 0 0 1 12 4.5V5a5 5 0 0 1 5 5v4.5a2.5 2.5 0 0 1-5 0V14"/><path d="M9.5 2A2.5 2.5 0 0 0 7 4.5V5a5 5 0 0 0-5 5v4.5a2.5 2.5 0 0 0 5 0V14"/><path d="M9.5 2A2.5 2.5 0 0 0 12 4.5V12"/><path d="M5 14.5c-1.5.5-2.5 1.5-2.5 3 0 1.5 2 2.5 4 2.5"/><path d="M14.5 14.5c1.5.5 2.5 1.5 2.5 3 0 1.5-2 2.5-4 2.5"/><path d="M12 19v1c0 1.1-.9 2-2 2"/><path d="M12 19v1c0 1.1.9 2 2 2"/></svg>`;

export default class ThinkingChip extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['status'];
    }

    private _init = false;
    _linkedSection: HTMLElement | null = null;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('turn-chip', 'kind-think');
        this.setAttribute('role', 'button');
        this.setAttribute('tabindex', '0');
        this.setAttribute('aria-expanded', 'false');
        this._render();
        this.onclick = (e) => {
            e.stopPropagation();
            this._toggleExpand();
        };
        this.onkeydown = (e) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                this._toggleExpand();
            }
        };
    }

    private _render(): void {
        const status = this.getAttribute('status') || 'complete';
        this.innerHTML = `<span class="chip-ring" aria-hidden="true"></span> ${BRAIN_ICON} <span>Thought</span>`;
        this.classList.toggle('status-thinking', status === 'running' || status === 'thinking');
        this.classList.toggle('status-complete', status !== 'running' && status !== 'thinking');
    }

    attributeChangedCallback(name: string): void {
        if (!this._init) return;
        if (name === 'status') {
            const status = this.getAttribute('status') || 'complete';
            this.classList.toggle('status-thinking', status === 'running' || status === 'thinking');
            this.classList.toggle('status-complete', status !== 'running' && status !== 'thinking');
        }
    }

    private _resolveLink(): void {
        if (!this._linkedSection && this.dataset.chipFor) {
            this._linkedSection = document.getElementById(this.dataset.chipFor);
        }
    }

    private _toggleExpand(): void {
        this._resolveLink();
        const section = this._linkedSection;
        if (!section) return;
        collapseAllChips(this.closest('chat-message'), this);
        if (section.classList.contains('turn-hidden')) {
            section.classList.remove('turn-hidden');
            section.classList.add('chip-expanded');
            this.classList.add('chip-dimmed');
            this.setAttribute('aria-expanded', 'true');
        } else {
            this.classList.remove('chip-dimmed');
            section.classList.add('collapsing');
            setTimeout(() => {
                section.classList.remove('collapsing', 'chip-expanded');
                section.classList.add('turn-hidden');
            }, 250);
            this.setAttribute('aria-expanded', 'false');
        }
    }

    linkSection(section: HTMLElement): void {
        this._linkedSection = section;
    }
}
