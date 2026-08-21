'use client';

import { Brand } from '@opensamguk/ui';
import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { formatNumber, formatYearMonthPhase } from '@/lib/format';

export default function Header() {
    const [turnText, setTurnText] = useState('서버 갱신 중');
    const [resourceText, setResourceText] = useState('');

    useEffect(() => {
        let alive = true;
        api.frontInfo()
            .then((info) => {
                if (!alive) return;
                setTurnText(formatYearMonthPhase(info.global.year, info.global.month, info.global.turnPhaseText));
                setResourceText(
                    info.general.hasGeneral
                        ? `금: ${formatNumber(info.general.gold)} · 쌀: ${formatNumber(info.general.rice)}`
                        : '',
                );
            })
            .catch(() => {
                if (!alive) return;
                setTurnText('서버 정보 없음');
                setResourceText('');
            });
        return () => {
            alive = false;
        };
    }, []);

    return (
        <header className="game-header">
            <div className="game-header-left">
                <Brand size="small" />
                <span className="game-header-turn">{turnText}</span>
            </div>
            <div className="game-header-right">
                {resourceText && <span className="game-header-res">{resourceText}</span>}
            </div>
        </header>
    );
}
