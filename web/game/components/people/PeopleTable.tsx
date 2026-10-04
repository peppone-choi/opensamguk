'use client';

import { Chip, Portrait, safeNationColor } from '@opensamguk/ui';
import type { PeopleRow } from '@/lib/people-view';
import styles from './people.module.css';

/** 소속 칩 — 세력색 네모 + 이름, 재야는 글자만(설계서 P-R02 「무소속 표기는 재야 하나로」). */
export function Affiliation({ value }: { readonly value: PeopleRow['affiliation'] }) {
    if (!value) return <span className={styles.muted}>재야</span>;
    return (
        <span className={styles.nation}>
            <i className={styles.swatch} style={{ background: safeNationColor(value.color) }} aria-hidden="true" />
            {value.name}
        </span>
    );
}

/** 이름 뒤 칩 — 지금 서버가 알려 주는 것은 「나」뿐이다(사람 · 군주 표지는 계약판 K4-05 보강 뒤). */
export function NameChips({ row }: { readonly row: PeopleRow }) {
    return row.isMe ? <Chip tone="bronze">나</Chip> : null;
}

const q = (v: number | null | undefined) => (v == null ? '?' : String(v));

export interface PeopleTableProps {
    readonly rows: readonly PeopleRow[];
    readonly selectedId: number | null;
    readonly onSelect: (row: PeopleRow) => void;
    /** 소재 城 이름 — 화면이 가진 城 표로 푼다. 못 풀면 null → 「?」. */
    readonly cityName: (cityId: number) => string | null;
}

/**
 * 인물 표(보드 V31K4People tbl) — 행 44, 열 # · 인물 · 소속 · 자리 · 소재 · 통 무 지 정 매 · 합 · 적성 · 결속.
 * 시야 · 권한 밖 값은 「?」. 자리 열은 서버가 자리 라벨을 주기 전까지 비워 두고 표 아래에 한 줄로 알린다.
 * 고르기는 인물 칸 단추(44) — 오른쪽 미리보기가 바뀐다. 내 행은 청동 배경.
 */
export function PeopleTable({ rows, selectedId, onSelect, cityName }: PeopleTableProps) {
    return (
        <div className="os-table-wrap">
            <table className={`os-table os-table--nowrap ${styles.table}`}>
                <thead>
                    <tr>
                        <th scope="col">#</th>
                        <th scope="col">인물</th>
                        <th scope="col">소속</th>
                        <th scope="col">자리</th>
                        <th scope="col">소재</th>
                        <th scope="col">통</th>
                        <th scope="col">무</th>
                        <th scope="col">지</th>
                        <th scope="col">정</th>
                        <th scope="col">매</th>
                        <th scope="col">합</th>
                        <th scope="col">적성</th>
                        <th scope="col">결속</th>
                    </tr>
                </thead>
                <tbody>
                    {rows.map((r) => {
                        const s = r.stats;
                        const sel = r.generalId === selectedId;
                        return (
                            <tr key={r.generalId} data-me={r.isMe || undefined} data-selected={sel || undefined} data-general-id={r.generalId}>
                                <td className={`os-mono ${styles.muted}`}>{r.rank}</td>
                                <td>
                                    <button type="button" className={styles.pick} aria-pressed={sel} onClick={() => onSelect(r)}>
                                        <Portrait picture={r.picture} imageServer={r.imageServer} size="card-24" alt="" />
                                        <span className="os-serif" style={{ fontWeight: 700 }}>{r.name}</span>
                                        <NameChips row={r} />
                                    </button>
                                </td>
                                <td><Affiliation value={r.affiliation} /></td>
                                <td className={styles.muted}>—</td>
                                <td>{r.locationCityId == null ? '?' : cityName(r.locationCityId) ?? '?'}</td>
                                <td className="os-mono">{q(s?.leadership)}</td>
                                <td className="os-mono">{q(s?.strength)}</td>
                                <td className="os-mono">{q(s?.intel)}</td>
                                <td className="os-mono">{q(s?.politics)}</td>
                                <td className="os-mono">{q(s?.charm)}</td>
                                <td className="os-mono">{q(r.total)}</td>
                                <td>{r.top ? <Chip>{`${r.top.label} ${r.top.value}`}</Chip> : '?'}</td>
                                <td>{r.bonds == null ? '?' : r.bonds.length === 0 ? '—' : r.bonds.join(' · ')}</td>
                            </tr>
                        );
                    })}
                </tbody>
            </table>
        </div>
    );
}
