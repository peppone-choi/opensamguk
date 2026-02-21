"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  Plus,
  Globe,
  UserPlus,
  Users,
  Bot,
  LogIn,
  Loader2,
  Trash2,
  RotateCcw,
} from "lucide-react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useAuthStore } from "@/stores/authStore";
import { scenarioApi } from "@/lib/gameApi";
import type { Scenario } from "@/types";
import { toast } from "sonner";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { StatBar } from "@/components/game/stat-bar";

export default function LobbyPage() {
  const router = useRouter();
  const {
    worlds,
    currentWorld,
    loading: worldsLoading,
    fetchWorlds,
    setCurrentWorld,
    createWorld,
    deleteWorld,
    resetWorld,
  } = useWorldStore();
  const {
    myGeneral,
    loading: generalLoading,
    fetchMyGeneral,
    clearMyGeneral,
  } = useGeneralStore();

  const user = useAuthStore((s) => s.user);
  const isAdmin = user?.role === "ADMIN";

  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [selectedScenario, setSelectedScenario] = useState("");
  const [worldName, setWorldName] = useState("");
  const [creating, setCreating] = useState(false);
  const [resetTarget, setResetTarget] = useState<{
    id: number;
    name: string;
  } | null>(null);
  const [resetScenario, setResetScenario] = useState("");
  const [resetting, setResetting] = useState(false);
  const scenarioMap = new Map(scenarios.map((s) => [s.code, s.title]));

  useEffect(() => {
    fetchWorlds();
    scenarioApi
      .list()
      .then(({ data }) => {
        setScenarios(data);
        if (data.length > 0) setSelectedScenario(data[0].code);
      })
      .catch(() => {});
  }, [fetchWorlds]);

  const handleSelectWorld = (world: typeof currentWorld & {}) => {
    clearMyGeneral();
    setCurrentWorld(world);
    fetchMyGeneral(world.id);
  };

  const handleCreateWorld = async () => {
    if (!selectedScenario) return;
    setCreating(true);
    try {
      const world = await createWorld({
        scenarioCode: selectedScenario,
        name: worldName.trim(),
      });
      setCurrentWorld(world);
      clearMyGeneral();
      toast.success("월드가 생성되었습니다.");
    } catch {
      toast.error("월드 생성에 실패했습니다.");
    } finally {
      setCreating(false);
    }
  };

  const handleDeleteWorld = async (e: React.MouseEvent, worldId: number) => {
    e.stopPropagation();
    if (!confirm("정말로 이 월드를 삭제하시겠습니까?")) return;
    try {
      await deleteWorld(worldId);
      toast.success("월드가 삭제되었습니다.");
    } catch {
      toast.error("월드 삭제에 실패했습니다.");
    }
  };

  const handleOpenReset = (
    e: React.MouseEvent,
    worldId: number,
    worldName: string,
  ) => {
    e.stopPropagation();
    const world = worlds.find((w) => w.id === worldId);
    setResetTarget({ id: worldId, name: worldName });
    setResetScenario(world?.scenarioCode || (scenarios[0]?.code ?? ""));
  };

  const handleConfirmReset = async () => {
    if (!resetTarget || !resetScenario) return;
    setResetting(true);
    try {
      await resetWorld(resetTarget.id, resetScenario);
      toast.success("월드가 초기화되었습니다.");
      setResetTarget(null);
    } catch {
      toast.error("월드 초기화에 실패했습니다.");
    } finally {
      setResetting(false);
    }
  };

  const handleEnter = () => {
    router.push("/");
  };

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_1fr]">
      {/* LEFT PANEL: World List */}
      <div className="space-y-4">
        <h2 className="text-lg font-semibold flex items-center gap-2">
          <Globe className="size-5" />
          월드 목록
        </h2>

        {worldsLoading ? (
          <div className="flex items-center justify-center py-8 text-muted-foreground">
            <Loader2 className="size-5 animate-spin mr-2" />
            로딩 중...
          </div>
        ) : worlds.length === 0 ? (
          <p className="text-sm text-muted-foreground py-4">
            생성된 월드가 없습니다.
          </p>
        ) : (
          <div className="space-y-2">
            {worlds.map((w) => (
              <Card
                key={w.id}
                className={`cursor-pointer transition-colors hover:border-primary/50 ${currentWorld?.id === w.id ? "border-primary" : ""}`}
                onClick={() => handleSelectWorld(w)}
              >
                <CardContent className="flex items-center justify-between py-3">
                  <div>
                    <p className="font-medium">
                      {w.name ||
                        scenarioMap.get(w.scenarioCode) ||
                        w.scenarioCode}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {scenarioMap.get(w.scenarioCode) || w.scenarioCode}{" "}
                      &middot; {w.currentYear}년 {w.currentMonth}월
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    {w.realtimeMode && (
                      <Badge variant="secondary" className="text-xs">
                        실시간
                      </Badge>
                    )}
                    {isAdmin && (
                      <>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="size-7 text-muted-foreground hover:text-foreground"
                          onClick={(e) =>
                            handleOpenReset(
                              e,
                              w.id,
                              w.name ||
                                scenarioMap.get(w.scenarioCode) ||
                                w.scenarioCode,
                            )
                          }
                          title="초기화"
                        >
                          <RotateCcw className="size-3.5" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="size-7 text-muted-foreground hover:text-destructive"
                          onClick={(e) => handleDeleteWorld(e, w.id)}
                          title="삭제"
                        >
                          <Trash2 className="size-3.5" />
                        </Button>
                      </>
                    )}
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        )}

        {/* Create World Form - Admin Only */}
        {isAdmin && (
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">월드 생성 (관리자)</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <Input
                value={worldName}
                onChange={(e) => setWorldName(e.target.value)}
                placeholder="월드 이름을 입력하세요"
              />
              <select
                value={selectedScenario}
                onChange={(e) => setSelectedScenario(e.target.value)}
                className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
              >
                {scenarios.map((s) => (
                  <option key={s.code} value={s.code}>
                    {s.title} ({s.startYear}년)
                  </option>
                ))}
              </select>
              <Button
                className="w-full"
                onClick={handleCreateWorld}
                disabled={creating || !selectedScenario || !worldName.trim()}
              >
                <Plus className="size-4 mr-1" />
                {creating ? "생성 중..." : "새 월드 생성"}
              </Button>
            </CardContent>
          </Card>
        )}
      </div>

      {/* RIGHT PANEL: World Detail / General */}
      <div className="space-y-4">
        {/* Reset Panel */}
        {resetTarget && (
          <Card className="border-destructive/50">
            <CardHeader>
              <CardTitle className="text-sm flex items-center gap-2">
                <RotateCcw className="size-4" />
                월드 초기화: {resetTarget.name}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-xs text-muted-foreground">
                시나리오를 선택하면 해당 시나리오로 월드가 초기화됩니다. 모든
                진행 상황이 삭제됩니다.
              </p>
              <select
                value={resetScenario}
                onChange={(e) => setResetScenario(e.target.value)}
                className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
              >
                {scenarios.map((s) => (
                  <option key={s.code} value={s.code}>
                    {s.title} ({s.startYear}년)
                  </option>
                ))}
              </select>
              <div className="flex gap-2">
                <Button
                  variant="destructive"
                  className="flex-1"
                  onClick={handleConfirmReset}
                  disabled={resetting || !resetScenario}
                >
                  {resetting ? "초기화 중..." : "초기화 확인"}
                </Button>
                <Button
                  variant="outline"
                  className="flex-1"
                  onClick={() => setResetTarget(null)}
                >
                  취소
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {!currentWorld ? (
          <div className="flex items-center justify-center h-full min-h-[200px] text-muted-foreground">
            <p>월드를 선택하세요</p>
          </div>
        ) : generalLoading ? (
          <div className="flex items-center justify-center h-full min-h-[200px] text-muted-foreground">
            <Loader2 className="size-5 animate-spin mr-2" />
            장수 확인 중...
          </div>
        ) : myGeneral ? (
          /* General exists - show preview + enter button */
          <div className="space-y-4">
            <h2 className="text-lg font-semibold">내 장수</h2>
            <Card>
              <CardContent className="space-y-4 pt-4">
                <div className="flex items-center gap-4">
                  <GeneralPortrait
                    picture={myGeneral.picture}
                    name={myGeneral.name}
                    size="lg"
                  />
                  <div>
                    <p className="text-xl font-bold">{myGeneral.name}</p>
                    <p className="text-sm text-muted-foreground">
                      {currentWorld.scenarioCode} &middot;{" "}
                      {currentWorld.currentYear}년 {currentWorld.currentMonth}월
                    </p>
                  </div>
                </div>
                <div className="space-y-1">
                  <StatBar
                    label="통솔"
                    value={myGeneral.leadership}
                    color="bg-red-500"
                  />
                  <StatBar
                    label="무력"
                    value={myGeneral.strength}
                    color="bg-orange-500"
                  />
                  <StatBar
                    label="지력"
                    value={myGeneral.intel}
                    color="bg-blue-500"
                  />
                  <StatBar
                    label="정치"
                    value={myGeneral.politics}
                    color="bg-green-500"
                  />
                  <StatBar
                    label="매력"
                    value={myGeneral.charm}
                    color="bg-purple-500"
                  />
                </div>
                <Button className="w-full" onClick={handleEnter}>
                  <LogIn className="size-4 mr-1" />
                  입장
                </Button>
              </CardContent>
            </Card>
          </div>
        ) : (
          /* No general - show 3 options */
          <div className="space-y-4">
            <h2 className="text-lg font-semibold">장수 선택</h2>
            <p className="text-sm text-muted-foreground">
              이 월드에 장수가 없습니다. 아래 방법 중 하나를 선택하세요.
            </p>
            <div className="grid gap-3">
              <Card
                className="cursor-pointer hover:border-primary/50 transition-colors"
                onClick={() => router.push("/lobby/join")}
              >
                <CardContent className="flex items-center gap-4 py-4">
                  <div className="flex items-center justify-center size-10 rounded-lg bg-primary/10 text-primary">
                    <UserPlus className="size-5" />
                  </div>
                  <div>
                    <p className="font-medium">장수 생성</p>
                    <p className="text-xs text-muted-foreground">
                      새로운 장수를 만들어 시작합니다.
                    </p>
                  </div>
                </CardContent>
              </Card>
              <Card
                className="cursor-pointer hover:border-primary/50 transition-colors"
                onClick={() => router.push("/lobby/select-pool")}
              >
                <CardContent className="flex items-center gap-4 py-4">
                  <div className="flex items-center justify-center size-10 rounded-lg bg-primary/10 text-primary">
                    <Users className="size-5" />
                  </div>
                  <div>
                    <p className="font-medium">풀에서 선택</p>
                    <p className="text-xs text-muted-foreground">
                      등록된 장수 중 하나를 선택합니다.
                    </p>
                  </div>
                </CardContent>
              </Card>
              <Card
                className="cursor-pointer hover:border-primary/50 transition-colors"
                onClick={() => router.push("/lobby/select-npc")}
              >
                <CardContent className="flex items-center gap-4 py-4">
                  <div className="flex items-center justify-center size-10 rounded-lg bg-primary/10 text-primary">
                    <Bot className="size-5" />
                  </div>
                  <div>
                    <p className="font-medium">NPC 빙의</p>
                    <p className="text-xs text-muted-foreground">
                      빈 NPC 장수를 인수하여 플레이합니다.
                    </p>
                  </div>
                </CardContent>
              </Card>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
