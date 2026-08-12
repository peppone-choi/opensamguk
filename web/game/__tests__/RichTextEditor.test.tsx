import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RichTextEditor, countHtmlCodePoints } from '@/components/RichTextEditor';

describe('RichTextEditor', () => {
    it('counts the submitted HTML in Unicode code points, matching PHP mb_strlen semantics', async () => {
        const value = '<p>한😀</p>';

        render(
            <RichTextEditor
                ariaLabel="국가 방침"
                maxTextLength={20}
                onChange={vi.fn()}
                value={value}
            />,
        );

        expect(value).toHaveLength(10);
        expect(countHtmlCodePoints(value)).toBe(9);
        expect(await screen.findByText('9 / 20')).toBeInTheDocument();
    });

    it('uses the native contenteditable editor with only the minimum formatting controls', async () => {
        render(
            <RichTextEditor
                ariaLabel="임관 권유문"
                maxTextLength={1000}
                onChange={vi.fn()}
                value="<p>함께 천하를</p>"
            />,
        );

        expect(await screen.findByRole('textbox', { name: '임관 권유문' })).toHaveAttribute('contenteditable', 'true');
        expect(screen.getByRole('button', { name: '굵게' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '기울임' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '취소선' })).toBeInTheDocument();
        expect(screen.getByLabelText('글자색')).toHaveAttribute('type', 'color');
    });
});
