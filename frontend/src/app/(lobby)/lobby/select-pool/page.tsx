"use client";

import { useEffect, useState, useCallback } from "react";
import { useRouter } from "next/navigation";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { generalApi } from "@/lib/gameApi";
import type { General } from "@/types";
import { Users, ArrowLeft, Pencil, Dices, Settings2 } from "lucide-react";
import { toast } from "sonner";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { StatBar } from "@/components/game/stat-bar";

const STAT_TOTAL = 350;
const STAT_MIN = 10;
const STAT_MAX = 100;
const STAT_KEYS = ["leadership", "strength", "intel", "politics", "charm"] as const;
type StatKey = (typeof STAT_KEYS)[number];
const STAT_LABELS: Record<StatKey, string> = {
  leadership: "통솔",
  strength: "무력",
  intel: "지력",
  politics: "정치",
  charm: "매력",
};
const STAT_COLORS: Record<StatKey, string> = {
  leadership: "bg-red-500",
  strength: "bg-orange-500",
  intel: "bg-blue-500",
  politics: "bg-green-500",
  charm: "bg-purple-500",
};

export default function LobbySelectPoolPage() {
  const router = useRouter();
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { fetchMyGeneral } = useGeneralStore();
  const [pool, setPool] = useState<General[]>([]);
  const [loading, setLoading] = useState(true);
  const [selecting, setSelecting] = useState<number | null>(null);
  const [tab, setTab] = useState<string>("pick");

  // Build custom mode state
  const [customName, setCustomName] = useState("");
  const [customStats, setCustomStats] = useState<Record<StatKey, number>>({
    leadership: 70,
    strength: 70,
    intel: 70,
    politics: 70,
    charm: 70,
  });
  const [building, setBuilding] = useState(false);

  // Update existing mode state
  const [selectedForUpdate, setSelectedForUpdate] = useState<General | null>(null);
  const [updateStats, setUpdateStats] = useState<Record<StatKey, number>>({
    leadership: 70,
    strength: 70,
    intel: 70,
    politics: 70,
    charm: 70,
  });
  const [updating, setUpdating] = useState(false);

  const loadPool = useCallback(async () => {
    if (!currentWorld) return;
    try {
      const { data } = await generalApi.listPool(currentWorld.id);
      setPool(data);
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [currentWorld]);

  useEffect(() => {
    loadPool();
  }, [loadPool]);

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

  const customTotal = STAT_KEYS.reduce((s, k) => s + customStats[k], 0);
  const customRemaining = STAT_TOTAL - customTotal;

  const handleBuildCustom = async () => {
    if (!currentWorld || !customName.trim()) {
      toast.error("장수 이름을 입력해주세요.");
      return;
    }
    if (customRemaining !== 0) {
      toast.error(`능력치 합계가 ${STAT_TOTAL}이어야 합니다.`);
      return;
    }
    setBuilding(true);
    try {
      await generalApi.buildPoolGeneral(currentWorld.id, {
        name: customName.trim(),
        ...customStats,
      });
      toast.success("커스텀 장수가 풀에 등록되었습니다.");
      setCustomName("");
      await loadPool();
    } catch {
      toast.error("커스텀 장수 생성에 실패했습니다.");
    } finally {
      setBuilding(false);
    }
  };

  const selectForUpdate = (g: General) => {
    setSelectedForUpdate(g);
    setUpdateStats({
      leadership: g.leadership,
      strength: g.strength,
      intel: g.intel,
      politics: g.politics,
      charm: g.charm,
    });
  };

  const updateTotal = STAT_KEYS.reduce((s, k) => s + updateStats[k], 0);
  const updateRemaining = STAT_TOTAL - updateTotal;

  const handleUpdate = async () => {
    if (!currentWorld || !selectedForUpdate) return;
    if (updateRemaining !== 0) {
      toast.error(`능력치 합계가 ${STAT_TOTAL}이어야 합니다.`);
      return;
    }
    setUpdating(true);
    try {
      await generalApi.updatePoolGeneral(
        currentWorld.id,
        selectedForUpdate.id,
        updateStats,
      );
      toast.success("장수 능력치가 수정되었습니다.");
      setSelectedForUpdate(null);
      await loadPool();
    } catch {
      toast.error("장수 수정에 실패했습니다.");
    } finally {
      setUpdating(false);
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

      <PageHeader icon={Users} title="장수 선택 (풀)" />

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="pick">
            <Users className="size-3.5 mr-1" />
            기존 장수 선택
          </TabsTrigger>
          <TabsTrigger value="build">
            <Pencil className="size-3.5 mr-1" />
            커스텀 생성
          </TabsTrigger>
          <TabsTrigger value="update">
            <Settings2 className="size-3.5 mr-1" />
            능력치 수정
          </TabsTrigger>
        </TabsList>

        {/* ═══ Pick existing ═══ */}
        <TabsContent value="pick" className="mt-4">
          <p className="text-sm text-muted-foreground mb-3">
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
                      <StatBar label="통솔" value={g.leadership} color="bg-red-500" />
                      <StatBar label="무력" value={g.strength} color="bg-orange-500" />
                      <StatBar label="지력" value={g.intel} color="bg-blue-500" />
                      <StatBar label="정치" value={g.politics} color="bg-green-500" />
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
        </TabsContent>

        {/* ═══ Build custom ═══ */}
        <TabsContent value="build" className="mt-4 space-y-4">
          <p className="text-sm text-muted-foreground">
            능력치를 직접 배분하여 커스텀 장수를 풀에 등록합니다.
          </p>
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">커스텀 장수 생성</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-1">
                <label className="text-sm text-muted-foreground">장수명</label>
                <Input
                  value={customName}
                  onChange={(e) => setCustomName(e.target.value)}
                  placeholder="장수 이름 입력"
                  maxLength={20}
                />
              </div>

              <div className="flex items-center justify-between">
                <span className="text-sm text-muted-foreground">
                  능력치 배분 (합계 {STAT_TOTAL})
                </span>
                <Badge
                  variant={
                    customRemaining === 0
                      ? "default"
                      : customRemaining > 0
                        ? "secondary"
                        : "destructive"
                  }
                  className={customRemaining === 0 ? "bg-green-600" : ""}
                >
                  남은: {customRemaining}
                </Badge>
              </div>

              <div className="flex gap-2 mb-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    const base = Math.floor(STAT_TOTAL / 5);
                    const rem = STAT_TOTAL - base * 5;
                    setCustomStats({
                      leadership: base + (rem > 0 ? 1 : 0),
                      strength: base + (rem > 1 ? 1 : 0),
                      intel: base + (rem > 2 ? 1 : 0),
                      politics: base + (rem > 3 ? 1 : 0),
                      charm: base,
                    });
                  }}
                >
                  균형형
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    const vals = STAT_KEYS.map(() => STAT_MIN + Math.floor(Math.random() * (STAT_MAX - STAT_MIN)));
                    const sum = vals.reduce((a, b) => a + b, 0);
                    const diff = STAT_TOTAL - sum;
                    vals[0] = Math.max(STAT_MIN, Math.min(STAT_MAX, vals[0] + diff));
                    setCustomStats({
                      leadership: vals[0],
                      strength: vals[1],
                      intel: vals[2],
                      politics: vals[3],
                      charm: vals[4],
                    });
                  }}
                >
                  <Dices className="size-3.5 mr-1" />
                  랜덤
                </Button>
              </div>

              {STAT_KEYS.map((key) => (
                <div key={key} className="space-y-1">
                  <div className="flex items-center gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      size="icon-xs"
                      onClick={() =>
                        setCustomStats((prev) => {
                          const next = Math.max(STAT_MIN, prev[key] - 5);
                          return { ...prev, [key]: next };
                        })
                      }
                    >
                      -
                    </Button>
                    <div className="flex-1">
                      <StatBar
                        label={STAT_LABELS[key]}
                        value={customStats[key]}
                        max={STAT_MAX}
                        color={STAT_COLORS[key]}
                      />
                    </div>
                    <Button
                      type="button"
                      variant="outline"
                      size="icon-xs"
                      onClick={() =>
                        setCustomStats((prev) => {
                          const next = Math.min(STAT_MAX, prev[key] + 5);
                          const newTotal = STAT_KEYS.reduce(
                            (s, k) => s + (k === key ? next : prev[k]),
                            0,
                          );
                          if (newTotal > STAT_TOTAL) return prev;
                          return { ...prev, [key]: next };
                        })
                      }
                    >
                      +
                    </Button>
                    <Input
                      type="number"
                      min={STAT_MIN}
                      max={STAT_MAX}
                      value={customStats[key]}
                      onChange={(e) =>
                        setCustomStats((prev) => ({
                          ...prev,
                          [key]: Math.max(
                            STAT_MIN,
                            Math.min(STAT_MAX, Number(e.target.value)),
                          ),
                        }))
                      }
                      className="w-14 text-center"
                    />
                  </div>
                </div>
              ))}

              <Button
                className="w-full"
                onClick={handleBuildCustom}
                disabled={building || customRemaining !== 0 || !customName.trim()}
              >
                {building ? "생성 중..." : "커스텀 장수 등록"}
              </Button>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ═══ Update existing ═══ */}
        <TabsContent value="update" className="mt-4 space-y-4">
          <p className="text-sm text-muted-foreground">
            풀에 있는 기존 장수의 능력치를 수정합니다.
          </p>

          {pool.length === 0 ? (
            <EmptyState icon={Users} title="수정할 장수가 없습니다." />
          ) : !selectedForUpdate ? (
            <div className="grid gap-2 sm:grid-cols-2">
              {pool.map((g) => (
                <Card
                  key={g.id}
                  className="cursor-pointer hover:border-primary/50 transition-colors"
                  onClick={() => selectForUpdate(g)}
                >
                  <CardContent className="flex items-center gap-3">
                    <GeneralPortrait
                      picture={g.picture}
                      name={g.name}
                      size="sm"
                    />
                    <div className="flex-1 min-w-0">
                      <p className="font-semibold text-sm">{g.name}</p>
                      <p className="text-xs text-muted-foreground">
                        통{g.leadership} 무{g.strength} 지{g.intel} 정{g.politics} 매{g.charm}
                      </p>
                    </div>
                    <Settings2 className="size-4 text-muted-foreground" />
                  </CardContent>
                </Card>
              ))}
            </div>
          ) : (
            <Card>
              <CardHeader>
                <CardTitle className="text-sm flex items-center justify-between">
                  <span>{selectedForUpdate.name} 능력치 수정</span>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setSelectedForUpdate(null)}
                  >
                    취소
                  </Button>
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="flex items-center justify-between">
                  <span className="text-sm text-muted-foreground">
                    능력치 배분 (합계 {STAT_TOTAL})
                  </span>
                  <Badge
                    variant={
                      updateRemaining === 0
                        ? "default"
                        : updateRemaining > 0
                          ? "secondary"
                          : "destructive"
                    }
                    className={updateRemaining === 0 ? "bg-green-600" : ""}
                  >
                    남은: {updateRemaining}
                  </Badge>
                </div>

                {STAT_KEYS.map((key) => (
                  <div key={key} className="space-y-1">
                    <div className="flex items-center gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        size="icon-xs"
                        onClick={() =>
                          setUpdateStats((prev) => ({
                            ...prev,
                            [key]: Math.max(STAT_MIN, prev[key] - 5),
                          }))
                        }
                      >
                        -
                      </Button>
                      <div className="flex-1">
                        <StatBar
                          label={STAT_LABELS[key]}
                          value={updateStats[key]}
                          max={STAT_MAX}
                          color={STAT_COLORS[key]}
                        />
                      </div>
                      <Button
                        type="button"
                        variant="outline"
                        size="icon-xs"
                        onClick={() =>
                          setUpdateStats((prev) => {
                            const next = Math.min(STAT_MAX, prev[key] + 5);
                            const total = STAT_KEYS.reduce(
                              (s, k) => s + (k === key ? next : prev[k]),
                              0,
                            );
                            if (total > STAT_TOTAL) return prev;
                            return { ...prev, [key]: next };
                          })
                        }
                      >
                        +
                      </Button>
                      <Input
                        type="number"
                        min={STAT_MIN}
                        max={STAT_MAX}
                        value={updateStats[key]}
                        onChange={(e) =>
                          setUpdateStats((prev) => ({
                            ...prev,
                            [key]: Math.max(
                              STAT_MIN,
                              Math.min(STAT_MAX, Number(e.target.value)),
                            ),
                          }))
                        }
                        className="w-14 text-center"
                      />
                    </div>
                  </div>
                ))}

                <Button
                  className="w-full"
                  onClick={handleUpdate}
                  disabled={updating || updateRemaining !== 0}
                >
                  {updating ? "수정 중..." : "능력치 수정"}
                </Button>
              </CardContent>
            </Card>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}
