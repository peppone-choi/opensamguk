'use client';

// MainControlBar — verbatim 20-button bar (spec §2), port of MainControlBar.vue.
// Renders CONTROL_BUTTONS with per-button gating from front-info general fields. A FAILED gate
// renders a non-navigating <span class="disabled"> (NOT a Link) so navigation is actually blocked
// (the legacy <a class="disabled"> still navigated; our port must block). new-window buttons open
// in a new tab. #19 경매장 is a split with a 금/쌀 ↔ 유니크 sub-dropdown.

import Link from 'next/link';
import { useState } from 'react';
import { CONTROL_BUTTONS, type ControlButton, type GateBucket } from '@/lib/control-bar-config';
import { gameChildPath, resolveServerGamePath, useServerId } from '@/lib/serverGameUrl';

export interface ControlGating {
    showSecret: boolean;
    permission: number;
    myLevel: number; // officer_level proxy
    nationLevel: number;
    isTournamentApplicationOpen: boolean;
    isBettingActive: boolean;
}

function gateAllows(bucket: GateBucket, g: ControlGating): boolean {
    switch (bucket) {
        case 'always':
            return true;
        case 'myLevel':
            return g.myLevel >= 1;
        case 'myLevelAndNation':
            return g.myLevel >= 1 && g.nationLevel >= 1;
        case 'permission2':
            return g.permission >= 2;
        case 'showSecret':
            return g.showSecret === true;
    }
}

function isHighlighted(btn: ControlButton, g: ControlGating): boolean {
    if (!btn.highlightVar) return false;
    return Boolean(g[btn.highlightVar]);
}

function resolveControlHref(href: string, serverId: string | undefined): string {
    if (!serverId) return href;
    return resolveServerGamePath(undefined, serverId, '/game', gameChildPath(href));
}

function SplitButton({
    btn,
    enabled,
    highlight,
    serverId,
}: {
    btn: ControlButton;
    enabled: boolean;
    highlight: boolean;
    serverId: string | undefined;
}) {
    const [open, setOpen] = useState(false);
    const cls = `control-btn${highlight ? ' highlight' : ''}${enabled ? '' : ' disabled'}`;
    const mainHref = resolveControlHref(btn.href, serverId);

    return (
        <div className="control-btn-group" onMouseLeave={() => setOpen(false)}>
            {enabled ? (
                <Link className={cls} href={mainHref} target={btn.newTab ? '_blank' : undefined}>
                    {btn.label}
                </Link>
            ) : (
                <span className={cls} aria-disabled="true">
                    {btn.label}
                </span>
            )}
            <button
                type="button"
                className="control-btn control-split-toggle"
                aria-label="Toggle Dropdown"
                onClick={() => setOpen((o) => !o)}
            >
                ▾
            </button>
            {open && (
                <ul className="control-split-menu">
                    {btn.split!.map((sub, i) => {
                        const subHref = resolveControlHref(sub.href, serverId);
                        return (
                            <li key={i}>
                                <Link
                                    className="dropdown-item"
                                    href={subHref}
                                    target={sub.newTab ? '_blank' : undefined}
                                    onClick={() => setOpen(false)}
                                >
                                    {sub.label}
                                </Link>
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}

export default function MainControlBar({ gating }: { gating: ControlGating }) {
    const serverId = useServerId();

    return (
        <nav className="control-bar" aria-label="국가 메뉴">
            {CONTROL_BUTTONS.map((btn) => {
                const enabled = gateAllows(btn.bucket, gating);
                const highlight = isHighlighted(btn, gating);

                if (btn.split) {
                    return (
                        <SplitButton
                            key={btn.id}
                            btn={btn}
                            enabled={enabled}
                            highlight={highlight}
                            serverId={serverId}
                        />
                    );
                }

                const cls = `control-btn${highlight ? ' highlight' : ''}${enabled ? '' : ' disabled'}`;
                const href = resolveControlHref(btn.href, serverId);
                if (!enabled) {
                    return (
                        <span key={btn.id} className={cls} aria-disabled="true">
                            {btn.label}
                        </span>
                    );
                }
                return (
                    <Link key={btn.id} className={cls} href={href} target={btn.newTab ? '_blank' : undefined}>
                        {btn.label}
                    </Link>
                );
            })}
        </nav>
    );
}
