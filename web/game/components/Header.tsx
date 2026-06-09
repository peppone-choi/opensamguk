'use client';

import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

interface HeaderProps {
    onCommand: () => void;
}

export default function Header({ onCommand }: HeaderProps) {
    const [turnText, setTurnText] = useState('서버 갱신 중');

    useEffect(() => {
        let alive = true;
        api.frontInfo()
            .then((info) => {
                if (alive) setTurnText(`${info.global.year}년 ${info.global.month}월 · 1순`);
            })
            .catch(() => {
                if (alive) setTurnText('서버 정보 없음');
            });
        return () => {
            alive = false;
        };
    }, []);

    return (
        <header className="game-header">
            <div className="game-header-left">
                <span className="game-header-brand">opensamguk</span>
                <span className="game-header-turn">{turnText}</span>
            </div>
            <div className="game-header-right">
                <button className="game-header-cmd" onClick={onCommand}>명령</button>
                <span className="game-header-res">금: 10,000 · 쌀: 5,000</span>
            </div>
        </header>
    );
}
