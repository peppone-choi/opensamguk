'use client';

interface StatusBadgeProps {
    variant: 'gold' | 'crimson' | 'jade' | 'muted';
    children: React.ReactNode;
}

export default function StatusBadge({ variant, children }: StatusBadgeProps) {
    return <span className={`status-badge status-${variant}`}>{children}</span>;
}
