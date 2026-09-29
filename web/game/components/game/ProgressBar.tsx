'use client';

import { ICON_CDN } from '@/lib/constants';

interface ProgressBarProps {
    percent: number;
    height?: 7 | 10;
    className?: string;
    title?: string;
}

function clampPercent(value: number): number {
    if (!Number.isFinite(value)) return 0;
    return Math.min(100, Math.max(0, value));
}

export default function ProgressBar({ percent, height = 7, className, title }: ProgressBarProps) {
    const pct = clampPercent(percent);
    const assetHeight = height - 2;
    return (
        <div
            className={className ? `progress-bar ${className}` : 'progress-bar'}
            title={title ?? `${pct.toLocaleString(undefined, { maximumFractionDigits: 2 })}%`}
            style={{
                height: height + 2,
            }}
        >
            <div
                className="progress-bar-base"
                style={{
                    height,
                    backgroundImage: `url(${ICON_CDN}/pr${assetHeight}.gif)`,
                }}
            />
            <div
                className="progress-bar-fill"
                style={{
                    width: `${pct}%`,
                    height,
                    backgroundImage: `url(${ICON_CDN}/pb${assetHeight}.gif)`,
                }}
            />
        </div>
    );
}
