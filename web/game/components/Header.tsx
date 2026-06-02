'use client';

interface HeaderProps {
    onCommand: () => void;
}

export default function Header({ onCommand }: HeaderProps) {
    return (
        <header className="game-header">
            <div className="game-header-left">
                <span className="game-header-brand">opensamguk</span>
                <span className="game-header-turn">184년 1월 · 1순</span>
            </div>
            <div className="game-header-right">
                <button className="game-header-cmd" onClick={onCommand}>명령</button>
                <span className="game-header-res">금: 10,000 · 쌀: 5,000</span>
            </div>
        </header>
    );
}
