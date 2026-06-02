'use client';

import React from 'react';

interface Props {
    children: React.ReactNode;
}

interface State {
    hasError: boolean;
}

export default class ErrorBoundary extends React.Component<Props, State> {
    constructor(props: Props) {
        super(props);
        this.state = { hasError: false };
    }

    static getDerivedStateFromError() {
        return { hasError: true };
    }

    render() {
        if (this.state.hasError) {
            return (
                <div className="error-boundary">
                    <h2>오류가 발생했습니다</h2>
                    <button onClick={() => window.location.reload()}>새로고침</button>
                </div>
            );
        }
        return this.props.children;
    }
}
