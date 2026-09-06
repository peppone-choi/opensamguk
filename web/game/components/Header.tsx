'use client';

// 상태바(56px) — ADR-LITE-049 03 작전실 상단. 워드마크 30px · 서버명·기수 · 시나리오 / 현재 年月순(serif) ·
// (턴텀분 턴 서버) · 자금 · 군량 · 전체 접속자 수 · 내 초상(32, 국가 링 self). 값은 front-info 에서만 온다.
import { Brand, Portrait } from '@opensamguk/ui';
import { formatNumber } from '@/lib/format';
import type { FrontInfoResponse } from '@/lib/types';

export interface HeaderProps {
    info: FrontInfoResponse | null;
    error?: boolean;
}

export default function Header({ info, error = false }: HeaderProps) {
    const global = info?.global;
    const general = info?.general;
    const nation = info?.nation;
    const generation = global?.generation ?? global?.serverCnt;
    const serverLine = [global?.title, global?.serverName, generation == null ? '' : `${generation}기`].filter(Boolean).join(' ');
    const phase = global?.turnPhaseText ?? '';

    return (
        <header className="os-topbar game-header" aria-label="상태바">
            <div className="os-topbar__left">
                <Brand size="large" className="os-topbar__brand" />
                <span className="os-topbar__sep" aria-hidden="true" />
                <div className="os-topbar__server">
                    <span className="os-topbar__server-name">{serverLine || (error ? '서버 정보 없음' : '서버 갱신 중')}</span>
                    {global?.scenarioText && <span className="os-topbar__scenario">{global.scenarioText}</span>}
                </div>
            </div>
            <div className="os-topbar__right">
                {global && (
                    <div className="os-inset os-topbar__clock" aria-label="현재 순">
                        <span className="os-serif">
                            현재: {global.year}年 {global.month}月 {phase && <span className="os-topbar__phase">{phase}</span>}
                        </span>
                        <span className="os-topbar__dim">({global.turnterm}분 턴 서버)</span>
                    </div>
                )}
                {general?.hasGeneral && (
                    <div className="os-topbar__res game-header-res">
                        <span title="자금">
                            금 <b className="os-num">{formatNumber(general.gold)}</b>
                        </span>
                        <span title="군량">
                            쌀 <b className="os-num">{formatNumber(general.rice)}</b>
                        </span>
                    </div>
                )}
                {global?.onlineUserCnt != null && (
                    <span className="os-topbar__online" title="전체 접속자 수">
                        <b className="os-num">{formatNumber(global.onlineUserCnt)}</b>
                        <span className="os-topbar__dim">명 접속</span>
                    </span>
                )}
                {general?.hasGeneral && (
                    <Portrait
                        picture={general.picture}
                        imageServer={general.imageServer}
                        size="icon-32"
                        alt={general.name ?? '내 장수'}
                        ring={nation?.color ? { color: nation.color, reason: 'self' } : null}
                    />
                )}
            </div>
        </header>
    );
}
