'use client';

import { usePathname } from 'next/navigation';
import { NAV_ITEMS } from '../lib/constants';
import { normalizeGamePathname, resolveServerGamePath, useServerId } from '../lib/serverGameUrl';

export default function BottomNav() {
    const pathname = usePathname();
    const serverId = useServerId();
    const normalizedPathname = normalizeGamePathname(pathname ?? '');

    return (
        <nav className="game-bottom-nav" aria-label="Mobile">
            {NAV_ITEMS.slice(0, 5).map((item) => {
                const childPath = item.path.replace(/^\/game\/?/, '');
                const href = serverId
                    ? resolveServerGamePath(undefined, serverId, '/game', childPath)
                    : item.path;
                return (
                    <a
                        key={item.path}
                        href={href}
                        className={`game-bottom-item${normalizedPathname === item.path ? ' active' : ''}`}
                    >
                        <span className="game-bottom-icon">{item.icon}</span>
                        <span className="game-bottom-label">{item.label}</span>
                    </a>
                );
            })}
        </nav>
    );
}
