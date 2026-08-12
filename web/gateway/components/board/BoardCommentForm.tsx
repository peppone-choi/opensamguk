'use client';

import React, { useState } from 'react';

export default function BoardCommentForm({
  disabled,
  onSubmit,
}: {
  readonly disabled: boolean;
  readonly onSubmit: (content: string) => Promise<void>;
}) {
  const [content, setContent] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    const trimmed = content.trim();
    if (trimmed === '') {
      setError('댓글 내용을 입력해주세요.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onSubmit(trimmed);
      setContent('');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '댓글을 등록하지 못했습니다.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="board-comment-form" onSubmit={(event) => void submit(event)}>
      <label htmlFor="board-comment">댓글</label>
      <textarea
        disabled={disabled || busy}
        id="board-comment"
        maxLength={2000}
        onChange={(event) => setContent(event.target.value)}
        placeholder="댓글을 남겨보세요."
        rows={3}
        value={content}
      />
      {error ? <p className="field-error" role="alert">{error}</p> : null}
      <button className="btn-primary" disabled={disabled || busy} type="submit">
        {busy ? '등록 중…' : '댓글 등록'}
      </button>
    </form>
  );
}
