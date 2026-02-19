"use client";

import { useEffect } from "react";
import { Swords } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { CommandPanel } from "@/components/game/command-panel";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";

export default function CommandsPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) {
      fetchMyGeneral(currentWorld.id).catch(() => {});
    }
  }, [currentWorld, myGeneral, fetchMyGeneral]);

  if (!currentWorld) {
    return (
      <div className="p-4">
        <EmptyState
          title="월드를 선택해주세요"
          description="명령 예약은 월드 진입 후 이용할 수 있습니다."
        />
      </div>
    );
  }

  if (!myGeneral) {
    return <LoadingState message="명령 정보를 불러오는 중..." />;
  }

  return (
    <div className="p-4 space-y-4 max-w-4xl mx-auto">
      <PageHeader
        icon={Swords}
        title="명령 예약"
        description="12턴 예약, 다중 선택, 저장 액션을 이용해 명령을 빠르게 편성합니다."
      />
      <CommandPanel
        generalId={myGeneral.id}
        realtimeMode={currentWorld.realtimeMode}
      />
    </div>
  );
}
