'use client';

import Color from '@tiptap/extension-color';
import { TextStyle } from '@tiptap/extension-text-style';
import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { useEffect, useMemo, useState } from 'react';

export interface RichTextEditorProps {
    readonly value: string;
    readonly onChange: (html: string) => void;
    readonly maxTextLength: number;
    readonly ariaLabel: string;
    readonly disabled?: boolean;
}

export function countHtmlCodePoints(html: string): number {
    return Array.from(html).length;
}

export function RichTextEditor({
    value,
    onChange,
    maxTextLength,
    ariaLabel,
    disabled = false,
}: RichTextEditorProps) {
    const [html, setHtml] = useState(value);
    const extensions = useMemo(
        () => [
            StarterKit.configure({
                blockquote: false,
                bulletList: false,
                code: false,
                codeBlock: false,
                heading: false,
                horizontalRule: false,
                link: false,
                listItem: false,
                orderedList: false,
                underline: false,
            }),
            TextStyle,
            Color,
        ],
        [],
    );
    const editor = useEditor({
        immediatelyRender: false,
        extensions,
        content: value,
        editable: !disabled,
        editorProps: {
            attributes: {
                'aria-label': ariaLabel,
                class: 'rich-text-editor__content',
                role: 'textbox',
            },
        },
        onUpdate: ({ editor: nextEditor }) => {
            const nextHtml = nextEditor.getHTML();
            setHtml(nextHtml);
            onChange(nextHtml);
        },
    });

    useEffect(() => {
        if (editor === null || editor.getHTML() === value) return;
        editor.commands.setContent(value, { emitUpdate: false });
        setHtml(value);
    }, [editor, value]);

    useEffect(() => {
        editor?.setEditable(!disabled);
    }, [disabled, editor]);

    const length = countHtmlCodePoints(html);

    if (editor === null) return null;

    return (
        <div className="rich-text-editor">
            <div className="rich-text-editor__toolbar" aria-label="서식 도구">
                <button
                    aria-label="굵게"
                    aria-pressed={editor.isActive('bold')}
                    disabled={disabled}
                    onClick={() => editor.chain().focus().toggleBold().run()}
                    type="button"
                >
                    굵게
                </button>
                <button
                    aria-label="기울임"
                    aria-pressed={editor.isActive('italic')}
                    disabled={disabled}
                    onClick={() => editor.chain().focus().toggleItalic().run()}
                    type="button"
                >
                    기울임
                </button>
                <button
                    aria-label="취소선"
                    aria-pressed={editor.isActive('strike')}
                    disabled={disabled}
                    onClick={() => editor.chain().focus().toggleStrike().run()}
                    type="button"
                >
                    취소선
                </button>
                <label className="rich-text-editor__color">
                    <span className="sr-only">글자색</span>
                    <input
                        aria-label="글자색"
                        disabled={disabled}
                        onChange={event => editor.chain().focus().setColor(event.target.value).run()}
                        type="color"
                        value={editor.getAttributes('textStyle').color ?? '#f0f0f0'}
                    />
                </label>
            </div>
            <EditorContent editor={editor} />
            <p className="rich-text-editor__counter" aria-live="polite">
                {length} / {maxTextLength}
            </p>
        </div>
    );
}
