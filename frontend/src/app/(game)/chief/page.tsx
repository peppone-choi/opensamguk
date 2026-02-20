"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type MouseEvent,
} from "react";
import { Crown, UserCog, Users, Ban } from "lucide-react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import {
  generalApi,
  commandApi,
  nationApi,
  nationManagementApi,
  cityApi,
} from "@/lib/gameApi";
import type {
  General,
  Nation,
  City,
  NationTurn,
  CommandResult,
  CommandTableEntry,
} from "@/types";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { ResourceDisplay } from "@/components/game/resource-display";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  formatOfficerLevelText,
  CREW_TYPE_NAMES,
  REGION_NAMES,
} from "@/lib/game-utils";
import {
  CommandArgForm,
  COMMAND_ARGS,
} from "@/components/game/command-arg-form";

const CHIEF_STAT_MIN = 65;
const NATION_TURN_COUNT = 12;

function getMinNationChiefLevel(nationLevel: number): number {
  // Nation level determines the minimum officer level available
  // Higher nation level => more officer slots
  if (nationLevel >= 7) return 5;
  if (nationLevel >= 6) return 5;
  if (nationLevel >= 5) return 7;
  if (nationLevel >= 4) return 7;
  if (nationLevel >= 3) return 9;
  return 9;
}

export default function ChiefPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const [nation, setNation] = useState<Nation | null>(null);
  const [nationGenerals, setNationGenerals] = useState<General[]>([]);
  const [nationCities, setNationCities] = useState<City[]>([]);
  const [nationTurns, setNationTurns] = useState<NationTurn[]>([]);
  const [selectedCmd, setSelectedCmd] = useState<CommandTableEntry | null>(
    null,
  );
  const [selectedNationSlots, setSelectedNationSlots] = useState<Set<number>>(
    new Set([0]),
  );
  const [lastNationClickedSlot, setLastNationClickedSlot] = useState(0);
  const [showNationReserveForm, setShowNationReserveForm] = useState(false);
  const [reservingNation, setReservingNation] = useState(false);
  const [nationReserveResult, setNationReserveResult] =
    useState<CommandResult | null>(null);
  const [nationCommandTable, setNationCommandTable] = useState<
    Record<string, CommandTableEntry[]>
  >({});
  const [executing, setExecuting] = useState(false);
  const [cmdResult, setCmdResult] = useState<CommandResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [appointMsg, setAppointMsg] = useState<string | null>(null);
  const [appointingLevel, setAppointingLevel] = useState<number | null>(null);

  // Appointment selections: officerLevel -> generalId
  const [appointSelections, setAppointSelections] = useState<
    Record<number, string>
  >({});
  // City officer: level -> { cityId, generalId }
  const [cityOfficerSelections, setCityOfficerSelections] = useState<
    Record<number, { cityId: string; generalId: string }>
  >({});
  // Permission selections
  const [ambassadorSelection, setAmbassadorSelection] = useState("");
  const [auditorSelection, setAuditorSelection] = useState("");
  // Expulsion
  const [expelSelection, setExpelSelection] = useState("");

  useEffect(() => {
    if (!currentWorld) return;
    fetchMyGeneral(currentWorld.id);
  }, [currentWorld, fetchMyGeneral]);

  const reload = useCallback(async () => {
    if (!myGeneral || myGeneral.officerLevel < 5) return;
    setLoading(true);
    try {
      const [natRes, gRes, tRes, cmdRes, cRes] = await Promise.all([
        nationApi.get(myGeneral.nationId),
        generalApi.listByNation(myGeneral.nationId),
        commandApi.getNationReserved(
          myGeneral.nationId,
          myGeneral.officerLevel,
        ),
        commandApi.getNationCommandTable(myGeneral.id),
        cityApi.listByNation(myGeneral.nationId),
      ]);
      setNation(natRes.data);
      setNationGenerals(gRes.data);
      setNationTurns(tRes.data);
      setNationCommandTable(cmdRes.data);
      setNationCities(cRes.data);
    } catch {
      setError("데이터를 불러올 수 없습니다.");
    } finally {
      setLoading(false);
    }
  }, [myGeneral]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const getNationTurn = (idx: number) =>
    nationTurns.find((turn) => turn.turnIdx === idx);

  const handleNationSlotClick = (
    idx: number,
    e: MouseEvent<HTMLButtonElement>,
  ) => {
    if (e.shiftKey && lastNationClickedSlot !== idx) {
      const start = Math.min(lastNationClickedSlot, idx);
      const end = Math.max(lastNationClickedSlot, idx);
      const next = new Set<number>();
      for (let i = start; i <= end; i += 1) {
        next.add(i);
      }
      setSelectedNationSlots(next);
    } else if (e.ctrlKey || e.metaKey) {
      const next = new Set(selectedNationSlots);
      if (next.has(idx)) {
        next.delete(idx);
        if (next.size === 0) {
          next.add(idx);
        }
      } else {
        next.add(idx);
      }
      setSelectedNationSlots(next);
    } else {
      setSelectedNationSlots(new Set([idx]));
      setShowNationReserveForm(true);
    }
    setLastNationClickedSlot(idx);
  };

  const handleNationReserve = async (
    actionCode: string,
    arg?: Record<string, unknown>,
  ) => {
    if (!myGeneral?.nationId) return;

    setReservingNation(true);
    setNationReserveResult(null);
    try {
      const turns = [...selectedNationSlots]
        .sort((a, b) => a - b)
        .map((turnIdx) => ({ turnIdx, actionCode, arg }));
      await commandApi.reserveNation(myGeneral.nationId, myGeneral.id, turns);
      await reload();
      setNationReserveResult({
        success: true,
        logs: ["국가 명령 예약을 저장했습니다."],
      });
      setShowNationReserveForm(false);

      const maxSlot = Math.max(...selectedNationSlots);
      if (maxSlot < NATION_TURN_COUNT - 1) {
        setSelectedNationSlots(new Set([maxSlot + 1]));
        setLastNationClickedSlot(maxSlot + 1);
      }
    } catch {
      setNationReserveResult({
        success: false,
        logs: ["국가 명령 예약 저장에 실패했습니다."],
      });
    } finally {
      setReservingNation(false);
    }
  };

  const nationCommandCategories = Object.keys(nationCommandTable);

  // Officer hierarchy: build map of officerLevel -> general
  const officerMap = useMemo(() => {
    const map: Record<number, General> = {};
    for (const g of nationGenerals) {
      if (g.officerLevel >= 5) {
        map[g.officerLevel] = g;
      }
    }
    return map;
  }, [nationGenerals]);

  // City officers: cityId -> { level -> general }
  const cityOfficerMap = useMemo(() => {
    const map = new Map<number, Record<number, General>>();
    for (const g of nationGenerals) {
      if (g.officerLevel >= 2 && g.officerLevel <= 4 && g.officerCity > 0) {
        const existing = map.get(g.officerCity) ?? {};
        existing[g.officerLevel] = g;
        map.set(g.officerCity, existing);
      }
    }
    return map;
  }, [nationGenerals]);

  // City map for lookups
  const cityMap = useMemo(
    () => new Map(nationCities.map((c) => [c.id, c])),
    [nationCities],
  );

  const citiesByRegion = useMemo(() => {
    const grouped: Record<number, City[]> = {};
    for (const c of nationCities) {
      if (!grouped[c.region]) grouped[c.region] = [];
      grouped[c.region].push(c);
    }
    for (const region of Object.keys(grouped)) {
      grouped[Number(region)].sort((a, b) => b.level - a.level);
    }
    return grouped;
  }, [nationCities]);

  // Candidate lists matching legacy PHP logic
  const candidatesByStrength = useMemo(
    () =>
      nationGenerals
        .filter((g) => g.officerLevel !== 12 && g.strength >= CHIEF_STAT_MIN)
        .sort(
          (a, b) => a.npcState - b.npcState || a.name.localeCompare(b.name),
        ),
    [nationGenerals],
  );

  const candidatesByIntel = useMemo(
    () =>
      nationGenerals
        .filter((g) => g.officerLevel !== 12 && g.intel >= CHIEF_STAT_MIN)
        .sort(
          (a, b) => a.npcState - b.npcState || a.name.localeCompare(b.name),
        ),
    [nationGenerals],
  );

  const candidatesAny = useMemo(
    () =>
      nationGenerals
        .filter((g) => g.officerLevel !== 12)
        .sort(
          (a, b) => a.npcState - b.npcState || a.name.localeCompare(b.name),
        ),
    [nationGenerals],
  );

  // Ambassador/Auditor candidates (permission-based)
  const ambassadorCandidates = useMemo(
    () =>
      nationGenerals.filter(
        (g) =>
          g.officerLevel !== 12 &&
          (g.permission === "ambassador" || g.permission === "normal"),
      ),
    [nationGenerals],
  );

  const auditorCandidates = useMemo(
    () =>
      nationGenerals.filter(
        (g) =>
          g.officerLevel !== 12 &&
          (g.permission === "auditor" || g.permission === "normal"),
      ),
    [nationGenerals],
  );

  // Get candidates for a given officer level
  const getCandidatesForLevel = (level: number): General[] => {
    if (level === 11 || level === 2) return candidatesAny;
    // Even levels = strength-based (military), odd levels = intel-based (advisor)
    return level % 2 === 0 ? candidatesByStrength : candidatesByIntel;
  };

  const getCandidateColor = (g: General, targetLevel: number): string => {
    if (g.officerLevel === targetLevel) return "text-red-400";
    if (g.officerLevel > 1) return "text-orange-400";
    return "";
  };

  const handleAppoint = async (officerLevel: number) => {
    const generalId = appointSelections[officerLevel];
    if (!generalId || !myGeneral?.nationId) return;
    setAppointingLevel(officerLevel);
    setAppointMsg(null);
    try {
      await nationManagementApi.appointOfficer(myGeneral.nationId, {
        generalId: Number(generalId),
        officerLevel,
      });
      setAppointMsg(
        `${formatOfficerLevelText(officerLevel, nation?.level)} 임명 완료`,
      );
      await reload();
    } catch {
      setAppointMsg("임명에 실패했습니다.");
    } finally {
      setAppointingLevel(null);
    }
  };

  const handleCityAppoint = async (officerLevel: number) => {
    const sel = cityOfficerSelections[officerLevel];
    if (!sel?.generalId || !sel?.cityId || !myGeneral?.nationId) return;
    setAppointingLevel(officerLevel);
    setAppointMsg(null);
    try {
      await nationManagementApi.appointOfficer(myGeneral.nationId, {
        generalId: Number(sel.generalId),
        officerLevel,
        officerCity: Number(sel.cityId),
      });
      setAppointMsg("도시 관직 임명 완료");
      await reload();
    } catch {
      setAppointMsg("임명에 실패했습니다.");
    } finally {
      setAppointingLevel(null);
    }
  };

  const handlePermissionAppoint = async (
    permission: string,
    generalId: string,
  ) => {
    if (!generalId || !myGeneral?.nationId) return;
    setAppointMsg(null);
    try {
      await nationManagementApi.appointOfficer(myGeneral.nationId, {
        generalId: Number(generalId),
        officerLevel: 0, // Permission appointment, not officer level change
      });
      setAppointMsg(
        `${permission === "ambassador" ? "외교권자" : "조언자"} 임명 완료`,
      );
      await reload();
    } catch {
      setAppointMsg("임명에 실패했습니다.");
    }
  };

  const handleExpel = async () => {
    if (!expelSelection || !myGeneral?.nationId) return;
    if (!confirm("정말로 추방하시겠습니까?")) return;
    setAppointMsg(null);
    try {
      await nationManagementApi.expel(
        myGeneral.nationId,
        Number(expelSelection),
      );
      setAppointMsg("추방 완료");
      setExpelSelection("");
      await reload();
    } catch {
      setAppointMsg("추방에 실패했습니다.");
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (!myGeneral) return <LoadingState />;
  if (myGeneral.officerLevel < 5)
    return (
      <div className="p-4 text-muted-foreground">
        관직 Lv.5 이상만 사용 가능합니다.
      </div>
    );
  if (loading) return <LoadingState />;
  if (error) return <div className="p-4 text-destructive">{error}</div>;

  const npcGenerals = nationGenerals.filter((g) => g.npcState > 0);
  const playerGenerals = nationGenerals.filter((g) => g.npcState === 0);
  const minChiefLevel = nation ? getMinNationChiefLevel(nation.level) : 9;
  const totalCrew = nationGenerals.reduce((sum, g) => sum + g.crew, 0);
  const isChief = myGeneral.officerLevel === 12;

  return (
    <div className="p-4 space-y-4 max-w-3xl mx-auto">
      <PageHeader icon={Crown} title="사령부" />

      <Tabs defaultValue="chief">
        <TabsList>
          <TabsTrigger value="chief">사령부</TabsTrigger>
          <TabsTrigger value="personnel">인사부</TabsTrigger>
          <TabsTrigger value="generals">소속 장수</TabsTrigger>
        </TabsList>

        {/* ===== Tab 1: Chief Center (사령부) ===== */}
        <TabsContent value="chief" className="mt-4 space-y-4">
          {/* Nation Resources */}
          {nation && (
            <Card>
              <CardHeader>
                <CardTitle>{nation.name} 국가 자원</CardTitle>
              </CardHeader>
              <CardContent>
                <ResourceDisplay
                  gold={nation.gold}
                  rice={nation.rice}
                  crew={totalCrew}
                />
              </CardContent>
            </Card>
          )}

          <Tabs defaultValue="reservation" className="space-y-3">
            <TabsList>
              <TabsTrigger value="reservation">국가 명령 예약</TabsTrigger>
              <TabsTrigger value="execute">즉시 실행</TabsTrigger>
            </TabsList>

            <TabsContent value="reservation" className="space-y-3 mt-0">
              <Card>
                <CardHeader>
                  <CardTitle>국가 명령 예약 (12턴)</CardTitle>
                </CardHeader>
                <CardContent className="space-y-2">
                  <div className="space-y-[1px] bg-gray-600">
                    {Array.from({ length: NATION_TURN_COUNT }, (_, n) => n).map(
                      (slot) => {
                        const turn = getNationTurn(slot);
                        const isSelected = selectedNationSlots.has(slot);
                        const actionCode = turn?.actionCode ?? "휴식";
                        const brief = turn?.brief;
                        const isRest = actionCode === "휴식";
                        return (
                          <button
                            type="button"
                            key={slot}
                            onClick={(e) => handleNationSlotClick(slot, e)}
                            className={`flex w-full items-center gap-2 px-2 py-1.5 text-left text-xs transition-colors ${
                              isSelected
                                ? "bg-[#141c65] text-white"
                                : "bg-[#111] hover:bg-[#191919]"
                            }`}
                          >
                            <span className="w-6 shrink-0 tabular-nums text-gray-400">
                              #{slot + 1}
                            </span>
                            <span
                              className={`shrink-0 border px-1 py-0 text-[10px] ${
                                isRest
                                  ? "border-gray-600 text-gray-400"
                                  : "border-cyan-700 text-cyan-300"
                              }`}
                            >
                              {actionCode}
                            </span>
                            {brief && (
                              <span className="flex-1 truncate text-gray-300">
                                {brief}
                              </span>
                            )}
                          </button>
                        );
                      },
                    )}
                  </div>

                  <div className="text-[11px] text-gray-400">
                    {selectedNationSlots.size > 1
                      ? `${selectedNationSlots.size}개 턴 선택됨`
                      : "Shift+클릭: 범위선택, Ctrl/Cmd+클릭: 다중선택"}
                  </div>

                  {showNationReserveForm && (
                    <NationCommandSelectForm
                      commandTable={nationCommandTable}
                      reserving={reservingNation}
                      onReserve={handleNationReserve}
                      onCancel={() => setShowNationReserveForm(false)}
                    />
                  )}

                  {nationReserveResult && (
                    <div
                      className={`p-3 rounded text-sm ${nationReserveResult.success ? "bg-green-900/50 text-green-300" : "bg-red-900/50 text-red-300"}`}
                    >
                      <Badge
                        variant={
                          nationReserveResult.success
                            ? "secondary"
                            : "destructive"
                        }
                        className="mb-2"
                      >
                        {nationReserveResult.success ? "성공" : "실패"}
                      </Badge>
                      {nationReserveResult.logs.map((log) => (
                        <p key={log}>{log}</p>
                      ))}
                    </div>
                  )}
                </CardContent>
              </Card>
            </TabsContent>

            <TabsContent value="execute" className="space-y-3 mt-0">
              <Card>
                <CardHeader>
                  <CardTitle>국가 명령</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                  <div className="flex flex-wrap gap-2">
                    {nationCommandCategories.map((category) => (
                      <div key={category} className="w-full">
                        <div className="mb-1 text-xs text-gray-400">
                          {category}
                        </div>
                        <div className="flex flex-wrap gap-2">
                          {(nationCommandTable[category] ?? []).map((cmd) => (
                            <Button
                              key={cmd.actionCode}
                              variant={
                                selectedCmd?.actionCode === cmd.actionCode
                                  ? "default"
                                  : "outline"
                              }
                              size="sm"
                              disabled={!cmd.enabled}
                              title={cmd.reason}
                              onClick={() => {
                                if (!cmd.enabled) return;
                                setSelectedCmd((prev) =>
                                  prev?.actionCode === cmd.actionCode
                                    ? null
                                    : cmd,
                                );
                              }}
                            >
                              {cmd.name}
                            </Button>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                  {selectedCmd && (
                    <div className="rounded border p-4 text-sm space-y-3">
                      <p className="text-muted-foreground">
                        선택된 명령: <Badge>{selectedCmd.name}</Badge>
                      </p>
                      <p className="text-xs text-gray-400">
                        소모: {selectedCmd.commandPointCost}CP / 실행 지연:{" "}
                        {selectedCmd.durationSeconds}초
                      </p>
                      <Button
                        onClick={async () => {
                          if (!myGeneral) return;
                          setExecuting(true);
                          setCmdResult(null);
                          try {
                            const { data } = await commandApi.executeNation(
                              myGeneral.id,
                              selectedCmd.actionCode,
                            );
                            setCmdResult(data);
                          } catch {
                            setCmdResult({
                              success: false,
                              logs: ["실행 중 오류가 발생했습니다."],
                            });
                          } finally {
                            setExecuting(false);
                          }
                        }}
                        disabled={executing}
                        size="sm"
                      >
                        {executing ? "실행중..." : "명령 실행"}
                      </Button>
                      {cmdResult && (
                        <div
                          className={`p-3 rounded text-sm ${cmdResult.success ? "bg-green-900/50 text-green-300" : "bg-red-900/50 text-red-300"}`}
                        >
                          <Badge
                            variant={
                              cmdResult.success ? "secondary" : "destructive"
                            }
                            className="mb-2"
                          >
                            {cmdResult.success ? "성공" : "실패"}
                          </Badge>
                          {cmdResult.logs.map((log) => (
                            <p key={log}>{log}</p>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </CardContent>
              </Card>
            </TabsContent>
          </Tabs>
        </TabsContent>

        {/* ===== Tab 2: Personnel (인사부) ===== */}
        <TabsContent value="personnel" className="mt-4 space-y-4">
          {appointMsg && <p className="text-sm text-green-400">{appointMsg}</p>}

          {/* Officer Hierarchy Display */}
          {nation && (
            <Card>
              <CardHeader>
                <CardTitle
                  className="text-center"
                  style={{
                    color: nation.color,
                  }}
                >
                  【 {nation.name} 】 관직표
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="space-y-1">
                  {Array.from(
                    { length: Math.floor((12 - minChiefLevel) / 2) + 1 },
                    (_, i) => {
                      const lv1 = 12 - i * 2;
                      const lv2 = lv1 - 1;
                      const g1 = officerMap[lv1];
                      const g2 =
                        lv2 >= minChiefLevel ? officerMap[lv2] : undefined;
                      return (
                        <div
                          key={lv1}
                          className="grid grid-cols-2 gap-2 text-sm"
                        >
                          {/* Left column */}
                          <div className="flex items-center gap-2 rounded bg-muted px-3 py-2">
                            <Badge
                              variant="outline"
                              className="min-w-[70px] justify-center text-xs"
                            >
                              {formatOfficerLevelText(lv1, nation.level)}
                            </Badge>
                            {g1 ? (
                              <>
                                <GeneralPortrait
                                  picture={g1.picture}
                                  name={g1.name}
                                  size="sm"
                                />
                                <span className="truncate">{g1.name}</span>
                                <span className="text-xs text-muted-foreground">
                                  ({g1.belong}년)
                                </span>
                              </>
                            ) : (
                              <span className="text-muted-foreground">-</span>
                            )}
                          </div>
                          {/* Right column */}
                          {lv2 >= minChiefLevel ? (
                            <div className="flex items-center gap-2 rounded bg-muted px-3 py-2">
                              <Badge
                                variant="outline"
                                className="min-w-[70px] justify-center text-xs"
                              >
                                {formatOfficerLevelText(lv2, nation.level)}
                              </Badge>
                              {g2 ? (
                                <>
                                  <GeneralPortrait
                                    picture={g2.picture}
                                    name={g2.name}
                                    size="sm"
                                  />
                                  <span className="truncate">{g2.name}</span>
                                  <span className="text-xs text-muted-foreground">
                                    ({g2.belong}년)
                                  </span>
                                </>
                              ) : (
                                <span className="text-muted-foreground">-</span>
                              )}
                            </div>
                          ) : (
                            <div />
                          )}
                        </div>
                      );
                    },
                  )}
                </div>
              </CardContent>
            </Card>
          )}

          {/* Officer Appointment - only for level >= 5 */}
          {myGeneral.officerLevel >= 5 && nation && (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <UserCog className="size-4" />
                  수뇌부 임명
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {/* Level 11 */}
                <div className="flex items-center gap-2">
                  <Badge
                    variant="secondary"
                    className="min-w-[70px] justify-center text-xs"
                  >
                    {formatOfficerLevelText(11, nation.level)}
                  </Badge>
                  <Select
                    value={appointSelections[11] ?? ""}
                    onValueChange={(v) =>
                      setAppointSelections((p) => ({ ...p, 11: v }))
                    }
                  >
                    <SelectTrigger size="sm" className="flex-1 min-w-[200px]">
                      <SelectValue placeholder="장수 선택..." />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="0">공석</SelectItem>
                      {getCandidatesForLevel(11).map((g) => (
                        <SelectItem
                          key={g.id}
                          value={String(g.id)}
                          className={getCandidateColor(g, 11)}
                        >
                          {g.name} 【{cityMap.get(g.cityId)?.name ?? g.cityId}】
                          {g.officerLevel > 1 && (
                            <span className="text-xs text-muted-foreground ml-1">
                              (Lv.{g.officerLevel})
                            </span>
                          )}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Button
                    size="sm"
                    onClick={() => handleAppoint(11)}
                    disabled={appointingLevel === 11 || !appointSelections[11]}
                  >
                    임명
                  </Button>
                </div>

                {/* Levels 10 down to minChiefLevel */}
                {Array.from(
                  { length: 10 - minChiefLevel + 1 },
                  (_, i) => 10 - i,
                ).map((level) => {
                  const candidates = getCandidatesForLevel(level);
                  const statLabel = level % 2 === 0 ? "무력" : "지력";
                  return (
                    <div key={level} className="flex items-center gap-2">
                      <Badge
                        variant="secondary"
                        className="min-w-[70px] justify-center text-xs"
                      >
                        {formatOfficerLevelText(level, nation.level)}
                      </Badge>
                      <Select
                        value={appointSelections[level] ?? ""}
                        onValueChange={(v) =>
                          setAppointSelections((p) => ({ ...p, [level]: v }))
                        }
                      >
                        <SelectTrigger
                          size="sm"
                          className="flex-1 min-w-[200px]"
                        >
                          <SelectValue
                            placeholder={`${statLabel} ${CHIEF_STAT_MIN}+ 장수...`}
                          />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="0">공석</SelectItem>
                          {candidates.map((g) => (
                            <SelectItem
                              key={g.id}
                              value={String(g.id)}
                              className={getCandidateColor(g, level)}
                            >
                              {g.name} 【
                              {cityMap.get(g.cityId)?.name ?? g.cityId}】
                              {g.officerLevel > 1 && ` (Lv.${g.officerLevel})`}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <Button
                        size="sm"
                        onClick={() => handleAppoint(level)}
                        disabled={
                          appointingLevel === level || !appointSelections[level]
                        }
                      >
                        임명
                      </Button>
                    </div>
                  );
                })}

                <p className="text-xs text-muted-foreground">
                  <span className="text-red-400">빨간색</span>은 현재 임명중인
                  장수, <span className="text-orange-400">노란색</span>은 다른
                  관직에 임명된 장수
                </p>
              </CardContent>
            </Card>
          )}

          {/* Ambassador / Auditor - chief (level 12) only */}
          {isChief && (
            <Card>
              <CardHeader>
                <CardTitle>외교권자 / 조언자 임명</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="flex items-center gap-2">
                  <Badge
                    variant="secondary"
                    className="min-w-[70px] justify-center text-xs"
                  >
                    외교권자
                  </Badge>
                  <Select
                    value={ambassadorSelection}
                    onValueChange={setAmbassadorSelection}
                  >
                    <SelectTrigger size="sm" className="flex-1 min-w-[200px]">
                      <SelectValue placeholder="장수 선택..." />
                    </SelectTrigger>
                    <SelectContent>
                      {ambassadorCandidates.map((g) => (
                        <SelectItem key={g.id} value={String(g.id)}>
                          {g.name}
                          {g.permission === "ambassador" && " (현재 외교권자)"}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Button
                    size="sm"
                    onClick={() =>
                      handlePermissionAppoint("ambassador", ambassadorSelection)
                    }
                    disabled={!ambassadorSelection}
                  >
                    임명
                  </Button>
                </div>
                <div className="flex items-center gap-2">
                  <Badge
                    variant="secondary"
                    className="min-w-[70px] justify-center text-xs"
                  >
                    조언자
                  </Badge>
                  <Select
                    value={auditorSelection}
                    onValueChange={setAuditorSelection}
                  >
                    <SelectTrigger size="sm" className="flex-1 min-w-[200px]">
                      <SelectValue placeholder="장수 선택..." />
                    </SelectTrigger>
                    <SelectContent>
                      {auditorCandidates.map((g) => (
                        <SelectItem key={g.id} value={String(g.id)}>
                          {g.name}
                          {g.permission === "auditor" && " (현재 조언자)"}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Button
                    size="sm"
                    onClick={() =>
                      handlePermissionAppoint("auditor", auditorSelection)
                    }
                    disabled={!auditorSelection}
                  >
                    임명
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {/* City Officer Appointments (levels 2-4) */}
          {myGeneral.officerLevel >= 5 && nation && (
            <Card>
              <CardHeader>
                <CardTitle>도시 관직 임명</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {[4, 3, 2].map((level) => {
                  const sel = cityOfficerSelections[level] ?? {
                    cityId: "",
                    generalId: "",
                  };
                  const candidates =
                    level === 4
                      ? candidatesByStrength
                      : level === 3
                        ? candidatesByIntel
                        : candidatesAny;
                  return (
                    <div key={level} className="flex items-center gap-2">
                      <Badge
                        variant="secondary"
                        className="min-w-[50px] justify-center text-xs"
                      >
                        {formatOfficerLevelText(level, nation.level)}
                      </Badge>
                      <Select
                        value={sel.cityId}
                        onValueChange={(v) =>
                          setCityOfficerSelections((p) => ({
                            ...p,
                            [level]: { ...sel, cityId: v },
                          }))
                        }
                      >
                        <SelectTrigger size="sm" className="min-w-[120px]">
                          <SelectValue placeholder="도시..." />
                        </SelectTrigger>
                        <SelectContent>
                          {Object.entries(citiesByRegion).map(
                            ([region, cities]) => (
                              <SelectGroup key={region}>
                                <SelectLabel>
                                  {REGION_NAMES[Number(region)] ??
                                    `지역 ${region}`}
                                </SelectLabel>
                                {cities.map((c) => (
                                  <SelectItem key={c.id} value={String(c.id)}>
                                    {c.name}
                                  </SelectItem>
                                ))}
                              </SelectGroup>
                            ),
                          )}
                        </SelectContent>
                      </Select>
                      <Select
                        value={sel.generalId}
                        onValueChange={(v) =>
                          setCityOfficerSelections((p) => ({
                            ...p,
                            [level]: { ...sel, generalId: v },
                          }))
                        }
                      >
                        <SelectTrigger
                          size="sm"
                          className="flex-1 min-w-[150px]"
                        >
                          <SelectValue placeholder="장수..." />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="0">공석</SelectItem>
                          {candidates.map((g) => (
                            <SelectItem
                              key={g.id}
                              value={String(g.id)}
                              className={getCandidateColor(g, level)}
                            >
                              {g.name} 【
                              {cityMap.get(g.cityId)?.name ?? g.cityId}】
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <Button
                        size="sm"
                        onClick={() => handleCityAppoint(level)}
                        disabled={
                          appointingLevel === level ||
                          !sel.cityId ||
                          !sel.generalId
                        }
                      >
                        임명
                      </Button>
                    </div>
                  );
                })}
              </CardContent>
            </Card>
          )}

          {/* City Officer List */}
          {nation && nationCities.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle>도시별 관직 현황</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-gray-700">
                        <th className="px-2 py-1 text-left">도시</th>
                        <th className="px-2 py-1 text-left">
                          {formatOfficerLevelText(4, nation.level)}
                        </th>
                        <th className="px-2 py-1 text-left">
                          {formatOfficerLevelText(3, nation.level)}
                        </th>
                        <th className="px-2 py-1 text-left">
                          {formatOfficerLevelText(2, nation.level)}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {Object.entries(citiesByRegion).map(
                        ([region, cities]) => (
                          <>
                            <tr key={`r-${region}`}>
                              <td
                                colSpan={4}
                                className="px-2 py-1 text-xs font-medium text-sky-400"
                              >
                                【{" "}
                                {REGION_NAMES[Number(region)] ??
                                  `지역 ${region}`}{" "}
                                】
                              </td>
                            </tr>
                            {cities.map((c) => {
                              const officers = cityOfficerMap.get(c.id) ?? {};
                              return (
                                <tr
                                  key={c.id}
                                  className="border-b border-gray-800"
                                >
                                  <td
                                    className="px-2 py-1 font-medium"
                                    style={{ color: nation.color }}
                                  >
                                    {c.name}
                                  </td>
                                  {[4, 3, 2].map((lv) => {
                                    const officer = officers[lv];
                                    return (
                                      <td
                                        key={lv}
                                        className="px-2 py-1 text-xs"
                                      >
                                        {officer
                                          ? `${officer.name} (${officer.belong}년)`
                                          : "-"}
                                      </td>
                                    );
                                  })}
                                </tr>
                              );
                            })}
                          </>
                        ),
                      )}
                    </tbody>
                  </table>
                </div>
              </CardContent>
            </Card>
          )}

          {/* Expulsion */}
          {myGeneral.officerLevel >= 5 && (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2 text-red-400">
                  <Ban className="size-4" />
                  추방
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="flex items-center gap-2">
                  <Select
                    value={expelSelection}
                    onValueChange={setExpelSelection}
                  >
                    <SelectTrigger size="sm" className="flex-1 min-w-[200px]">
                      <SelectValue placeholder="대상 장수 선택..." />
                    </SelectTrigger>
                    <SelectContent>
                      {candidatesAny
                        .filter((g) => g.id !== myGeneral.id)
                        .map((g) => (
                          <SelectItem key={g.id} value={String(g.id)}>
                            {g.name} ({g.leadership}/{g.strength}/{g.intel})
                            {g.killTurn != null && ` ${g.killTurn}턴`}
                          </SelectItem>
                        ))}
                    </SelectContent>
                  </Select>
                  <Button
                    size="sm"
                    variant="destructive"
                    onClick={handleExpel}
                    disabled={!expelSelection}
                  >
                    추방
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}
        </TabsContent>

        {/* ===== Tab 3: Generals List ===== */}
        <TabsContent value="generals" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Users className="size-4" />
                소속 장수 (플레이어 {playerGenerals.length}명 / NPC{" "}
                {npcGenerals.length}명)
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-700 text-xs text-muted-foreground">
                      <th className="px-2 py-1 text-left">장수</th>
                      <th className="px-2 py-1 text-left">관직</th>
                      <th className="px-2 py-1 text-left">도시</th>
                      <th className="px-2 py-1 text-right">병력</th>
                      <th className="px-2 py-1 text-right">병종</th>
                      <th className="px-2 py-1 text-right">훈련</th>
                      <th className="px-2 py-1 text-right">사기</th>
                    </tr>
                  </thead>
                  <tbody>
                    {nationGenerals.map((g) => {
                      const city = cityMap.get(g.cityId);
                      return (
                        <tr key={g.id} className="border-b border-gray-800">
                          <td className="px-2 py-1">
                            <div className="flex items-center gap-2">
                              <GeneralPortrait
                                picture={g.picture}
                                name={g.name}
                                size="sm"
                              />
                              <span className="font-medium truncate max-w-[80px]">
                                {g.name}
                              </span>
                              {g.npcState > 0 && (
                                <Badge
                                  variant="outline"
                                  className="text-[10px] px-1"
                                >
                                  NPC
                                </Badge>
                              )}
                            </div>
                          </td>
                          <td className="px-2 py-1 text-xs">
                            {formatOfficerLevelText(
                              g.officerLevel,
                              nation?.level,
                            )}
                          </td>
                          <td className="px-2 py-1 text-xs">
                            {city?.name ?? "-"}
                          </td>
                          <td className="px-2 py-1 text-xs text-right tabular-nums">
                            {g.crew.toLocaleString()}
                          </td>
                          <td className="px-2 py-1 text-xs text-right">
                            {CREW_TYPE_NAMES[g.crewType] ?? g.crewType}
                          </td>
                          <td className="px-2 py-1 text-xs text-right tabular-nums">
                            {g.train}
                          </td>
                          <td className="px-2 py-1 text-xs text-right tabular-nums">
                            {g.atmos}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}

interface NationCommandSelectFormProps {
  commandTable: Record<string, CommandTableEntry[]>;
  reserving: boolean;
  onReserve: (actionCode: string, arg?: Record<string, unknown>) => void;
  onCancel: () => void;
}

function NationCommandSelectForm({
  commandTable,
  reserving,
  onReserve,
  onCancel,
}: NationCommandSelectFormProps) {
  const [selectedCmd, setSelectedCmd] = useState("");
  const [pendingArg, setPendingArg] = useState<
    Record<string, unknown> | undefined
  >();

  const categories = Object.keys(commandTable);
  const hasArgForm = !!(selectedCmd && COMMAND_ARGS[selectedCmd]);

  const selectedEntry = useMemo(() => {
    for (const list of Object.values(commandTable)) {
      const found = list.find((cmd) => cmd.actionCode === selectedCmd);
      if (found) return found;
    }
    return null;
  }, [commandTable, selectedCmd]);

  const handleReserve = () => {
    if (!selectedCmd) return;
    onReserve(selectedCmd, pendingArg);
  };

  return (
    <Card className="border-amber-400/30">
      <CardContent className="space-y-3 pt-3">
        <Tabs defaultValue={categories[0] ?? ""}>
          <TabsList className="flex-wrap h-auto">
            {categories.map((cat) => (
              <TabsTrigger key={cat} value={cat} className="text-xs">
                {cat}
              </TabsTrigger>
            ))}
          </TabsList>
          {categories.map((cat) => (
            <TabsContent key={cat} value={cat}>
              <div className="flex flex-wrap gap-1">
                {commandTable[cat].map((cmd) => (
                  <Badge
                    key={cmd.actionCode}
                    variant={
                      selectedCmd === cmd.actionCode ? "default" : "secondary"
                    }
                    className={`cursor-pointer text-xs ${
                      !cmd.enabled ? "opacity-40 cursor-not-allowed" : ""
                    }`}
                    title={cmd.reason ?? undefined}
                    onClick={() => {
                      if (!cmd.enabled) return;
                      setSelectedCmd(cmd.actionCode);
                      setPendingArg(undefined);
                    }}
                  >
                    {cmd.name}
                  </Badge>
                ))}
              </div>
            </TabsContent>
          ))}
        </Tabs>

        {selectedEntry && (
          <div className="rounded border p-3 text-xs text-muted-foreground">
            <p>
              소모: {selectedEntry.commandPointCost}CP / 실행 지연:{" "}
              {selectedEntry.durationSeconds}초
            </p>
          </div>
        )}

        {hasArgForm && (
          <CommandArgForm actionCode={selectedCmd} onSubmit={setPendingArg} />
        )}

        <div className="flex gap-2">
          <Button
            size="sm"
            onClick={handleReserve}
            disabled={!selectedCmd || reserving || (hasArgForm && !pendingArg)}
          >
            {reserving ? "저장중..." : "예약"}
          </Button>
          <Button size="sm" variant="ghost" onClick={onCancel}>
            취소
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
