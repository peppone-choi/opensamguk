"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useWorldStore } from "@/stores/worldStore";
import { subscribeWebSocket } from "@/lib/websocket";
import { LoadingState } from "@/components/game/loading-state";

export default function ProcessingPage() {
  const router = useRouter();
  const currentWorld = useWorldStore((s) => s.currentWorld);

  useEffect(() => {
    if (!currentWorld) return;
    const unsub = subscribeWebSocket(
      `/topic/world/${currentWorld.id}/turn`,
      () => {
        router.replace("/");
      },
    );
    return unsub;
  }, [currentWorld, router]);

  // Fallback: poll and redirect after 30s even if WS misses the event
  useEffect(() => {
    const timer = setTimeout(() => {
      router.replace("/");
    }, 30000);
    return () => clearTimeout(timer);
  }, [router]);

  return (
    <div className="flex flex-col items-center justify-center py-24">
      <LoadingState message="턴 처리 중..." />
      <p className="mt-4 text-xs text-muted-foreground">
        턴 실행이 완료되면 자동으로 이동합니다.
      </p>
    </div>
  );
}
