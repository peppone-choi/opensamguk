'use client';

import { Table } from '@opensamguk/ui';
import type { ReactNode } from 'react';

interface GameTableProps {
    headers: string[];
    rows: (string | number | ReactNode)[][];
}

export default function GameTable({ headers, rows }: GameTableProps) {
    return <Table className="game-table" wrapperClassName="game-table-wrap" headers={headers} rows={rows} />;
}
