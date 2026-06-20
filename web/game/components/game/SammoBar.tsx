'use client';

import { ICON_CDN } from '@/lib/constants';

interface SammoBarProps {
    percent: number;
    height?: 7 | 10;
    className?: string;
    title?: string;
}

function clampPercent(value: number): number {
    if (!Number.isFinite(value)) return 0;
    return Math.min(100, Math.max(0, value));
}

export default function SammoBar({ percent, height = 7, className, title }: SammoBarProps) {
    const pct = clampPercent(percent);
    const assetHeight = height - 2;
    return (
        <div
            className={className ? `sammo-bar ${className}` : 'sammo-bar'}
            title={title ?? `${pct.toLocaleString(undefined, { maximumFractionDigits: 2 })}%`}
            style={{
                height: height + 2,
            }}
        >
            <div
                className="sammo-bar-base"
                style={{
                    height,
                    backgroundImage: `url(${ICON_CDN}/pr${assetHeight}.gif)`,
                }}
            />
            <div
                className="sammo-bar-fill"
                style={{
                    width: `${pct}%`,
                    height,
                    backgroundImage: `url(${ICON_CDN}/pb${assetHeight}.gif)`,
                }}
            />
        </div>
    );
}
