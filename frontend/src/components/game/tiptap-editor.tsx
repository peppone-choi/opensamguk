"use client";

import { useCallback, useState } from "react";
import { useEditor, EditorContent, BubbleMenu } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import Underline from "@tiptap/extension-underline";
import TextStyle from "@tiptap/extension-text-style";
import TextAlign from "@tiptap/extension-text-align";
import Color from "@tiptap/extension-color";
import Link from "@tiptap/extension-link";
import Image from "@tiptap/extension-image";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import {
  Undo2,
  Redo2,
  Bold,
  Italic,
  Underline as UnderlineIcon,
  Strikethrough,
  Droplet,
  ImageIcon,
  Minus,
  AlignLeft,
  AlignCenter,
  AlignRight,
} from "lucide-react";

const FONT_SIZES = [
  "8px",
  "10px",
  "12px",
  "14px",
  "18px",
  "22px",
  "28px",
  "36px",
  "48px",
  "72px",
];

interface TipTapEditorProps {
  value: string;
  onChange?: (html: string) => void;
  editable?: boolean;
  onReady?: () => void;
  /** Upload image callback: receives base64 data, returns URL path */
  onUploadImage?: (base64Data: string) => Promise<string>;
  className?: string;
}

/**
 * Rich text editor using TipTap. Ported from legacy TipTap.vue (498 lines).
 * Features: bold, italic, underline, strike, font size, text/bg color,
 * text alignment, image (upload + link), horizontal rule, undo/redo.
 */
export function TipTapEditor({
  value,
  onChange,
  editable = true,
  onReady,
  onUploadImage,
  className,
}: TipTapEditorProps) {
  const [showImageModal, setShowImageModal] = useState(false);
  const [imageLink, setImageLink] = useState("");
  const [imageFile, setImageFile] = useState<File | null>(null);

  const editor = useEditor({
    extensions: [
      StarterKit,
      Underline,
      TextStyle,
      TextAlign.configure({ types: ["heading", "paragraph"] }),
      Color.configure({ types: ["textStyle"] }),
      Image.configure({ inline: true }),
      Link,
    ],
    content: value,
    editable,
    onUpdate: ({ editor }) => {
      onChange?.(editor.getHTML());
    },
    onCreate: () => {
      onReady?.();
    },
  });

  const colorConvert = useCallback(
    (val: string | undefined, defaultVal: string) => {
      if (!val) return defaultVal;
      if (val.startsWith("rgb")) {
        const parts = val.split("(")[1].split(")")[0].split(",");
        const hex = parts
          .map((p) => {
            const h = parseInt(p.trim()).toString(16);
            return h.length === 1 ? "0" + h : h;
          })
          .join("");
        return `#${hex}`;
      }
      return val;
    },
    [],
  );

  const handleAddImage = useCallback(async () => {
    if (!editor) return;

    if (imageFile && onUploadImage) {
      try {
        const reader = new FileReader();
        const base64 = await new Promise<string>((resolve, reject) => {
          reader.onload = () => resolve(reader.result as string);
          reader.onerror = reject;
          reader.readAsDataURL(imageFile);
        });
        const path = await onUploadImage(base64);
        editor.chain().focus().setImage({ src: path }).run();
      } catch (e) {
        console.error("Image upload failed:", e);
        alert("이미지 업로드에 실패했습니다.");
        return;
      }
    } else if (imageLink) {
      editor.chain().focus().setImage({ src: imageLink }).run();
    } else {
      alert("업로드할 이미지를 선택하거나, 이미지 주소를 입력해주세요.");
      return;
    }

    setShowImageModal(false);
    setImageLink("");
    setImageFile(null);
  }, [editor, imageFile, imageLink, onUploadImage]);

  if (!editor) return null;

  const ToolBtn = ({
    tooltip,
    onClick,
    active,
    children,
  }: {
    tooltip: string;
    onClick: () => void;
    active?: boolean;
    children: React.ReactNode;
  }) => (
    <TooltipProvider delayDuration={300}>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            type="button"
            variant={active ? "secondary" : "ghost"}
            size="icon"
            className="h-7 w-7"
            onClick={onClick}
          >
            {children}
          </Button>
        </TooltipTrigger>
        <TooltipContent side="bottom">{tooltip}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );

  return (
    <div className={className}>
      {editable && (
        <div className="flex flex-wrap items-center gap-0.5 p-1 bg-muted rounded-t-md border border-b-0 border-border">
          {/* Undo / Redo */}
          <ToolBtn tooltip="되돌리기" onClick={() => editor.commands.undo()}>
            <Undo2 className="h-4 w-4" />
          </ToolBtn>
          <ToolBtn tooltip="재실행" onClick={() => editor.commands.redo()}>
            <Redo2 className="h-4 w-4" />
          </ToolBtn>

          <div className="w-px h-5 bg-border mx-1" />

          {/* Formatting */}
          <ToolBtn
            tooltip="진하게"
            active={editor.isActive("bold")}
            onClick={() => editor.chain().focus().toggleBold().run()}
          >
            <Bold className="h-4 w-4" />
          </ToolBtn>
          <ToolBtn
            tooltip="기울이기"
            active={editor.isActive("italic")}
            onClick={() => editor.chain().focus().toggleItalic().run()}
          >
            <Italic className="h-4 w-4" />
          </ToolBtn>
          <ToolBtn
            tooltip="밑줄"
            active={editor.isActive("underline")}
            onClick={() => editor.chain().focus().toggleUnderline().run()}
          >
            <UnderlineIcon className="h-4 w-4" />
          </ToolBtn>
          <ToolBtn
            tooltip="가로선"
            active={editor.isActive("strike")}
            onClick={() => editor.chain().focus().toggleStrike().run()}
          >
            <Strikethrough className="h-4 w-4" />
          </ToolBtn>

          <div className="w-px h-5 bg-border mx-1" />

          {/* Font size dropdown */}
          <select
            className="h-7 text-xs bg-background border border-input rounded px-1"
            onChange={(e) => {
              const size = e.target.value;
              if (size === "") {
                editor.chain().focus().unsetAllMarks().run();
              } else {
                editor
                  .chain()
                  .focus()
                  .setMark("textStyle", { fontSize: size })
                  .run();
              }
            }}
            defaultValue=""
          >
            <option value="">크기</option>
            {FONT_SIZES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>

          <div className="w-px h-5 bg-border mx-1" />

          {/* Colors */}
          <ToolBtn
            tooltip="색상 취소"
            onClick={() => editor.chain().focus().unsetColor().run()}
          >
            <Droplet className="h-4 w-4" />
          </ToolBtn>
          <input
            type="color"
            title="글자색"
            className="h-7 w-7 p-0 border-0 rounded cursor-pointer"
            value={colorConvert(
              editor.getAttributes("textStyle").color as string | undefined,
              "#ffffff",
            )}
            onChange={(e) =>
              editor.chain().focus().setColor(e.target.value).run()
            }
          />

          <div className="w-px h-5 bg-border mx-1" />

          {/* Image & HR */}
          <ToolBtn
            tooltip="이미지 추가"
            onClick={() => setShowImageModal(true)}
          >
            <ImageIcon className="h-4 w-4" />
          </ToolBtn>
          <ToolBtn
            tooltip="구분선"
            onClick={() => editor.chain().focus().setHorizontalRule().run()}
          >
            <Minus className="h-4 w-4" />
          </ToolBtn>

          <div className="w-px h-5 bg-border mx-1" />

          {/* Alignment */}
          <ToolBtn
            tooltip="왼쪽 정렬"
            active={editor.isActive({ textAlign: "left" })}
            onClick={() => editor.chain().focus().setTextAlign("left").run()}
          >
            <AlignLeft className="h-4 w-4" />
          </ToolBtn>
          <ToolBtn
            tooltip="가운데 정렬"
            active={editor.isActive({ textAlign: "center" })}
            onClick={() => editor.chain().focus().setTextAlign("center").run()}
          >
            <AlignCenter className="h-4 w-4" />
          </ToolBtn>
          <ToolBtn
            tooltip="오른쪽 정렬"
            active={editor.isActive({ textAlign: "right" })}
            onClick={() => editor.chain().focus().setTextAlign("right").run()}
          >
            <AlignRight className="h-4 w-4" />
          </ToolBtn>
        </div>
      )}

      <EditorContent
        editor={editor}
        className="prose prose-invert max-w-none p-3 border border-border rounded-b-md min-h-[200px] focus-within:ring-1 focus-within:ring-ring"
      />

      {/* Image Modal */}
      {showImageModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="bg-background border border-border rounded-lg p-6 w-full max-w-md space-y-4">
            <h3 className="text-lg font-medium">이미지 추가</h3>
            <div className="space-y-2">
              <label className="text-sm text-muted-foreground">
                이미지 업로드
              </label>
              <input
                type="file"
                accept=".jpg,.jpeg,.png,.gif,.webp,.avif"
                className="w-full text-sm"
                onChange={(e) => setImageFile(e.target.files?.[0] ?? null)}
              />
              <p className="text-xs text-muted-foreground">
                jpg, png, gif, webp, avif
              </p>
            </div>
            <div className="space-y-2">
              <label className="text-sm text-muted-foreground">
                이미지 링크
              </label>
              <Input
                value={imageLink}
                onChange={(e) => setImageLink(e.target.value)}
                placeholder="https://..."
              />
            </div>
            <div className="flex justify-end gap-2">
              <Button
                variant="ghost"
                onClick={() => {
                  setShowImageModal(false);
                  setImageLink("");
                  setImageFile(null);
                }}
              >
                취소
              </Button>
              <Button onClick={handleAddImage}>추가</Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
