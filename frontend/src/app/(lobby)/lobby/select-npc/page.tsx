"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { generalApi } from "@/lib/gameApi";
import type { General } from "@/types";
import { Bot, ArrowLeft } from "lucide-react";
import { toast } from "sonner";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { StatBar } from "@/components/game/stat-bar";

export default function LobbySelectNpcPage() {
  const router = useRouter();
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { fetchMyGeneral } = useGeneralStore();
  const [npcs, setNpcs] = useState<General[]>([]);
  const [loading, setLoading] = useState(true);
  const [selecting, setSelecting] = useState<number | null>(null);

  useEffect(() => {
    if (!currentWorld) return;
    generalApi
      .listAvailableNpcs(currentWorld.id)
      .then(({ data }) => setNpcs(data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [currentWorld]);

  const handleSelect = async (generalId: number) => {
    if (!currentWorld) return;
    setSelecting(generalId);
    try {
      await generalApi.selectNpc(currentWorld.id, generalId);
      await fetchMyGeneral(currentWorld.id);
      toast.success("NPC 장수를 인수했습니다.");
      router.push("/");
    } catch {
      toast.error("NPC 인수에 실패했습니다.");
    } finally {
      setSelecting(null);
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <Button
        variant="ghost"
        size="sm"
        onClick={() => router.push("/lobby")}
        className="mb-2"
      >
        <ArrowLeft className="size-4 mr-1" /> 로비로 돌아가기
      </Button>

      <PageHeader icon={Bot} title="NPC 인수" />
      <p className="text-sm text-muted-foreground">
        빈 NPC 장수를 인수하여 플레이할 수 있습니다.
      </p>

      {npcs.length === 0 ? (
        <EmptyState icon={Bot} title="인수 가능한 NPC가 없습니다." />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {npcs.map((g) => (
            <Card key={g.id}>
              <CardContent className="space-y-3">
                <div className="flex items-center gap-3">
                  <GeneralPortrait
                    picture={g.picture}
                    name={g.name}
                    size="md"
                  />
                  <div>
                    <p className="font-semibold">{g.name}</p>
                    <Badge variant="secondary" className="text-xs">
                      NPC #{g.npcState}
                    </Badge>
                  </div>
                </div>
                <div className="space-y-1">
                  <StatBar
                    label="통솔"
                    value={g.leadership}
                    color="bg-red-500"
                  />
                  <StatBar
                    label="무력"
                    value={g.strength}
                    color="bg-orange-500"
                  />
                  <StatBar label="지력" value={g.intel} color="bg-blue-500" />
                  <StatBar
                    label="정치"
                    value={g.politics}
                    color="bg-green-500"
                  />
                  <StatBar label="매력" value={g.charm} color="bg-purple-500" />
                </div>
                <div className="flex gap-2 text-xs text-muted-foreground">
                  <span>병력: {g.crew.toLocaleString()}</span>
                  <span>경험: {g.experience}</span>
                </div>
                <Button
                  className="w-full"
                  onClick={() => handleSelect(g.id)}
                  disabled={selecting !== null}
                >
                  {selecting === g.id ? "인수 중..." : "인수하기"}
                </Button>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
