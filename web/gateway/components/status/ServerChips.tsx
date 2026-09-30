'use client';

import { Chip } from '@opensamguk/ui';

export interface ServerChoice {
    readonly id: string;
    readonly name: string;
    readonly generation?: number;
}

/**
 * 서버 고르기 칩 줄(설계서 SB1 · SB2). 누르면 지도 · 세력 현황 · 천하 정세가 그 서버로 바뀐다.
 * 서버 목록은 페이지 서버 렌더가 준다(첫 지도 경로에서 `/api/servers` 왕복을 뺀다, 설계서 P-G02 성능).
 */
export default function ServerChips({ servers, selectedId, onSelect }: {
    readonly servers: readonly ServerChoice[];
    readonly selectedId: string;
    readonly onSelect: (id: string) => void;
}) {
    return (
        <div className="gw31-chips" role="group" aria-label="서버 고르기">
            {servers.map((server) => {
                const on = server.id === selectedId;
                return (
                    <button
                        key={server.id}
                        type="button"
                        className={`os-button gw31-btn gw31-chip-btn${on ? ' is-on' : ''}`}
                        aria-pressed={on}
                        onClick={() => onSelect(server.id)}
                    >
                        {server.name}
                        {server.generation != null && <Chip tone={on ? 'neutral' : 'bronze'} className="gw31-chip-btn__gen">{server.generation}기</Chip>}
                    </button>
                );
            })}
        </div>
    );
}
