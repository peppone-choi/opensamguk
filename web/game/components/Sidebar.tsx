'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { NAV_ITEMS } from '../lib/constants';
import { normalizeGamePathname, resolveServerGamePath, useServerId } from '../lib/serverGameUrl';

export default function Sidebar() {
    const pathname = usePathname();
    const serverId = useServerId();
    const normalizedPathname = normalizeGamePathname(pathname ?? '', serverId);

    return (
        <nav className="game-sidebar" aria-label="Main">
            {NAV_ITEMS.map((item) => {
                const childPath = item.path.replace(/^\/game\/?/, '');
                const href = serverId
                    ? resolveServerGamePath(undefined, serverId, '/game', childPath)
                    : item.path;
                return (
                    <Link
                        key={item.path}
                        href={href}
                        className={`game-sidebar-item${normalizedPathname === item.path ? ' active' : ''}`}
                    >
                        <span className="game-sidebar-icon">{item.icon}</span>
                        <span className="game-sidebar-label">{item.label}</span>
                    </Link>
                );
            })}
        </nav>
    );
}
