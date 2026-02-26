"use client";

import { useEffect, useState, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { commandApi } from "@/lib/gameApi";
import { subscribeWebSocket } from "@/lib/websocket";
import { LoadingState } from "@/components/game/loading-state";
import { CommandArgForm } from "@/components/game/command-arg-form";
import { PageHeader } from "@/components/game/page-header";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { ClipboardList } from "lucide-react";

function ProcessingContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const command = searchParams.get("command");
  const turnListStr = searchParams.get("turnList");
  const isNationCommand = searchParams.get("nation") === "true";
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral } = useGeneralStore();
  const [isSubmitting, setIsSubmitting] = useState(false);

  const isFormMode = Boolean(command && turnListStr);
  const turnList = turnListStr
    ? turnListStr.split(",").map((t) => parseInt(t, 10))
    : [];

  // Wait mode: WS listener (hooks must always run in same order)
  useEffect(() => {
    if (isFormMode || !currentWorld) return;
    const unsub = subscribeWebSocket(
      `/topic/world/${currentWorld.id}/turn`,
      () => {
        router.replace("/");
      },
    );
    return unsub;
  }, [currentWorld, router, isFormMode]);

  // Wait mode: 30s fallback timeout
  useEffect(() => {
    if (isFormMode) return;
    const timer = setTimeout(() => {
      router.replace("/");
    }, 30000);
    return () => clearTimeout(timer);
  }, [router, isFormMode]);

  // Form mode: command argument form
  if (isFormMode && command) {
    const handleFormSubmit = async (arg: Record<string, unknown>) => {
      if (!myGeneral) return;
      setIsSubmitting(true);
      try {
        const turns = turnList.map((turnIdx) => ({
          turnIdx,
          actionCode: command,
          arg,
        }));
        if (isNationCommand && myGeneral.nationId) {
          await commandApi.reserveNation(
            myGeneral.nationId,
            myGeneral.id,
            turns,
          );
          router.push("/commands?mode=nation");
        } else {
          await commandApi.reserve(myGeneral.id, turns);
          router.push("/commands");
        }
      } catch (error) {
        console.error("Failed to reserve command:", error);
        setIsSubmitting(false);
      }
    };

    return (
      <div className="p-4 space-y-4 max-w-lg mx-auto">
        <PageHeader
          icon={ClipboardList}
          title={`명령 인수 입력 — ${command}`}
        />
        <Card>
          <CardContent className="space-y-4 pt-4">
            <div className="space-y-2">
              <p className="text-xs text-muted-foreground">대상 턴</p>
              <div className="flex flex-wrap gap-1.5">
                {turnList.map((turnIdx) => (
                  <span
                    key={turnIdx}
                    className="inline-flex items-center gap-1 rounded-full bg-amber-900/30 px-2.5 py-0.5 text-xs font-medium text-amber-200"
                  >
                    <ClipboardList className="size-3" />턴 {turnIdx}
                  </span>
                ))}
              </div>
            </div>

            <CommandArgForm actionCode={command} onSubmit={handleFormSubmit} />

            <div className="flex gap-2 pt-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => router.push("/commands")}
                disabled={isSubmitting}
                className="flex-1"
              >
                취소
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  // Wait mode: turn processing
  return (
    <div className="flex flex-col items-center justify-center py-24">
      <LoadingState message="턴 처리 중..." />
      <p className="mt-4 text-xs text-muted-foreground">
        턴 실행이 완료되면 자동으로 이동합니다.
      </p>
    </div>
  );
}

export default function ProcessingPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <ProcessingContent />
    </Suspense>
  );
}
