import { Badge } from "@/components/ui/badge";

interface NationBadgeProps {
  name?: string | null;
  color?: string | null;
  size?: "sm" | "md";
}

export function NationBadge({ name, color, size = "sm" }: NationBadgeProps) {
  if (!name) {
    return (
      <Badge variant="outline" className="text-muted-foreground">
        재야
      </Badge>
    );
  }

  const dotSize = size === "sm" ? "size-2" : "size-2.5";

  return (
    <Badge
      variant="outline"
      className="gap-1.5"
      style={color ? { borderColor: color } : undefined}
    >
      <span
        className={`inline-block ${dotSize} rounded-full shrink-0`}
        style={{ backgroundColor: color ?? "#888" }}
      />
      <span style={{ color: color ?? undefined }}>{name}</span>
    </Badge>
  );
}

/** Utility: get a CSS variable-friendly class name for a nation color */
export function getNationColorStyle(color?: string | null): React.CSSProperties {
  if (!color) return {};
  return { "--nation-color": color } as React.CSSProperties;
}
