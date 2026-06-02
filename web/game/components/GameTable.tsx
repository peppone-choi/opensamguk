'use client';

interface GameTableProps {
    headers: string[];
    rows: (string | number | React.ReactNode)[][];
}

export default function GameTable({ headers, rows }: GameTableProps) {
    return (
        <div className="game-table-wrap">
            <table className="game-table">
                <thead>
                    <tr>
                        {headers.map((h, i) => <th key={i}>{h}</th>)}
                    </tr>
                </thead>
                <tbody>
                    {rows.map((row, ri) => (
                        <tr key={ri}>
                            {row.map((cell, ci) => <td key={ci}>{cell}</td>)}
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
