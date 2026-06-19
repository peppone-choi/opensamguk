'use client';

// MainControlDropdown — compact 국가 메뉴 replica (spec §2 + §8.2), port of MainControlDropdown.vue.
// Identical items + identical gating as MainControlBar, but compact labels and a multi-column
// (CSS `columns`, default 4) dropdown layout for the mobile GameBottomBar dropup. new-window items
// get `.open-window`. Disabled items render a non-navigating <span>. The split (#19) is flattened
// into its two sub-items (금/쌀 + 유니크), matching the legacy dropdown.

import { CONTROL_BUTTONS, type GateBucket } from '@/lib/control-bar-config';
import { gameChildPath, resolveServerGamePath, useServerId } from '@/lib/serverGameUrl';
import type { ControlGating } from './MainControlBar';

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

interface FlatEntry {
    key: string;
    label: string;
    href: string;
    enabled: boolean;
    newTab?: boolean;
}

function resolveControlHref(rawHref: string, serverId: string | undefined): string {
    if (!serverId) return rawHref;
    return resolveServerGamePath(undefined, serverId, '/game', gameChildPath(rawHref));
}

export default function MainControlDropdown({
    gating,
    columns = 4,
}: {
    gating: ControlGating;
    columns?: number;
}) {
    const serverId = useServerId();
    const entries: FlatEntry[] = [];

    for (const btn of CONTROL_BUTTONS) {
        const enabled = gateAllows(btn.bucket, gating);
        if (btn.split) {
            // Flatten the 경매장 split into its sub-items (matches legacy dropdown).
            for (const sub of btn.split) {
                entries.push({
                    key: `${btn.id}-${sub.label}`,
                    label: sub.label,
                    href: resolveControlHref(sub.href, serverId),
                    enabled,
                    newTab: sub.newTab,
                });
            }
        } else {
            entries.push({
                key: String(btn.id),
                label: btn.compactLabel,
                href: resolveControlHref(btn.href, serverId),
                enabled,
                newTab: btn.newTab,
            });
        }
    }

    return (
        <ul
            className="dropdown-menu dropdown-menu-start gm-columns"
            style={{ ['--gm-columns' as string]: String(columns) } as React.CSSProperties}
        >
            {entries.map((e) => (
                <li key={e.key}>
                    {e.enabled ? (
                        <a
                            className={`dropdown-item${e.newTab ? ' open-window' : ''}`}
                            href={e.href}
                            target={e.newTab ? '_blank' : undefined}
                            rel={e.newTab ? 'noopener noreferrer' : undefined}
                        >
                            {e.label}
                        </a>
                    ) : (
                        <span className="dropdown-item disabled" aria-disabled="true">
                            {e.label}
                        </span>
                    )}
                </li>
            ))}
        </ul>
    );
}
