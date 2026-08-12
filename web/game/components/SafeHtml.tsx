'use client';

import DOMPurify from 'dompurify';
import { useEffect, useState } from 'react';

const ALLOWED_TAGS = ['b', 'strong', 'i', 'em', 's', 'u', 'span', 'br', 'p'];
const ALLOWED_ATTRIBUTES = ['style'];
const HTML_TAG = /<\/?[a-zA-Z][^>]*>/;
const COLOR_STYLE = /^\s*color\s*:\s*(#[0-9a-fA-F]{3,8}|[a-zA-Z]{1,20})\s*;?\s*$/;

function escapePlainText(value: string): string {
    return value
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;')
        .replace(/\r?\n/g, '<br>');
}

function retainColorStyleOnly(html: string): string {
    const template = document.createElement('template');
    template.innerHTML = html;
    template.content.querySelectorAll<HTMLElement>('[style]').forEach(element => {
        const match = COLOR_STYLE.exec(element.getAttribute('style') ?? '');
        if (element.tagName !== 'SPAN' || match === null) {
            element.removeAttribute('style');
            return;
        }
        element.setAttribute('style', `color: ${match[1]}`);
    });
    return template.innerHTML;
}

export function sanitizeRichHtml(html: string): string {
    if (typeof window === 'undefined' || typeof DOMPurify.sanitize !== 'function') {
        return escapePlainText(html);
    }
    const sanitized = DOMPurify.sanitize(html, {
        ALLOWED_TAGS,
        ALLOWED_ATTR: ALLOWED_ATTRIBUTES,
        RETURN_TRUSTED_TYPE: false,
    });
    return retainColorStyleOnly(sanitized);
}

export interface SafeHtmlProps {
    readonly html: string | null | undefined;
    readonly className?: string;
}

export function SafeHtml({ html, className }: SafeHtmlProps) {
    const value = html ?? '';
    const [sanitized, setSanitized] = useState(() => escapePlainText(value));

    useEffect(() => {
        setSanitized(HTML_TAG.test(value) ? sanitizeRichHtml(value) : escapePlainText(value));
    }, [value]);

    return <div className={className} dangerouslySetInnerHTML={{ __html: sanitized }} />;
}
