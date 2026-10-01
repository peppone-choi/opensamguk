import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import PrivacyPage from '@/app/privacy/page';
import TermsPage from '@/app/terms/page';

describe('P-G10 · P-G11 정책 문서', () => {
    it.each([
        ['개인정보처리방침', PrivacyPage, '모으는 정보'],
        ['이용약관', TermsPage, '금지 행동'],
    ])('%s — 승인 전에는 본문을 지어내지 않고 「준비 중」과 다룰 절만', (title, Page, section) => {
        render(<Page />);
        expect(screen.getByRole('heading', { level: 1, name: title })).toBeInTheDocument();
        expect(screen.getByRole('status')).toHaveAttribute('data-copy-status', 'pending');
        expect(screen.getByText(section)).toBeInTheDocument();
        expect(screen.getByRole('link', { name: '회원가입' })).toHaveAttribute('href', '/join');
        expect(screen.getAllByAltText('오픈삼국')).toHaveLength(1);
    });
});
