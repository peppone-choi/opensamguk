import { createElement, type ReactNode } from "react";

const COLOR_MAP: Record<string, string> = {
  R: "#ef4444",
  B: "#3b82f6",
  G: "#22c55e",
  M: "#a855f7",
  C: "#06b6d4",
  L: "#84cc16",
  S: "#94a3b8",
  O: "#f97316",
  D: "#6b7280",
  Y: "#eab308",
  W: "#f8fafc",
};

export function formatLog(text: string): ReactNode[] {
  const parts: ReactNode[] = [];
  const tagRegex = /<([RBGMCLSODYW1\/])>/g;
  let lastIndex = 0;
  let currentColor: string | null = null;
  let small = false;
  let key = 0;

  let match;
  while ((match = tagRegex.exec(text)) !== null) {
    if (match.index > lastIndex) {
      const segment = text.slice(lastIndex, match.index);
      if (currentColor || small) {
        const style: Record<string, string> = {};
        if (currentColor) style.color = currentColor;
        if (small) style.fontSize = "0.9em";
        parts.push(createElement("span", { key: key++, style }, segment));
      } else {
        parts.push(segment);
      }
    }

    const tag = match[1];
    if (tag === "/") {
      currentColor = null;
      small = false;
    } else if (tag === "1") {
      small = true;
    } else if (COLOR_MAP[tag]) {
      currentColor = COLOR_MAP[tag];
    }

    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < text.length) {
    const segment = text.slice(lastIndex);
    if (currentColor || small) {
      const style: Record<string, string> = {};
      if (currentColor) style.color = currentColor;
      if (small) style.fontSize = "0.9em";
      parts.push(createElement("span", { key: key++, style }, segment));
    } else {
      parts.push(segment);
    }
  }

  return parts;
}

export function formatLogHtml(text: string): string {
  return text
    .replace(/<R>/g, '<span style="color:#ef4444">')
    .replace(/<B>/g, '<span style="color:#3b82f6">')
    .replace(/<G>/g, '<span style="color:#22c55e">')
    .replace(/<M>/g, '<span style="color:#a855f7">')
    .replace(/<C>/g, '<span style="color:#06b6d4">')
    .replace(/<L>/g, '<span style="color:#84cc16">')
    .replace(/<S>/g, '<span style="color:#94a3b8">')
    .replace(/<O>/g, '<span style="color:#f97316">')
    .replace(/<D>/g, '<span style="color:#6b7280">')
    .replace(/<Y>/g, '<span style="color:#eab308">')
    .replace(/<W>/g, '<span style="color:#f8fafc">')
    .replace(/<\/>/g, "</span>")
    .replace(/<1>/g, '<span style="font-size:0.9em">');
}
