'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import GeneralBasicCard from '../../../components/game/GeneralBasicCard';
import MyInfoLogPanel from '../../../components/game/MyInfoLogPanel';
import { api } from '../../../lib/api';
import { submitCommandAndAwaitResult } from '../../../lib/commandSubmit';
import { formatNumber } from '../../../lib/format';
import { formatDefenceTrain } from '../../../lib/utilGame';
import type { FrontInfoResponse, MyPageItem, MyPageResponse } from '../../../lib/types';

function InfoGrid({ rows }: { rows: [string, React.ReactNode][] }) {
    return (
        <div
            style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
                gap: '1px',
                background: 'var(--border-subtle)',
                fontSize: 'var(--text-sm)',
            }}
        >
            {rows.map(([label, value]) => (
                <div key={label} className="basic-card-row" style={{ display: 'contents' }}>
                    <div className="basic-card-head">{label}</div>
                    <div className="basic-card-body">{value}</div>
                </div>
            ))}
        </div>
    );
}

function currentDefenceTrain(frontInfo: FrontInfoResponse | null): string {
    const value = frontInfo?.general.defenceTrain;
    if (value == null) return '-';
    return `${formatDefenceTrain(value)}(훈사${value})`;
}

type InstantActionCode = 'DropItem' | 'InstantRetreat' | 'DieOnPrestart';

export default function MyPage() {
    const [frontInfo, setFrontInfo] = useState<FrontInfoResponse | null>(null);
    const [myPage, setMyPage] = useState<MyPageResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [selectedItemType, setSelectedItemType] = useState('');
    const [submittingAction, setSubmittingAction] = useState<InstantActionCode | ''>('');
    const [actionMessage, setActionMessage] = useState('');
    const [settings, setSettings] = useState<MyPageResponse['settings'] | null>(null);

    const fetchData = () => {
        setLoading(true);
        setError('');
        Promise.all([api.frontInfo(), api.myPage<MyPageResponse>()])
            .then(([front, mine]) => {
                setFrontInfo(front);
                setMyPage(mine);
                setSettings(mine.settings);
                setSelectedItemType((current) => {
                    const droppable = mine.items.filter((item) => item.droppable);
                    if (droppable.some((item) => item.type === current)) return current;
                    return droppable[0]?.type ?? '';
                });
            })
            .catch(() => setError('내 정보를 불러올 수 없습니다.'))
            .finally(() => setLoading(false));
    };

    useEffect(fetchData, []);

    if (loading) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 정보&설정</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error || !frontInfo || !myPage || !frontInfo.general.hasGeneral) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 정보&설정</h1>
                    <div className="error-state">
                        <p>{error || '장수 정보가 없습니다.'}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    const generalRows: [string, React.ReactNode][] = [
        ['소속', myPage.nationName ?? '재야'],
        ['현재 도시', myPage.cityName ?? '-'],
        ['통솔', myPage.leadership],
        ['무력', myPage.strength],
        ['지력', myPage.intel],
        ['정치', myPage.politics ?? '-'],
        ['매력', myPage.charm ?? '-'],
        ['부상', `${myPage.injury}%`],
        ['자금', formatNumber(myPage.gold)],
        ['군량', formatNumber(myPage.rice)],
        ['병사', formatNumber(myPage.crew)],
        ['훈련/사기', `${myPage.train} / ${myPage.atmos}`],
    ];

    const updateSetting = (key: keyof MyPageResponse['settings'], value: number) =>
        setSettings((current) => current == null ? current : { ...current, [key]: value });
    const settingsRows: [string, React.ReactNode][] = [
        ['토너먼트', <select key="tnmt" value={settings?.tnmt ?? 1} onChange={(e) => updateSetting('tnmt', Number(e.target.value))}><option value={1}>사용</option><option value={0}>사용 안 함</option></select>],
        ['환약 사용', <input key="treatment" type="number" min={10} max={100} value={settings?.useTreatment ?? 10} onChange={(e) => updateSetting('useTreatment', Number(e.target.value))} />],
        ['자동 사령턴 허용', <select key="auto" value={settings?.useAutoNationTurn ?? 1} onChange={(e) => updateSetting('useAutoNationTurn', Number(e.target.value))}><option value={1}>허용</option><option value={0}>허용 안 함</option></select>],
        ['수비', <span key="defence" style={{ display: 'flex', gap: 'var(--space-xs)', alignItems: 'center' }}><span>{currentDefenceTrain(frontInfo)}</span><input type="number" min={40} value={settings?.defenceTrain ?? 80} onChange={(e) => updateSetting('defenceTrain', Number(e.target.value))} /></span>],
        ['500px/1000px 모드', <select key="width" defaultValue={typeof window === 'undefined' ? '1000' : localStorage.getItem('game-width') ?? '1000'} onChange={(e) => localStorage.setItem('game-width', e.target.value)}><option value="500">500px</option><option value="1000">1000px</option></select>],
        ['개인용 CSS', <input key="css" defaultValue={typeof window === 'undefined' ? '' : localStorage.getItem('game-css') ?? ''} onBlur={(e) => localStorage.setItem('game-css', e.target.value)} />],
    ];

    const droppableItems = myPage.items.filter((item) => item.droppable);
    const selectedDropItem = droppableItems.find((item) => item.type === selectedItemType) ?? droppableItems[0];
    const anyInstantAction =
        droppableItems.length > 0 ||
        myPage.instantActions.instantRetreatPossible ||
        myPage.instantActions.dieOnPrestartPossible;

    const runInstantAction = async (code: InstantActionCode, args?: unknown, confirmMessage?: string) => {
        if (confirmMessage != null && !window.confirm(confirmMessage)) return;
        setSubmittingAction(code);
        setActionMessage('');
        try {
            const out = await submitCommandAndAwaitResult(() => api.instantAction(code, myPage.generalId, args));
            if (out.status === 'applied') {
                setActionMessage('처리되었습니다.');
                fetchData();
            } else {
                setActionMessage(out.reason ?? '처리 지연');
            }
        } catch {
            setActionMessage('요청에 실패했습니다.');
        } finally {
            setSubmittingAction('');
        }
    };

    const dropSelectedItem = (item: MyPageItem | undefined) => {
        if (item == null) return;
        void runInstantAction(
            'DropItem',
            { itemType: item.type },
            `${item.name}을(를) 버리시겠습니까?`,
        );
    };

    const saveSettings = async () => {
        if (settings == null) return;
        const out = await submitCommandAndAwaitResult(() => api.commands.setMySetting({
            tnmt: settings.tnmt,
            defence_train: settings.defenceTrain,
            use_treatment: settings.useTreatment,
            use_auto_nation_turn: settings.useAutoNationTurn,
        }, myPage.generalId));
        setActionMessage(out.status === 'applied' ? '설정을 저장했습니다.' : out.reason ?? '설정을 저장할 수 없습니다.');
        if (out.status === 'applied') fetchData();
    };

    const takeVacation = async () => {
        if (!window.confirm('휴가를 실행하시겠습니까?')) return;
        const out = await submitCommandAndAwaitResult(() => api.commands.vacation(myPage.generalId));
        setActionMessage(out.status === 'applied' ? '휴가를 설정했습니다.' : out.reason ?? '휴가를 설정할 수 없습니다.');
    };

    return (
        <Shell>
            <div className="page-content">
                <h1>내 정보&설정</h1>
                <div
                    style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
                        gap: 'var(--space-md)',
                        alignItems: 'start',
                        marginBottom: 'var(--space-md)',
                    }}
                >
                    <GeneralBasicCard general={frontInfo.general} nation={frontInfo.nation} />
                    <GameCard>
                        <div className="basic-card-name">{myPage.name}</div>
                        <InfoGrid rows={generalRows} />
                    </GameCard>
                    <GameCard>
                        <div className="basic-card-name">설정</div>
                        <InfoGrid rows={settingsRows} />
                        <div style={{ display: 'flex', gap: 'var(--space-xs)', marginTop: 'var(--space-sm)' }}>
                            <button type="button" onClick={() => void saveSettings()}>설정 저장</button>
                            <button type="button" onClick={() => void takeVacation()}>휴가</button>
                        </div>
                    </GameCard>
                    {anyInstantAction ? (
                        <GameCard>
                            <div className="basic-card-name">즉시 실행</div>
                            <div style={{ display: 'grid', gap: 'var(--space-sm)' }}>
                                {droppableItems.length > 0 ? (
                                    <div style={{ display: 'flex', gap: 'var(--space-xs)', flexWrap: 'wrap' }}>
                                        <select
                                            aria-label="버릴 아이템"
                                            value={selectedDropItem?.type ?? ''}
                                            onChange={(event) => setSelectedItemType(event.target.value)}
                                            disabled={submittingAction !== ''}
                                        >
                                            {droppableItems.map((item) => (
                                                <option key={item.type} value={item.type}>
                                                    {item.label}: {item.name}
                                                </option>
                                            ))}
                                        </select>
                                        <button
                                            type="button"
                                            onClick={() => dropSelectedItem(selectedDropItem)}
                                            disabled={selectedDropItem == null || submittingAction !== ''}
                                        >
                                            아이템 버리기
                                        </button>
                                    </div>
                                ) : null}
                                {myPage.instantActions.instantRetreatPossible ? (
                                    <button
                                        type="button"
                                        onClick={() =>
                                            void runInstantAction('InstantRetreat', undefined, '즉시 접경귀환을 실행하시겠습니까?')
                                        }
                                        disabled={submittingAction !== ''}
                                    >
                                        즉시 접경귀환
                                    </button>
                                ) : null}
                                {myPage.instantActions.dieOnPrestartPossible ? (
                                    <button
                                        type="button"
                                        onClick={() =>
                                            void runInstantAction('DieOnPrestart', undefined, '개전 전 장수 삭제를 실행하시겠습니까?')
                                        }
                                        disabled={submittingAction !== ''}
                                    >
                                        개전 전 장수 삭제
                                    </button>
                                ) : null}
                                {actionMessage ? (
                                    <p className="text-muted" role="status">
                                        {actionMessage}
                                    </p>
                                ) : null}
                            </div>
                        </GameCard>
                    ) : null}
                </div>
                <MyInfoLogPanel generalId={myPage.generalId} />
            </div>
        </Shell>
    );
}
