'use client';

import { useState, useCallback } from 'react';
import Header from './Header';
import Sidebar from './Sidebar';
import BottomNav from './BottomNav';
import Toast from './Toast';
import CommandModal from './CommandModal';
import { useSSE } from '../hooks/useSSE';
import { useToast } from '../hooks/useToast';

export default function Shell({ children }: { children: React.ReactNode }) {
    const [commandOpen, setCommandOpen] = useState(false);
    const { toasts, show, remove } = useToast();

    const refresh = useCallback(() => {
        window.location.reload();
    }, []);

    useSSE(refresh);

    return (
        <div className="shell">
            <Header onCommand={() => setCommandOpen(true)} />
            <div className="shell-body">
                <Sidebar />
                <main className="shell-main">{children}</main>
            </div>
            <BottomNav onCommand={() => setCommandOpen(true)} />
            <Toast toasts={toasts} onRemove={remove} />
            {commandOpen && <CommandModal onClose={() => setCommandOpen(false)} onToast={show} />}
        </div>
    );
}
