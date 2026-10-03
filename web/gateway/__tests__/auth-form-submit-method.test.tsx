import { render, waitFor } from '@testing-library/react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

// 로그인 · 가입 폼 제출 방식: 스크립트가 붙기 전 HTML(서버 렌더)은 method="post" 이고 칸 묶음이 네이티브 disabled 다.
// 붙은 뒤에는 칸 묶음이 열리고 제출은 화면 코드(onSubmit)가 맡는다.
vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
    useSearchParams: () => ({ get: () => null }),
}));
vi.mock('@/lib/client', () => ({ login: vi.fn(), register: vi.fn() }));

import LoginForm from '@/components/login/LoginForm';
import JoinForm from '@/components/join/JoinForm';

describe.each([
    ['로그인', LoginForm],
    ['가입', JoinForm],
])('%s 폼 제출 방식', (_name, Form) => {
    it('서버 렌더 HTML: method="post", 칸 묶음(입력 · 제출)이 네이티브 disabled', () => {
        const html = renderToString(<Form />);
        expect(html).toMatch(/<form[^>]*\smethod="post"/);
        const fieldset = /<fieldset[^>]*>/.exec(html)?.[0] ?? '';
        expect(fieldset, '칸 묶음 fieldset').not.toBe('');
        expect(fieldset).toMatch(/\sdisabled=""/);
        // 입력과 제출 단추가 그 묶음 안에 있다(묶음 밖 입력은 잠기지 않는다).
        const inside = html.slice(html.indexOf(fieldset), html.indexOf('</fieldset>'));
        expect(inside).toMatch(/name="password"/);
        expect(inside).toMatch(/type="submit"/);
    });

    it('하이드레이션 뒤에는 칸 묶음이 열린다', async () => {
        const { container } = render(<Form />);
        const fieldset = container.querySelector('fieldset');
        expect(fieldset).not.toBeNull();
        await waitFor(() => expect(fieldset).not.toBeDisabled());
        expect(container.querySelector('form')).toHaveAttribute('method', 'post');
    });
});
