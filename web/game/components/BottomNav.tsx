'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { NAV_ITEMS } from '../lib/constants';

interface BottomNavProps {
    onCommand: () => void;
}

export default function BottomNav({ onCommand }: BottomNavProps) {
    const pathname = usePathname();

    return (
        <nav className="game-bottom-nav" aria-label="Mobile">
            {NAV_ITEMS.slice(0, 5).map(item => (
                <Link
                    key={item.path}
                    href={item.path}
                    className={`game-bottom-item${pathname === item.path ? ' active' : ''}`}
                >
                    <span className="game-bottom-icon">{item.icon}</span>
                    <span className="game-bottom-label">{item.label}</span>
                </Link>
            ))}
            <button className="game-bottom-item" onClick={onCommand}>
                <span className="game-bottom-icon">⚡</span>
                <span className="game-bottom-label">명령</span>
            </button>
        </nav>
    );
}
