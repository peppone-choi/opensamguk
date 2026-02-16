import { Badge } from "@/components/ui/badge";

interface NationBadgeProps {
  name?: string | null;
  color?: string | null;
}

export function NationBadge({ name, color }: NationBadgeProps) {
  if (!name) {
    return (
      <Badge variant="outline" className="text-muted-foreground">
        재야
      </Badge>
    );
  }

  return (
    <Badge variant="outline" className="gap-1.5">
      <span
        className="inline-block size-2 rounded-full shrink-0"
        style={{ backgroundColor: color ?? "#888" }}
      />
      <span style={{ color: color ?? undefined }}>{name}</span>
    </Badge>
  );
}
