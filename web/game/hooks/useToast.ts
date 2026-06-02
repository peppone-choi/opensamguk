'use client';

import { useState, useCallback } from 'react';

interface Toast {
    id: number;
    message: string;
    type: 'success' | 'error' | 'info';
}

let nextId = 0;

export function useToast() {
    const [toasts, setToasts] = useState<Toast[]>([]);

    const show = useCallback((message: string, type: Toast['type'] = 'info') => {
        const id = ++nextId;
        setToasts(prev => [...prev, { id, message, type }]);
        setTimeout(() => {
            setToasts(prev => prev.filter(t => t.id !== id));
        }, 3000);
    }, []);

    const remove = useCallback((id: number) => {
        setToasts(prev => prev.filter(t => t.id !== id));
    }, []);

    return { toasts, show, remove };
}
