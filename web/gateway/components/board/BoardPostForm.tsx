'use client';

import React, { useState } from 'react';
import BoardRichTextEditor from '@/components/board/BoardRichTextEditor';
import { BOARD_CATEGORIES, type BoardCategory } from '@/lib/board';

const MAX_CONTENT_LENGTH = 10000;

function richTextIsBlank(html: string): boolean {
  return html
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;|&#160;|&#xA0;/gi, ' ')
    .trim() === '';
}

export default function BoardPostForm({
  allowNotice,
  onSubmit,
}: {
  readonly allowNotice: boolean;
  readonly onSubmit: (input: { readonly category: BoardCategory; readonly title: string; readonly content: string }) => Promise<void>;
}) {
  const [category, setCategory] = useState<BoardCategory>('FREE');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    const nextTitle = title.trim();
    const nextContent = content.trim();
    if (nextTitle === '' || richTextIsBlank(nextContent)) {
      setError('제목과 내용을 모두 입력해주세요.');
      return;
    }
    if (nextContent.length > MAX_CONTENT_LENGTH) {
      setError(`내용은 ${MAX_CONTENT_LENGTH}자 이내로 입력해주세요.`);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onSubmit({ category, title: nextTitle, content: nextContent });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '게시글을 등록하지 못했습니다.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="board-compose-form" onSubmit={(event) => void submit(event)}>
      <div className="field">
        <label htmlFor="board-category">분류</label>
        <select
          id="board-category"
          onChange={(event) => {
            const next = BOARD_CATEGORIES.find((item) => item.value === event.target.value);
            if (next) setCategory(next.value);
          }}
          value={category}
        >
          {BOARD_CATEGORIES.filter((item) => allowNotice || item.value !== 'NOTICE').map((item) => (
            <option key={item.value} value={item.value}>{item.label}</option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="board-title">제목</label>
        <input id="board-title" maxLength={120} onChange={(event) => setTitle(event.target.value)} value={title} />
      </div>
      <div className="field">
        <span className="field-label">내용</span>
        <BoardRichTextEditor ariaLabel="내용" disabled={busy} onChange={setContent} value={content} />
        <span className="board-rich-editor-counter" aria-live="polite">{content.length} / {MAX_CONTENT_LENGTH}</span>
      </div>
      {error ? <p className="field-error" role="alert">{error}</p> : null}
      <div className="board-compose-actions">
        <button className="btn-primary" disabled={busy} type="submit">{busy ? '등록 중…' : '등록'}</button>
      </div>
    </form>
  );
}
