import { Coins, Wheat, Swords } from "lucide-react";

interface ResourceDisplayProps {
  gold: number;
  rice: number;
  crew: number;
}

export function ResourceDisplay({ gold, rice, crew }: ResourceDisplayProps) {
  return (
    <div className="flex items-center gap-3 text-xs text-muted-foreground">
      <span className="flex items-center gap-1">
        <Coins className="size-3.5 text-yellow-400" />
        <span className="text-yellow-400 tabular-nums">
          {gold.toLocaleString()}
        </span>
      </span>
      <span className="flex items-center gap-1">
        <Wheat className="size-3.5 text-green-400" />
        <span className="text-green-400 tabular-nums">
          {rice.toLocaleString()}
        </span>
      </span>
      <span className="flex items-center gap-1">
        <Swords className="size-3.5 text-blue-400" />
        <span className="text-blue-400 tabular-nums">
          {crew.toLocaleString()}
        </span>
      </span>
    </div>
  );
}
