'use client';

import { Card } from '@opensamguk/ui';
import type { CSSProperties, ReactNode } from 'react';

interface GameCardProps {
    children: ReactNode;
    className?: string;
    style?: CSSProperties;
}

export default function GameCard({ children, className = '', style }: GameCardProps) {
    return <Card className={`game-card ${className}`} style={style}>{children}</Card>;
}
