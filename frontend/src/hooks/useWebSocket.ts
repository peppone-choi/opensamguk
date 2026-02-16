import { useCallback, useEffect, useRef, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { connectWebSocket, disconnectWebSocket } from "@/lib/websocket";
import { toast } from "sonner";

export function useWebSocket() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const connectedRef = useRef(false);
  const [enabled, setEnabled] = useState(true);

  useEffect(() => {
    if (!currentWorld || !enabled || connectedRef.current) return;
    connectedRef.current = true;

    connectWebSocket(currentWorld.id, {
      onTurnAdvance: (data) => {
        toast.info(`${data.year}년 ${data.month}월로 진행되었습니다.`);
      },
      onBattle: (data) => {
        toast.warning(`전투 발생: ${data.message || "전투 알림"}`);
      },
      onDiplomacy: (data) => {
        toast.info(`외교: ${data.message || "외교 변화"}`);
      },
      onMessage: () => {
        toast.info(`새 서신이 도착했습니다.`);
      },
    });

    return () => {
      disconnectWebSocket();
      connectedRef.current = false;
    };
  }, [currentWorld, enabled]);

  const toggleRealtime = useCallback(() => {
    setEnabled((prev) => {
      if (prev) disconnectWebSocket();
      connectedRef.current = false;
      return !prev;
    });
  }, []);

  return { enabled, toggleRealtime };
}
