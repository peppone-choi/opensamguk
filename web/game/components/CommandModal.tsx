'use client';

import { useState } from 'react';
import { api } from '../lib/api';
import type { GameCommand } from '../types/game';

interface CommandModalProps {
    onClose: () => void;
    onToast: (msg: string, type: 'success' | 'error' | 'info') => void;
}

const COMMANDS: GameCommand[] = [
    { code: 'recruit', name: '징병', category: '내정', args: [{ name: 'amount', type: 'number', label: '병력', required: true, min: 1 }] },
    { code: 'train', name: '훈련', category: '내정', args: [] },
    { code: 'tax', name: '징수', category: '내정', args: [] },
    { code: 'develop', name: '개발', category: '내정', args: [{ name: 'type', type: 'select', label: '종류', required: true, options: [{ value: 'agri', label: '농업' }, { value: 'comm', label: '상업' }, { value: 'secu', label: '치안' }, { value: 'def', label: '수비' }] }] },
    { code: 'war', name: '출정', category: '군사', args: [{ name: 'targetCityId', type: 'number', label: '목표 도시', required: true }] },
    { code: 'defend', name: '수비', category: '군사', args: [] },
    { code: 'declare_war', name: '선전포고', category: '외교', args: [{ name: 'targetNationId', type: 'number', label: '목표 국가', required: true }] },
    { code: 'propose_peace', name: '종전제의', category: '외교', args: [{ name: 'targetNationId', type: 'number', label: '목표 국가', required: true }] },
    { code: 'propose_neutral', name: '불가침제의', category: '외교', args: [{ name: 'targetNationId', type: 'number', label: '목표 국가', required: true }] },
    { code: 'break_neutral', name: '불가침파기', category: '외교', args: [{ name: 'targetNationId', type: 'number', label: '목표 국가', required: true }] },
];

const CATEGORIES = ['내정', '군사', '외교', '기타'];

export default function CommandModal({ onClose, onToast }: CommandModalProps) {
    const [selected, setSelected] = useState<GameCommand | null>(null);
    const [args, setArgs] = useState<Record<string, string>>({});
    const [loading, setLoading] = useState(false);
    const [cat, setCat] = useState('내정');

    const filtered = COMMANDS.filter(c => c.category === cat);

    async function submit() {
        if (!selected) return;
        setLoading(true);
        try {
            const body: Record<string, unknown> = {};
            for (const a of selected.args) {
                const v = args[a.name];
                if (a.required && !v) throw new Error(`${a.label} 필수`);
                body[a.name] = a.type === 'number' ? Number(v) : v;
            }
            await api.command(selected.code, body);
            onToast(`${selected.name} 명령이 접수되었습니다.`, 'success');
            onClose();
        } catch (e) {
            onToast(e instanceof Error ? e.message : '명령 실패', 'error');
        } finally {
            setLoading(false);
        }
    }

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div className="modal-content" onClick={e => e.stopPropagation()}>
                <div className="modal-header">
                    <h2>명령</h2>
                    <button onClick={onClose} aria-label="닫기">×</button>
                </div>

                {!selected ? (
                    <>
                        <div className="cmd-cats">
                            {CATEGORIES.map(c => (
                                <button key={c} className={cat === c ? 'active' : ''} onClick={() => setCat(c)}>{c}</button>
                            ))}
                        </div>
                        <div className="cmd-grid">
                            {filtered.map(cmd => (
                                <button key={cmd.code} className="cmd-item" onClick={() => { setSelected(cmd); setArgs({}); }}>
                                    {cmd.name}
                                </button>
                            ))}
                        </div>
                    </>
                ) : (
                    <>
                        <button className="cmd-back" onClick={() => setSelected(null)}>← 뒤로</button>
                        <h3>{selected.name}</h3>
                        <div className="cmd-form">
                            {selected.args.map(arg => (
                                <label key={arg.name}>
                                    <span>{arg.label}{arg.required && ' *'}</span>
                                    {arg.type === 'select' ? (
                                        <select value={args[arg.name] ?? ''} onChange={e => setArgs(p => ({ ...p, [arg.name]: e.target.value }))}>
                                            <option value="">선택</option>
                                            {arg.options?.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                                        </select>
                                    ) : (
                                        <input
                                            type={arg.type}
                                            value={args[arg.name] ?? ''}
                                            onChange={e => setArgs(p => ({ ...p, [arg.name]: e.target.value }))}
                                            min={arg.min}
                                            max={arg.max}
                                        />
                                    )}
                                </label>
                            ))}
                            <button className="cmd-submit" onClick={submit} disabled={loading}>
                                {loading ? '처리 중...' : '실행'}
                            </button>
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}
