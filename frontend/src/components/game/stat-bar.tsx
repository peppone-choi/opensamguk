import { Progress } from "@/components/ui/progress";

interface StatBarProps {
  label: string;
  value: number;
  max?: number;
  color?: string;
}

export function StatBar({ label, value, max = 100, color }: StatBarProps) {
  const pct = max > 0 ? Math.min(100, (value / max) * 100) : 0;

  return (
    <div className="flex items-center gap-2">
      <span className="w-12 text-sm text-muted-foreground shrink-0">
        {label}
      </span>
      <Progress value={pct} className="flex-1 h-3" indicatorColor={color} />
      <span className="w-10 text-sm text-right tabular-nums">{value}</span>
    </div>
  );
}
