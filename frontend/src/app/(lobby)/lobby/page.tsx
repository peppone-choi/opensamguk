"use client";

import { useEffect, useState, useMemo } from "react";
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
  Clock,
  Signal,
  Crown,
  Swords,
  Shield,
} from "lucide-react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useAuthStore } from "@/stores/authStore";
import { scenarioApi } from "@/lib/gameApi";
import type { Scenario, WorldState } from "@/types";
import { toast } from "sonner";
import { ServerStatusCard } from "@/components/auth/server-status-card";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { StatBar } from "@/components/game/stat-bar";

/** Derive server phase from world metadata */
function getServerPhase(w: WorldState): {
  label: string;
  color: string;
  icon: typeof Shield;
} {
  const meta = w.meta ?? {};
  if (meta.finished || meta.isFinished)
    return { label: "종료", color: "text-gray-400", icon: Shield };
  if (meta.isLocked || meta.locked)
    return { label: "잠김", color: "text-yellow-400", icon: Shield };
  // 가오픈/reserved distinction (legacy parity: entrance.ts)
  if (meta.isReserved || meta.reserved)
    return { label: "가오픈", color: "text-orange-400", icon: Clock };
  if (w.realtimeMode)
    return { label: "실시간", color: "text-green-400", icon: Signal };
  return { label: "턴제", color: "text-cyan-400", icon: Clock };
}

/** Derive player/capacity counts from world metadata */
function getPlayerInfo(w: WorldState): {
  current: number;
  max: number;
  npc: number;
} {
  const meta = w.meta ?? {};
  const config = w.config ?? {};
  return {
    current: (meta.playerCount as number) ?? (meta.userCount as number) ?? 0,
    max:
      (config.generalCntLimit as number) ??
      (meta.generalCntLimit as number) ??
      100,
    npc: (meta.npcCount as number) ?? 0,
  };
}

/** Can user join/found/rise in this world? */
function getActionAvailability(
  w: WorldState,
  hasGeneral: boolean,
): {
  canJoin: boolean;
  canFound: boolean;
  canRise: boolean;
  joinReason?: string;
  foundReason?: string;
  riseReason?: string;
} {
  const meta = w.meta ?? {};
  const config = w.config ?? {};
  const isFinished = !!(meta.finished || meta.isFinished);
  const isLocked = !!(meta.isLocked || meta.locked);
  const playerInfo = getPlayerInfo(w);
  const isFull = playerInfo.current >= playerInfo.max;

  if (hasGeneral) {
    return {
      canJoin: false,
      canFound: false,
      canRise: false,
      joinReason: "이미 장수가 있습니다",
      foundReason: "이미 장수가 있습니다",
      riseReason: "이미 장수가 있습니다",
    };
  }

  if (isFinished) {
    return {
      canJoin: false,
      canFound: false,
      canRise: false,
      joinReason: "종료된 서버",
      foundReason: "종료된 서버",
      riseReason: "종료된 서버",
    };
  }

  const joinMode = (config.joinMode as string) ?? (meta.joinMode as string) ?? "normal";

  // Legacy parity: block_general_create bitfield check
  const blockBits = (meta.blockGeneralCreate as number) ?? (config.blockGeneralCreate as number) ?? 0;
  const blockCreate = !!(blockBits & 1);
  const blockNpc = !!(blockBits & 2);
  const blockFound = !!(blockBits & 4);
  const blockRise = !!(blockBits & 8);

  return {
    canJoin: !isLocked && !isFull && !blockCreate,
    canFound:
      !isLocked &&
      !isFull &&
      !blockFound &&
      joinMode !== "noFound",
    canRise:
      !isLocked &&
      !isFull &&
      !blockRise &&
      joinMode !== "noRise",
    joinReason: isLocked
      ? "서버 잠김"
      : isFull
        ? "정원 초과"
        : undefined,
    foundReason: isLocked
      ? "서버 잠김"
      : isFull
        ? "정원 초과"
        : joinMode === "noFound"
          ? "건국 불가"
          : undefined,
    riseReason: isLocked
      ? "서버 잠김"
      : isFull
        ? "정원 초과"
        : joinMode === "noRise"
          ? "거병 불가"
          : undefined,
  };
}

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
  const [notice, setNotice] = useState("");
  const [serverNotices, setServerNotices] = useState<Record<number, string>>({});
  const scenarioMap = useMemo(
    () => new Map(scenarios.map((s) => [s.code, s.title])),
    [scenarios],
  );

  useEffect(() => {
    fetchWorlds().then(() => {
      // Load server notices from world metadata
      const worldStore = useWorldStore.getState();
      const notices: Record<number, string> = {};
      let globalNotice = "";
      for (const w of worldStore.worlds) {
        const n = (w.meta?.notice as string) ?? (w.meta?.serverNotice as string) ?? "";
        if (n) notices[w.id] = n;
        if (!globalNotice && (w.meta?.globalNotice as string)) {
          globalNotice = w.meta?.globalNotice as string;
        }
      }
      setServerNotices(notices);
      if (globalNotice) setNotice(globalNotice);
    });
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

  const actionAvailability = useMemo(() => {
    if (!currentWorld) return null;
    return getActionAvailability(currentWorld, !!myGeneral);
  }, [currentWorld, myGeneral]);

  return (
    <div className="space-y-6">
      {/* Notice */}
      {notice && (
        <div className="text-center">
          <span
            className="text-2xl font-bold text-orange-500"
            dangerouslySetInnerHTML={{ __html: notice }}
          />
        </div>
      )}

      {/* Server Status (public cached map) */}
      <ServerStatusCard />

      <div className="grid gap-6 lg:grid-cols-[1fr_1fr]">
        {/* LEFT PANEL: World List with Status Indicators */}
        <div className="space-y-4">
          <h2 className="text-lg font-semibold flex items-center gap-2">
            <Globe className="size-5" />
            서버 목록
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
              {worlds.map((w) => {
                const phase = getServerPhase(w);
                const players = getPlayerInfo(w);
                const PhaseIcon = phase.icon;
                const worldDisplayName =
                  w.name ||
                  scenarioMap.get(w.scenarioCode) ||
                  w.scenarioCode;

                return (
                  <Card
                    key={w.id}
                    className={`cursor-pointer transition-colors hover:border-primary/50 ${currentWorld?.id === w.id ? "border-primary bg-primary/5" : ""}`}
                    onClick={() => handleSelectWorld(w)}
                  >
                    <CardContent className="py-3 space-y-2">
                      {/* Top row: name + admin buttons */}
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <p className="font-medium">{worldDisplayName}</p>
                          <Badge
                            variant="outline"
                            className={`text-[10px] ${phase.color}`}
                          >
                            <PhaseIcon className="size-3 mr-0.5" />
                            {phase.label}
                          </Badge>
                        </div>
                        <div className="flex items-center gap-1">
                          {w.realtimeMode && (
                            <Badge
                              variant="secondary"
                              className="text-[10px]"
                            >
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
                                  handleOpenReset(e, w.id, worldDisplayName)
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
                      </div>

                      {/* Status indicators row */}
                      <div className="flex items-center gap-4 text-xs text-muted-foreground">
                        <span className="flex items-center gap-1">
                          <Clock className="size-3" />
                          {w.currentYear}년 {w.currentMonth}월
                        </span>
                        <span className="flex items-center gap-1">
                          <Users className="size-3" />
                          인원: {players.current}/{players.max}
                          {players.npc > 0 && (
                            <span className="text-cyan-400">
                              +NPC {players.npc}
                            </span>
                          )}
                        </span>
                        <span className="flex items-center gap-1">
                          <Signal className="size-3" />
                          {w.tickSeconds
                            ? `${Math.round(w.tickSeconds / 60)}분 턴`
                            : "턴제"}
                        </span>
                      </div>

                      {/* Extended server info (legacy parity: entrance.ts per-server detail) */}
                      <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] text-muted-foreground">
                        <span>
                          시나리오: {scenarioMap.get(w.scenarioCode) || w.scenarioCode}
                        </span>
                        {(w.meta?.nationCount != null || w.meta?.nationCnt != null) && (
                          <span>국가: {String(w.meta?.nationCount ?? w.meta?.nationCnt)}개</span>
                        )}
                        {w.meta?.fictionMode ? (
                          <Badge variant="outline" className="text-[9px] h-4 px-1 text-purple-400 border-purple-400/30">
                            가상
                          </Badge>
                        ) : null}
                        {(w.meta?.isUnited || w.meta?.united) ? (
                          <Badge variant="outline" className="text-[9px] h-4 px-1 text-yellow-400 border-yellow-400/30">
                            통일
                          </Badge>
                        ) : null}
                        {w.meta?.eventStatus ? (
                          <Badge variant="outline" className="text-[9px] h-4 px-1 text-cyan-400 border-cyan-400/30">
                            이벤트
                          </Badge>
                        ) : null}
                        {w.meta?.openTime ? (
                          <span>오픈: {new Date(String(w.meta.openTime)).toLocaleDateString("ko-KR")}</span>
                        ) : null}
                      </div>
                      {/* Per-server notice */}
                      {serverNotices[w.id] && (
                        <p className="text-[11px] text-orange-400 mt-1" dangerouslySetInnerHTML={{ __html: serverNotices[w.id] }} />
                      )}
                    </CardContent>
                  </Card>
                );
              })}
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
              <p>서버를 선택하세요</p>
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
                        {currentWorld.currentYear}년{" "}
                        {currentWorld.currentMonth}월
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
            /* No general - dynamic action matrix based on server state */
            <div className="space-y-4">
              <h2 className="text-lg font-semibold">장수 선택</h2>

              {/* Server state summary */}
              {currentWorld && (
                <Card className="border-muted">
                  <CardContent className="py-3">
                    <div className="flex items-center justify-between text-xs">
                      <span className="text-muted-foreground">
                        서버 상태
                      </span>
                      <Badge
                        variant="outline"
                        className={getServerPhase(currentWorld).color}
                      >
                        {getServerPhase(currentWorld).label}
                      </Badge>
                    </div>
                    <div className="mt-1 flex items-center gap-3 text-xs text-muted-foreground">
                      <span>
                        인원: {getPlayerInfo(currentWorld).current}/
                        {getPlayerInfo(currentWorld).max}
                      </span>
                      <span>
                        {currentWorld.currentYear}년{" "}
                        {currentWorld.currentMonth}월
                      </span>
                    </div>
                  </CardContent>
                </Card>
              )}

              <div className="grid gap-3">
                {/* 장수 생성 */}
                <Card
                  className={`transition-colors ${
                    actionAvailability?.canJoin
                      ? "cursor-pointer hover:border-primary/50"
                      : "opacity-50 cursor-not-allowed"
                  }`}
                  onClick={() =>
                    actionAvailability?.canJoin &&
                    router.push("/lobby/join")
                  }
                >
                  <CardContent className="flex items-center gap-4 py-4">
                    <div className="flex items-center justify-center size-10 rounded-lg bg-primary/10 text-primary">
                      <UserPlus className="size-5" />
                    </div>
                    <div className="flex-1">
                      <p className="font-medium">장수 생성</p>
                      <p className="text-xs text-muted-foreground">
                        {actionAvailability?.joinReason ??
                          "새로운 장수를 만들어 시작합니다."}
                      </p>
                    </div>
                    {actionAvailability?.canJoin && (
                      <Badge variant="secondary" className="text-[10px]">
                        가능
                      </Badge>
                    )}
                  </CardContent>
                </Card>

                {/* 풀에서 선택 */}
                <Card
                  className={`transition-colors ${
                    actionAvailability?.canJoin
                      ? "cursor-pointer hover:border-primary/50"
                      : "opacity-50 cursor-not-allowed"
                  }`}
                  onClick={() =>
                    actionAvailability?.canJoin &&
                    router.push("/lobby/select-pool")
                  }
                >
                  <CardContent className="flex items-center gap-4 py-4">
                    <div className="flex items-center justify-center size-10 rounded-lg bg-primary/10 text-primary">
                      <Users className="size-5" />
                    </div>
                    <div className="flex-1">
                      <p className="font-medium">풀에서 선택</p>
                      <p className="text-xs text-muted-foreground">
                        {actionAvailability?.joinReason ??
                          "등록된 장수 중 하나를 선택합니다."}
                      </p>
                    </div>
                    {actionAvailability?.canJoin && (
                      <Badge variant="secondary" className="text-[10px]">
                        가능
                      </Badge>
                    )}
                  </CardContent>
                </Card>

                {/* NPC 빙의 */}
                <Card
                  className={`transition-colors ${
                    actionAvailability?.canJoin
                      ? "cursor-pointer hover:border-primary/50"
                      : "opacity-50 cursor-not-allowed"
                  }`}
                  onClick={() =>
                    actionAvailability?.canJoin &&
                    router.push("/lobby/select-npc")
                  }
                >
                  <CardContent className="flex items-center gap-4 py-4">
                    <div className="flex items-center justify-center size-10 rounded-lg bg-primary/10 text-primary">
                      <Bot className="size-5" />
                    </div>
                    <div className="flex-1">
                      <p className="font-medium">NPC 빙의</p>
                      <p className="text-xs text-muted-foreground">
                        {actionAvailability?.joinReason ??
                          "빈 NPC 장수를 인수하여 플레이합니다."}
                      </p>
                    </div>
                    {actionAvailability?.canJoin && (
                      <Badge variant="secondary" className="text-[10px]">
                        가능
                      </Badge>
                    )}
                  </CardContent>
                </Card>

                {/* 건국 */}
                <Card
                  className={`transition-colors ${
                    actionAvailability?.canFound
                      ? "cursor-pointer hover:border-yellow-500/50 border-yellow-500/20"
                      : "opacity-50 cursor-not-allowed"
                  }`}
                  onClick={() =>
                    actionAvailability?.canFound &&
                    router.push("/lobby/join?mode=found")
                  }
                >
                  <CardContent className="flex items-center gap-4 py-4">
                    <div className="flex items-center justify-center size-10 rounded-lg bg-yellow-500/10 text-yellow-500">
                      <Crown className="size-5" />
                    </div>
                    <div className="flex-1">
                      <p className="font-medium">건국</p>
                      <p className="text-xs text-muted-foreground">
                        {actionAvailability?.foundReason ??
                          "새로운 국가를 세우고 군주로 시작합니다."}
                      </p>
                    </div>
                    {actionAvailability?.canFound && (
                      <Badge
                        variant="outline"
                        className="text-[10px] text-yellow-400"
                      >
                        가능
                      </Badge>
                    )}
                  </CardContent>
                </Card>

                {/* 거병 */}
                <Card
                  className={`transition-colors ${
                    actionAvailability?.canRise
                      ? "cursor-pointer hover:border-red-500/50 border-red-500/20"
                      : "opacity-50 cursor-not-allowed"
                  }`}
                  onClick={() =>
                    actionAvailability?.canRise &&
                    router.push("/lobby/join?mode=rise")
                  }
                >
                  <CardContent className="flex items-center gap-4 py-4">
                    <div className="flex items-center justify-center size-10 rounded-lg bg-red-500/10 text-red-500">
                      <Swords className="size-5" />
                    </div>
                    <div className="flex-1">
                      <p className="font-medium">거병</p>
                      <p className="text-xs text-muted-foreground">
                        {actionAvailability?.riseReason ??
                          "반란을 일으켜 독립 세력으로 시작합니다."}
                      </p>
                    </div>
                    {actionAvailability?.canRise && (
                      <Badge
                        variant="outline"
                        className="text-[10px] text-red-400"
                      >
                        가능
                      </Badge>
                    )}
                  </CardContent>
                </Card>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Multi-account warning */}
      <Card>
        <CardContent className="space-y-2 pt-4 text-xs text-muted-foreground">
          <p className="font-bold text-red-500">
            ★ 1명이 2개 이상의 계정을 사용하거나 타 유저의 턴을 대신 입력하는
            것이 적발될 경우 차단 될 수 있습니다.
          </p>
          <p>
            계정은 한번 등록으로 계속 사용합니다. 각 서버 리셋시 캐릭터만 새로
            생성하면 됩니다.
          </p>
        </CardContent>
      </Card>

      {/* Account Management */}
      <Card>
        <CardHeader>
          <CardTitle className="text-center text-sm tracking-widest">
            계 정 관 리
          </CardTitle>
        </CardHeader>
        <CardContent className="flex justify-center gap-3">
          <Button variant="outline" onClick={() => router.push("/account")}>
            비밀번호 &amp; 전콘 &amp; 탈퇴
          </Button>
          <Button
            variant="outline"
            onClick={() => {
              useAuthStore.getState().logout();
              router.push("/login");
            }}
          >
            로 그 아 웃
          </Button>
          {isAdmin && (
            <Button variant="outline" onClick={() => router.push("/admin")}>
              관리자 페이지
            </Button>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
