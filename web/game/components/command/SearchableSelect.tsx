'use client';

// SearchableSelect — the shared 초성-search dropdown the city / general / nation field-types render on
// (legacy SelectCity/SelectGeneral/SelectNation are all vue-multiselect with a convertSearch초성 filter).
// A text input filters `options` by matchesQuery(); the list is clickable; an unavailable option is red
// and suffixed `(불가)` exactly like the legacy `notAvailable` rendering. Controlled by `value`/`onChange`.

import { useMemo, useState } from 'react';
import { matchesQuery } from '@/lib/chosung';

export interface SelectOption {
    value: number;
    /** Display label (one line). */
    label: string;
    /** Optional secondary detail rendered in parentheses (legacy `info`). */
    info?: string;
    /** Diplomatically/positionally unavailable → red + `(불가)`, still selectable (legacy parity). */
    notAvailable?: boolean;
    /** Raw name(s) used for 초성/substring matching (defaults to `label`). */
    searchText?: string;
}

export interface SearchableSelectProps {
    options: SelectOption[];
    value: number | null;
    onChange: (value: number) => void;
    placeholder?: string;
    /** Loading the option source (e.g. map preview / generals) — render a spinner row. */
    loading?: boolean;
    /** Source unavailable (backend read missing) — render the message instead of an empty list. */
    emptyText?: string;
}

export default function SearchableSelect({
    options,
    value,
    onChange,
    placeholder = '검색',
    loading = false,
    emptyText = '선택 가능한 항목이 없습니다.',
}: SearchableSelectProps) {
    const [query, setQuery] = useState('');

    const filtered = useMemo(
        () => options.filter((o) => matchesQuery(o.searchText ?? o.label, query)),
        [options, query],
    );

    return (
        <div className="cmd-select">
            <input
                type="text"
                className="cmd-select-input"
                placeholder={placeholder}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
            />
            <div className="cmd-select-list" role="listbox">
                {loading ? (
                    <div className="cmd-select-empty">
                        <span className="spinner" />
                    </div>
                ) : filtered.length === 0 ? (
                    <div className="cmd-select-empty">{options.length === 0 ? emptyText : '일치하는 항목 없음'}</div>
                ) : (
                    filtered.map((o) => (
                        <button
                            key={o.value}
                            type="button"
                            role="option"
                            aria-selected={o.value === value}
                            className={`cmd-select-opt${o.value === value ? ' selected' : ''}`}
                            style={o.notAvailable ? { color: 'red' } : undefined}
                            onClick={() => onChange(o.value)}
                        >
                            {o.label}
                            {o.info ? <span className="cmd-select-info"> ({o.info})</span> : null}
                            {o.notAvailable ? ' (불가)' : ''}
                        </button>
                    ))
                )}
            </div>
        </div>
    );
}
