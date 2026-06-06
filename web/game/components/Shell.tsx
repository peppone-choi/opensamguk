'use client';

import { useState, useCallback, useEffect } from 'react';
import Header from './Header';
import BottomNav from './BottomNav';
import Toast from './Toast';
import CommandModal from './CommandModal';
import { useSSE } from '../hooks/useSSE';
import { useToast } from '../hooks/useToast';
import { api } from '../lib/api';
import type { FrontInfoResponse } from '../lib/types';

export default function Shell({ children }: { children: React.ReactNode }) {
    const [commandOpen, setCommandOpen] = useState(false);
    // CommandController requires the caller's generalId — resolve it lazily from front-info when the
    // command FAB opens (the modal cannot reserve without it). nationId scopes the nation picker.
    const [ident, setIdent] = useState<{ generalId: number; nationId: number } | null>(null);
    const { toasts, show, remove } = useToast();

    const refresh = useCallback(() => {
        window.location.reload();
    }, []);

    useSSE(refresh);

    // Resolve identity once the command modal is requested (cheap front-info read, cached after first).
    useEffect(() => {
        if (!commandOpen || ident) return;
        let on = true;
        void api
            .frontInfo()
            .then((fi: FrontInfoResponse) => {
                if (on && fi.general.generalId != null) {
                    setIdent({ generalId: fi.general.generalId, nationId: fi.general.nationId });
                }
            })
            .catch(() => {
                /* no general → modal stays closed-ish; FAB toast below */
            });
        return () => {
            on = false;
        };
    }, [commandOpen, ident]);

    function closeCommand() {
        setCommandOpen(false);
    }

    return (
        <div className="shell">
            <Header onCommand={() => setCommandOpen(true)} />
            {/* 좌측 사이드바 제거(사용자 요청 — 1000px 폭에서 너비 부족). 네비는 Header(상단)+BottomNav(하단)+GameChrome GlobalMenu. */}
            <div className="shell-body">
                <main className="shell-main">{children}</main>
            </div>
            <BottomNav onCommand={() => setCommandOpen(true)} />
            <Toast toasts={toasts} onRemove={remove} />
            {commandOpen && ident && (
                <CommandModal
                    onClose={closeCommand}
                    onToast={show}
                    generalId={ident.generalId}
                    nationId={ident.nationId}
                />
            )}
        </div>
    );
}
