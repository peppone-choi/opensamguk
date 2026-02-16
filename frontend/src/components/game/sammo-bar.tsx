"use client";

import { getSammoBarBg, getSammoBarFill } from "@/lib/image";

interface SammoBarProps {
  height: 7 | 10;
  percent: number;
  altText?: string;
}

/**
 * Legacy SammoBar parity - uses CDN gif images for progress bars.
 * height: 7 (city stats) or 10 (general stats)
 * percent: 0-100
 */
export function SammoBar({ height, percent, altText }: SammoBarProps) {
  const clampedPercent = Math.max(0, Math.min(100, percent));
  const bgUrl = getSammoBarBg(height);
  const fillUrl = getSammoBarFill(height);

  return (
    <div
      className="relative mx-auto w-full overflow-hidden"
      style={{
        height: height + 2,
        borderTop: "1px solid #888",
        borderBottom: "1px solid #333",
      }}
      title={altText ?? `${Math.round(clampedPercent)}%`}
    >
      <div
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          width: "100%",
          height,
          backgroundImage: `url('${bgUrl}')`,
          backgroundRepeat: "repeat-x",
          backgroundPosition: "center",
          backgroundSize: `auto ${height}px`,
        }}
      />
      <div
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          height,
          width: `${clampedPercent}%`,
          backgroundImage: `url('${fillUrl}')`,
          backgroundRepeat: "repeat-x",
          backgroundPosition: "left center",
          backgroundSize: `auto ${height}px`,
        }}
      />
    </div>
  );
}
