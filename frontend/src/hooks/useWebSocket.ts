"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { connectWebSocket, disconnectWebSocket } from "@/lib/websocket";
import { toast } from "sonner";
import { playSoundEffect } from "@/hooks/useSoundEffects";

export function useWebSocket() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const fetchWorld = useWorldStore((s) => s.fetchWorld);
  const fetchMyGeneral = useGeneralStore((s) => s.fetchMyGeneral);
  const connectedRef = useRef(false);
  const [enabled, setEnabled] = useState(true);

  useEffect(() => {
    if (!currentWorld || !enabled || connectedRef.current) return;
    connectedRef.current = true;

    connectWebSocket(currentWorld.id, {
      onTurnAdvance: (data) => {
        toast.info(`${data.year}년 ${data.month}월로 진행되었습니다.`, {
          duration: 5000,
          id: "turn-advance",
        });
        playSoundEffect("turnComplete");
        // Refresh world and general state after turn advance
        fetchWorld(currentWorld.id).catch(() => {});
        fetchMyGeneral(currentWorld.id).catch(() => {});
      },
      onBattle: (data) => {
        toast.warning(`⚔️ 전투 발생: ${data.message || "전투 알림"}`, {
          duration: 8000,
        });
        playSoundEffect("battleStart");
      },
      onDiplomacy: (data) => {
        toast.info(`🏛️ 외교: ${data.message || "외교 변화"}`, {
          duration: 6000,
        });
        playSoundEffect("notification");
      },
      onMessage: () => {
        toast.info(`📨 새 서신이 도착했습니다.`, {
          duration: 5000,
        });
        playSoundEffect("newMessage");
      },
    });

    return () => {
      disconnectWebSocket();
      connectedRef.current = false;
    };
  }, [currentWorld, enabled, fetchWorld, fetchMyGeneral]);

  const toggleRealtime = useCallback(() => {
    setEnabled((prev) => {
      if (prev) disconnectWebSocket();
      connectedRef.current = false;
      return !prev;
    });
  }, []);

  return { enabled, toggleRealtime };
}
