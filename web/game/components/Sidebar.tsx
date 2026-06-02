'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { NAV_ITEMS } from '../lib/constants';

export default function Sidebar() {
    const pathname = usePathname();

    return (
        <nav className="game-sidebar" aria-label="Main">
            {NAV_ITEMS.map(item => (
                <Link
                    key={item.path}
                    href={item.path}
                    className={`game-sidebar-item${pathname === item.path ? ' active' : ''}`}
                >
                    <span className="game-sidebar-icon">{item.icon}</span>
                    <span className="game-sidebar-label">{item.label}</span>
                </Link>
            ))}
        </nav>
    );
}
