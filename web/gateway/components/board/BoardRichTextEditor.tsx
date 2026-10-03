'use client';

import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import React, { useEffect } from 'react';

type BoardRichTextEditorProps = {
  readonly ariaLabel: string;
  readonly disabled: boolean;
  readonly onChange: (html: string) => void;
  readonly value: string;
};

export default function BoardRichTextEditor({
  ariaLabel,
  disabled,
  onChange,
  value,
}: BoardRichTextEditorProps): React.ReactElement | null {
  const editor = useEditor({
    immediatelyRender: false,
    extensions: [StarterKit],
    content: value,
    editable: !disabled,
    editorProps: {
      attributes: {
        'aria-label': ariaLabel,
        class: 'gw31-editor__content',
        role: 'textbox',
      },
    },
    onUpdate: ({ editor: nextEditor }) => onChange(nextEditor.getHTML()),
  });

  useEffect(() => {
    editor?.setEditable(!disabled);
  }, [disabled, editor]);

  if (editor === null) return null;

  return (
    <div className="os-panel os-panel--static gw31-editor">
      <div aria-label="서식 도구" className="gw31-editor__toolbar" role="toolbar">
        <button className="os-button os-button--ghost os-button--sm gw31-editor__bold" aria-label="굵게" aria-pressed={editor.isActive('bold')} disabled={disabled} onClick={() => editor.chain().focus().toggleBold().run()} type="button">굵게</button>
        <button className="os-button os-button--ghost os-button--sm gw31-editor__italic" aria-label="기울임" aria-pressed={editor.isActive('italic')} disabled={disabled} onClick={() => editor.chain().focus().toggleItalic().run()} type="button">기울임</button>
        <button className="os-button os-button--ghost os-button--sm gw31-editor__strike" aria-label="취소선" aria-pressed={editor.isActive('strike')} disabled={disabled} onClick={() => editor.chain().focus().toggleStrike().run()} type="button">취소선</button>
      </div>
      <EditorContent editor={editor} />
    </div>
  );
}
