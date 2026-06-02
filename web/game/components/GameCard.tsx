'use client';

import React from 'react';

interface GameCardProps {
    children: React.ReactNode;
    className?: string;
    style?: React.CSSProperties;
}

export default function GameCard({ children, className = '', style }: GameCardProps) {
    return <div className={`game-card ${className}`} style={style}>{children}</div>;
}
