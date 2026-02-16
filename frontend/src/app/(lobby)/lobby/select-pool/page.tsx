"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { generalApi } from "@/lib/gameApi";
import type { General } from "@/types";
import { Users, ArrowLeft } from "lucide-react";
import { toast } from "sonner";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { StatBar } from "@/components/game/stat-bar";

export default function LobbySelectPoolPage() {
  const router = useRouter();
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { fetchMyGeneral } = useGeneralStore();
  const [pool, setPool] = useState<General[]>([]);
  const [loading, setLoading] = useState(true);
  const [selecting, setSelecting] = useState<number | null>(null);

  useEffect(() => {
    if (!currentWorld) return;
    generalApi
      .listPool(currentWorld.id)
      .then(({ data }) => setPool(data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [currentWorld]);

  const handleSelect = async (generalId: number) => {
    if (!currentWorld) return;
    setSelecting(generalId);
    try {
      await generalApi.selectFromPool(currentWorld.id, generalId);
      await fetchMyGeneral(currentWorld.id);
      toast.success("장수를 선택했습니다.");
      router.push("/");
    } catch {
      toast.error("장수 선택에 실패했습니다.");
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
      <Button variant="ghost" size="sm" onClick={() => router.push("/lobby")} className="mb-2">
        <ArrowLeft className="size-4 mr-1" /> 로비로 돌아가기
      </Button>

      <PageHeader icon={Users} title="장수 선택 (풀)" />
      <p className="text-sm text-muted-foreground">
        풀에 등록된 장수 중 하나를 선택하여 플레이할 수 있습니다.
      </p>

      {pool.length === 0 ? (
        <EmptyState icon={Users} title="선택 가능한 장수가 없습니다." />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {pool.map((g) => (
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
                    <p className="text-xs text-muted-foreground">
                      나이 {g.age}세
                    </p>
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
                  {selecting === g.id ? "선택 중..." : "선택하기"}
                </Button>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
