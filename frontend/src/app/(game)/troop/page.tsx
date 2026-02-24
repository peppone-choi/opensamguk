"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { troopApi } from "@/lib/gameApi";
import type { Troop, General } from "@/types";
import { Shield, Plus, Swords, Clock, FileText, MapPin, Activity, ChevronDown, ChevronUp } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import {
  CREW_TYPE_NAMES,
  formatOfficerLevelText,
  isValidObjKey,
} from "@/lib/game-utils";

function TurnBrief({ members }: { members: General[] }) {
  const totalCrew = members.reduce((s, g) => s + g.crew, 0);
  const avgTrain =
    members.length > 0
      ? Math.round(members.reduce((s, g) => s + g.train, 0) / members.length)
      : 0;
  const avgAtmos =
    members.length > 0
      ? Math.round(members.reduce((s, g) => s + g.atmos, 0) / members.length)
      : 0;
  const injuredCount = members.filter((g) => g.injury > 0).length;
  const lowCrewCount = members.filter((g) => g.crew < 3000).length;
  const readyCount = members.filter((g) => !g.commandEndTime || new Date(g.commandEndTime) <= new Date()).length;

  return (
    <div className="bg-blue-950/30 border border-blue-900/50 rounded-lg p-3 space-y-2">
      <div className="flex items-center gap-2 text-xs font-medium text-blue-300">
        <Activity className="size-3" />
        턴 브리프
      </div>
      <div className="grid grid-cols-3 sm:grid-cols-6 gap-2 text-xs">
        <div className="text-center">
          <div className="text-muted-foreground">총 병력</div>
          <div className="text-blue-400 font-bold tabular-nums">{totalCrew.toLocaleString()}</div>
        </div>
        <div className="text-center">
          <div className="text-muted-foreground">평균 훈련</div>
          <div className="tabular-nums font-bold">{avgTrain}</div>
        </div>
        <div className="text-center">
          <div className="text-muted-foreground">평균 사기</div>
          <div className="tabular-nums font-bold">{avgAtmos}</div>
        </div>
        <div className="text-center">
          <div className="text-muted-foreground">대기 가능</div>
          <div className="text-green-400 tabular-nums font-bold">{readyCount}/{members.length}</div>
        </div>
        <div className="text-center">
          <div className="text-muted-foreground">부상</div>
          <div className={`tabular-nums font-bold ${injuredCount > 0 ? "text-orange-400" : ""}`}>{injuredCount}명</div>
        </div>
        <div className="text-center">
          <div className="text-muted-foreground">병력 부족</div>
          <div className={`tabular-nums font-bold ${lowCrewCount > 0 ? "text-red-400" : ""}`}>{lowCrewCount}명</div>
        </div>
      </div>
    </div>
  );
}

function CommandTimeline({ members }: { members: General[] }) {
  const [expanded, setExpanded] = useState(false);
  const sorted = [...members].sort((a, b) => {
    const ta = a.commandEndTime ? new Date(a.commandEndTime).getTime() : 0;
    const tb = b.commandEndTime ? new Date(b.commandEndTime).getTime() : 0;
    return ta - tb;
  });

  return (
    <div className="space-y-1">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
      >
        <Clock className="size-3" />
        명령 타임라인
        {expanded ? <ChevronUp className="size-3" /> : <ChevronDown className="size-3" />}
      </button>
      {expanded && (
        <div className="relative pl-4 border-l border-gray-700 space-y-1.5 ml-1">
          {sorted.map((g) => {
            const endTime = g.commandEndTime ? new Date(g.commandEndTime) : null;
            const isActive = endTime && endTime > new Date();
            const lastTurnAction = g.lastTurn?.actionCode as string | undefined;
            return (
              <div key={g.id} className="flex items-center gap-2 text-xs relative">
                <div className={`absolute -left-[21px] w-2.5 h-2.5 rounded-full border-2 ${isActive ? "bg-yellow-500 border-yellow-400" : "bg-gray-600 border-gray-500"}`} />
                <span className="font-medium w-16 truncate">{g.name}</span>
                <span className="text-muted-foreground">
                  {lastTurnAction || "대기"}
                </span>
                {endTime && (
                  <span className={`ml-auto tabular-nums ${isActive ? "text-yellow-400" : "text-gray-500"}`}>
                    {endTime.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}
                  </span>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function TroopSummary({ members }: { members: General[] }) {
  const totalCrew = members.reduce((s, g) => s + g.crew, 0);
  const avgTrain =
    members.length > 0
      ? Math.round(members.reduce((s, g) => s + g.train, 0) / members.length)
      : 0;
  const avgAtmos =
    members.length > 0
      ? Math.round(members.reduce((s, g) => s + g.atmos, 0) / members.length)
      : 0;

  return (
    <div className="flex items-center gap-4 text-xs text-muted-foreground border-t border-gray-800 pt-2 mt-2">
      <span>
        총 병력:{" "}
        <span className="text-blue-400 tabular-nums">
          {totalCrew.toLocaleString()}
        </span>
      </span>
      <span>
        평균 훈련: <span className="tabular-nums">{avgTrain}</span>
      </span>
      <span>
        평균 사기: <span className="tabular-nums">{avgAtmos}</span>
      </span>
    </div>
  );
}

function MemberRow({
  g,
  isLeader,
  isTroopLeader,
  troopId,
  cityName,
  leaderCityId,
  onKick,
}: {
  g: General;
  isLeader: boolean;
  isTroopLeader: boolean;
  troopId: number;
  cityName?: string;
  leaderCityId?: number;
  onKick: (troopId: number, generalId: number) => void;
}) {
  const isDifferentCity = leaderCityId != null && g.cityId !== leaderCityId;

  return (
    <TooltipProvider delayDuration={200}>
      <Tooltip>
        <TooltipTrigger asChild>
          <div className="flex items-center gap-3 rounded bg-muted/50 px-3 py-2 text-sm cursor-default hover:bg-muted/80 transition-colors">
            <GeneralPortrait picture={g.picture} name={g.name} size="sm" />
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <span className="font-medium truncate">{g.name}</span>
                {isLeader && (
                  <Badge variant="default" className="text-[10px] px-1.5">
                    대장
                  </Badge>
                )}
                {g.npcState > 0 && (
                  <Badge variant="outline" className="text-[10px] px-1">
                    NPC
                  </Badge>
                )}
              </div>
              <div className="flex items-center gap-3 text-xs text-muted-foreground mt-0.5 flex-wrap">
                <span>{formatOfficerLevelText(g.officerLevel)}</span>
                <span>
                  {CREW_TYPE_NAMES[g.crewType] ?? g.crewType}{" "}
                  {g.crew.toLocaleString()}
                </span>
                <span>훈{g.train}</span>
                <span>사{g.atmos}</span>
                {cityName && (
                  <span
                    className={`flex items-center gap-0.5 ${isDifferentCity ? "text-orange-400" : ""}`}
                  >
                    <MapPin className="size-2.5" />
                    {cityName}
                  </span>
                )}
              </div>
            </div>
            {/* Equipment */}
            <div className="hidden sm:flex items-center gap-2 text-[10px] text-muted-foreground">
              {isValidObjKey(g.weaponCode) && (
                <Badge variant="outline" className="text-[10px] px-1">
                  무기
                </Badge>
              )}
              {isValidObjKey(g.bookCode) && (
                <Badge variant="outline" className="text-[10px] px-1">
                  서적
                </Badge>
              )}
              {isValidObjKey(g.horseCode) && (
                <Badge variant="outline" className="text-[10px] px-1">
                  말
                </Badge>
              )}
              {isValidObjKey(g.itemCode) && (
                <Badge variant="outline" className="text-[10px] px-1">
                  도구
                </Badge>
              )}
            </div>
            {/* Kick button for troop leader */}
            {isTroopLeader && !isLeader && (
              <Button
                size="sm"
                variant="ghost"
                className="text-red-400 h-6 px-2"
                onClick={(e) => {
                  e.stopPropagation();
                  onKick(troopId, g.id);
                }}
              >
                추방
              </Button>
            )}
          </div>
        </TooltipTrigger>
        <TooltipContent side="top" className="max-w-xs">
          <div className="space-y-1 text-xs">
            <div className="font-semibold">{g.name}</div>
            <div className="grid grid-cols-2 gap-x-4 gap-y-0.5">
              <span className="text-muted-foreground">통솔</span>
              <span className="tabular-nums">{g.leadership}</span>
              <span className="text-muted-foreground">무력</span>
              <span className="tabular-nums">{g.strength}</span>
              <span className="text-muted-foreground">지력</span>
              <span className="tabular-nums">{g.intel}</span>
              <span className="text-muted-foreground">정치</span>
              <span className="tabular-nums">{g.politics ?? "-"}</span>
              <span className="text-muted-foreground">매력</span>
              <span className="tabular-nums">{g.charm ?? "-"}</span>
            </div>
            <div className="border-t border-gray-700 pt-1 grid grid-cols-2 gap-x-4 gap-y-0.5">
              <span className="text-muted-foreground">병종</span>
              <span>{CREW_TYPE_NAMES[g.crewType] ?? g.crewType}</span>
              <span className="text-muted-foreground">병력</span>
              <span className="tabular-nums">{g.crew.toLocaleString()}</span>
              <span className="text-muted-foreground">훈련</span>
              <span className="tabular-nums">{g.train}</span>
              <span className="text-muted-foreground">사기</span>
              <span className="tabular-nums">{g.atmos}</span>
              <span className="text-muted-foreground">수비훈</span>
              <span className="tabular-nums">{g.defenceTrain ?? "-"}</span>
              <span className="text-muted-foreground">금</span>
              <span className="tabular-nums">{g.gold?.toLocaleString() ?? "-"}</span>
              <span className="text-muted-foreground">쌀</span>
              <span className="tabular-nums">{g.rice?.toLocaleString() ?? "-"}</span>
              {cityName && (
                <>
                  <span className="text-muted-foreground">도시</span>
                  <span>{cityName}</span>
                </>
              )}
              {g.turnTime && (
                <>
                  <span className="text-muted-foreground">턴</span>
                  <span className="tabular-nums">{g.turnTime.slice(-8)}</span>
                </>
              )}
              {g.commandEndTime && (
                <>
                  <span className="text-muted-foreground">명령 종료</span>
                  <span className="tabular-nums">{g.commandEndTime.slice(-8)}</span>
                </>
              )}
            </div>
            {g.specialCode && (
              <div className="border-t border-gray-700 pt-1">
                <span className="text-muted-foreground">특기: </span>
                <span className="text-cyan-400">{g.specialCode}</span>
              </div>
            )}
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

export default function TroopPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { generals, cities, loadAll } = useGameStore();
  const [troops, setTroops] = useState<Troop[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState("");
  const [renamingId, setRenamingId] = useState<number | null>(null);
  const [renameText, setRenameText] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!currentWorld) return;
    if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
    loadAll(currentWorld.id);
  }, [currentWorld, myGeneral, fetchMyGeneral, loadAll]);

  const fetchTroops = async () => {
    if (!myGeneral?.nationId) return;
    try {
      const { data } = await troopApi.listByNation(myGeneral.nationId);
      setTroops(data.map((tw) => tw.troop));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTroops();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [myGeneral?.nationId]);

  const generalMap = useMemo(
    () => new Map(generals.map((g) => [g.id, g])),
    [generals],
  );

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );

  const troopMembers = useMemo(() => {
    const map = new Map<number, General[]>();
    for (const t of troops) {
      map.set(
        t.id,
        generals.filter((g) => g.troopId === t.id),
      );
    }
    return map;
  }, [troops, generals]);

  const handleCreate = async () => {
    if (!currentWorld || !myGeneral?.nationId || !newName.trim()) return;
    setSaving(true);
    try {
      await troopApi.create({
        worldId: currentWorld.id,
        leaderGeneralId: myGeneral.id,
        nationId: myGeneral.nationId,
        name: newName.trim(),
      });
      setNewName("");
      setShowCreate(false);
      await fetchTroops();
    } finally {
      setSaving(false);
    }
  };

  const handleJoin = async (troopId: number) => {
    if (!myGeneral) return;
    await troopApi.join(troopId, myGeneral.id);
    await fetchTroops();
    if (currentWorld) fetchMyGeneral(currentWorld.id);
  };

  const handleExit = async (troopId: number) => {
    if (!myGeneral) return;
    await troopApi.exit(troopId, myGeneral.id);
    await fetchTroops();
    if (currentWorld) fetchMyGeneral(currentWorld.id);
  };

  const handleDisband = async (troopId: number) => {
    if (!confirm("부대를 해산하시겠습니까?")) return;
    await troopApi.disband(troopId);
    await fetchTroops();
  };

  const handleKick = async (troopId: number, generalId: number) => {
    if (!confirm("추방하시겠습니까?")) return;
    await troopApi.kick(troopId, generalId);
    await fetchTroops();
  };

  const handleRename = async (troopId: number) => {
    if (!renameText.trim()) return;
    await troopApi.rename(troopId, renameText.trim());
    setRenamingId(null);
    setRenameText("");
    await fetchTroops();
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;
  if (!myGeneral?.nationId)
    return (
      <div className="p-4 text-muted-foreground">소속 국가가 없습니다.</div>
    );

  const myTroop = troops.find(
    (t) =>
      t.leaderGeneralId === myGeneral.id ||
      (troopMembers.get(t.id) ?? []).some((m) => m.id === myGeneral.id),
  );

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <div className="flex items-center justify-between">
        <PageHeader icon={Shield} title="부대 편성" />
        {!myTroop && (
          <Button
            onClick={() => setShowCreate(!showCreate)}
            variant={showCreate ? "outline" : "default"}
            size="sm"
          >
            <Plus className="size-4" />
            {showCreate ? "취소" : "부대 창설"}
          </Button>
        )}
      </div>

      {/* Create form */}
      {showCreate && (
        <Card>
          <CardContent className="space-y-3">
            <div>
              <label className="block text-xs text-muted-foreground mb-1">
                부대명
              </label>
              <Input
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                placeholder="부대 이름..."
              />
            </div>
            <Button onClick={handleCreate} disabled={saving || !newName.trim()}>
              {saving ? "생성 중..." : "창설"}
            </Button>
          </CardContent>
        </Card>
      )}

      {/* My current troop highlight */}
      {myTroop && (
        <Card className="border-blue-800">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <Swords className="size-4 text-blue-400" />
              현재 소속 부대: {myTroop.name}
            </CardTitle>
          </CardHeader>
        </Card>
      )}

      {/* Troop list */}
      {troops.length === 0 ? (
        <EmptyState icon={Shield} title="부대가 없습니다." />
      ) : (
        troops.map((t) => {
          const leader = generalMap.get(t.leaderGeneralId);
          const members = troopMembers.get(t.id) ?? [];
          const isLeader = myGeneral.id === t.leaderGeneralId;
          const isMember = members.some((m) => m.id === myGeneral.id);
          const canRename =
            isLeader && (myGeneral.officerLevel ?? 0) >= 4;

          return (
            <Card key={t.id}>
              <CardHeader>
                <CardTitle className="flex items-center justify-between">
                  <div className="flex items-center gap-2 flex-wrap">
                    {renamingId === t.id ? (
                      <div className="flex gap-2">
                        <Input
                          value={renameText}
                          onChange={(e) => setRenameText(e.target.value)}
                          className="h-8 w-40"
                        />
                        <Button size="sm" onClick={() => handleRename(t.id)}>
                          확인
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setRenamingId(null)}
                        >
                          취소
                        </Button>
                      </div>
                    ) : (
                      <>
                        <span>{t.name}</span>
                        <Badge variant="secondary">{members.length}명</Badge>
                        {/* Turn time display */}
                        {t.turnTime && (
                          <span className="flex items-center gap-1 text-xs text-muted-foreground">
                            <Clock className="size-3" />
                            {t.turnTime}
                          </span>
                        )}
                      </>
                    )}
                  </div>
                  <div className="flex gap-1">
                    {canRename && renamingId !== t.id && (
                      <>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            setRenamingId(t.id);
                            setRenameText(t.name);
                          }}
                        >
                          이름변경
                        </Button>
                      </>
                    )}
                    {isLeader && (
                      <Button
                        size="sm"
                        variant="destructive"
                        onClick={() => handleDisband(t.id)}
                      >
                        해산
                      </Button>
                    )}
                    {!isMember && (
                      <Button size="sm" onClick={() => handleJoin(t.id)}>
                        가입
                      </Button>
                    )}
                    {isMember && !isLeader && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => handleExit(t.id)}
                      >
                        탈퇴
                      </Button>
                    )}
                  </div>
                </CardTitle>
              </CardHeader>
              <CardContent>
                {/* Reserved command brief */}
                {t.reservedCommandBrief && (
                  <div className="flex items-center gap-1.5 text-xs text-cyan-400 mb-2 bg-cyan-950/30 rounded px-2 py-1">
                    <FileText className="size-3 shrink-0" />
                    <span>예약 명령: {t.reservedCommandBrief}</span>
                  </div>
                )}

                <div className="space-y-1">
                  {/* Leader first */}
                  {leader && (
                    <MemberRow
                      g={leader}
                      isLeader={true}
                      isTroopLeader={isLeader}
                      troopId={t.id}
                      cityName={cityMap.get(leader.cityId)?.name}
                      leaderCityId={leader.cityId}
                      onKick={handleKick}
                    />
                  )}
                  {/* Other members */}
                  {members
                    .filter((m) => m.id !== t.leaderGeneralId)
                    .map((m) => (
                      <MemberRow
                        key={m.id}
                        g={m}
                        isLeader={false}
                        isTroopLeader={isLeader}
                        troopId={t.id}
                        cityName={cityMap.get(m.cityId)?.name}
                        leaderCityId={leader?.cityId}
                        onKick={handleKick}
                      />
                    ))}
                </div>
                {/* Turn Brief */}
                {members.length > 0 && <TurnBrief members={members} />}

                {/* Command Timeline */}
                {members.length > 0 && <CommandTimeline members={members} />}

                {/* Troop summary */}
                <TroopSummary members={members} />
              </CardContent>
            </Card>
          );
        })
      )}
    </div>
  );
}
