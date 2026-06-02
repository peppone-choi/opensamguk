'use client';

interface ToastItem {
    id: number;
    message: string;
    type: 'success' | 'error' | 'info';
}

interface ToastProps {
    toasts: ToastItem[];
    onRemove: (id: number) => void;
}

export default function Toast({ toasts, onRemove }: ToastProps) {
    return (
        <div className="toast-container" role="status" aria-live="polite">
            {toasts.map(t => (
                <div key={t.id} className={`toast toast-${t.type}`}>
                    <span>{t.message}</span>
                    <button onClick={() => onRemove(t.id)} aria-label="닫기">×</button>
                </div>
            ))}
        </div>
    );
}
