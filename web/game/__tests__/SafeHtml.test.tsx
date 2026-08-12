import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SafeHtml, sanitizeRichHtml } from '@/components/SafeHtml';

describe('SafeHtml', () => {
    it('renders only the allowed formatting and neutralizes executable markup', () => {
        const source = '<p><strong>천하</strong><span style="color: #2e7d32">통일</span><img src=x onerror=alert(1)><script>alert(1)</script></p>';
        const { container } = render(<SafeHtml html={source} />);

        expect(container.querySelector('strong')).toHaveTextContent('천하');
        expect(container.querySelector('[style]')).toHaveAttribute('style', 'color: #2e7d32');
        expect(container.querySelector('img')).toBeNull();
        expect(container.querySelector('script')).toBeNull();
        expect(container.innerHTML).not.toContain('onerror');
        expect(container.innerHTML).not.toContain('alert(');
    });

    it('keeps only a color declaration on spans', () => {
        const sanitized = sanitizeRichHtml('<span style="color: red; background: url(javascript:alert(1))">안전</span>');
        const { container } = render(<SafeHtml html={sanitized} />);

        expect(container.querySelector('span')).not.toHaveAttribute('style');
        expect(container.innerHTML).not.toContain('javascript:');
    });

    it('escapes legacy plain text and retains line breaks', () => {
        const { container } = render(<SafeHtml html={'첫 줄\n둘째 줄 < &'} />);

        expect(container).toHaveTextContent('첫 줄둘째 줄 < &');
        expect(container.querySelectorAll('br')).toHaveLength(1);
        expect(container.innerHTML).toContain('&lt;');
        expect(container.innerHTML).toContain('&amp;');
    });
});
