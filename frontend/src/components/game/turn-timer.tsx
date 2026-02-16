"use client";

import { useEffect, useState, useRef } from "react";
import { useWorldStore } from "@/stores/worldStore";

export function TurnTimer() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const [remaining, setRemaining] = useState(0);
  const lastTurnRef = useRef(0);

  // Reset timer when turn advances (year/month changes)
  useEffect(() => {
    lastTurnRef.current = Date.now();
  }, [currentWorld?.currentYear, currentWorld?.currentMonth]);

  useEffect(() => {
    if (!currentWorld?.tickSeconds || currentWorld.tickSeconds <= 0) return;
    const tickMs = currentWorld.tickSeconds * 1000;

    const update = () => {
      const elapsed = Date.now() - lastTurnRef.current;
      const rem = Math.max(0, tickMs - elapsed);
      setRemaining(Math.ceil(rem / 1000));
    };

    update();
    const interval = setInterval(update, 1000);
    return () => clearInterval(interval);
  }, [
    currentWorld?.tickSeconds,
    currentWorld?.currentYear,
    currentWorld?.currentMonth,
  ]);

  if (!currentWorld?.tickSeconds || currentWorld.tickSeconds <= 0) return null;

  const minutes = Math.floor(remaining / 60);
  const seconds = remaining % 60;

  return (
    <span className="text-xs text-muted-foreground tabular-nums">
      {minutes}:{seconds.toString().padStart(2, "0")}
    </span>
  );
}
