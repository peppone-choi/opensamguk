"use client";

import React, { useEffect, useMemo, useState, useCallback } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import { Shield, UserPlus, UserMinus, Award } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatOfficerLevelText } from "@/lib/game-utils";
import { nationManagementApi } from "@/lib/gameApi";
import type { General, Nation } from "@/types";

function PermissionSelector({
  label,
  permType,
  maxSlots,
  nationGenerals: nGens,
  nation: nat,
  myGeneral: myGen,
  actionLoading: aLoading,
  setActionLoading: setALoading,
  setMessage: setMsg,
  currentWorld: cw,
  loadAll: la,
}: {
  label: string;
  permType: "ambassador" | "auditor";
  maxSlots: number;
  nationGenerals: General[];
  nation: Nation | null | undefined;
  myGeneral: General;
  actionLoading: boolean;
  setActionLoading: (v: boolean) => void;
  setMessage: (v: { text: string; type: "success" | "error" } | null) => void;
  currentWorld: { id: number } | null;
  loadAll: (worldId: number) => Promise<void>;
}) {
  const current = nGens.filter((g) => g.permission === permType);
  const [selected, setSelected] = React.useState<number[]>([]);

  React.useEffect(() => {
    setSelected(current.map((g) => g.id));
  }, [nGens]); // eslint-disable-line react-hooks/exhaustive-deps

  const candidates = nGens.filter(
    (g) => g.officerLevel !== 12 && g.id !== myGen.id,
  );

  const handleSave = async () => {
    if (!nat) return;
    setALoading(true);
    setMsg(null);
    try {
      await nationManagementApi.setPermission(nat.id, {
        requesterId: myGen.id,
        isAmbassador: permType === "ambassador",
        generalIds: selected,
      });
      setMsg({ text: `${label} 변경 완료`, type: "success" });
      if (cw) await la(cw.id);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : `${label} 변경 실패`;
      setMsg({ text: msg, type: "error" });
    } finally {
      setALoading(false);
    }
  };

  const toggleGeneral = (gid: number) => {
    setSelected((prev) => {
      if (prev.includes(gid)) return prev.filter((id) => id !== gid);
      if (prev.length >= maxSlots) return prev;
      return [...prev, gid];
    });
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <label className="text-sm font-medium">{label} (최대 {maxSlots}명)</label>
        <Button
          size="sm"
          variant="outline"
          disabled={aLoading}
          onClick={handleSave}
        >
          변경
        </Button>
      </div>
      <div className="flex flex-wrap gap-1">
        {candidates.map((g) => {
          const isSelected = selected.includes(g.id);
          return (
            <Badge
              key={g.id}
              variant={isSelected ? "default" : "outline"}
              className={`cursor-pointer transition-colors ${
                isSelected
                  ? "bg-purple-600 hover:bg-purple-700"
                  : "hover:bg-muted/50"
              }`}
              onClick={() => toggleGeneral(g.id)}
            >
              {g.name}
            </Badge>
          );
        })}
        {candidates.length === 0 && (
          <span className="text-xs text-muted-foreground">대상 장수 없음</span>
        )}
      </div>
      {current.length > 0 && (
        <div className="text-xs text-muted-foreground">
          현재: {current.map((g) => g.name).join(", ")}
        </div>
      )}
    </div>
  );
}

type OfficerSlot = {
  level: number;
  label: string;
  general: General | null;
  isLocked: boolean;
  statRequirement: "strength" | "intel" | "any";
};

// Minimum chief officer level based on nation level
function getNationChiefLevel(nationLevel: number): number {
  if (nationLevel >= 7) return 1;
  if (nationLevel >= 5) return 3;
  if (nationLevel >= 3) return 5;
  if (nationLevel >= 1) return 7;
  return 12;
}

export default function SuperiorPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { myGeneral, fetchMyGeneral } = useGeneralStore();
  const { generals, nations, cities, loading, loadAll } = useGameStore();
  const [actionLoading, setActionLoading] = useState(false);
  const [appointLevel, setAppointLevel] = useState<number>(0);
  const [appointGeneral, setAppointGeneral] = useState<number>(0);
  const [appointCity, setAppointCity] = useState<number>(0);
  const [kickTarget, setKickTarget] = useState<number>(0);
  const [message, setMessage] = useState<{
    text: string;
    type: "success" | "error";
  } | null>(null);

  useEffect(() => {
    if (!currentWorld) return;
    loadAll(currentWorld.id);
    if (!myGeneral) fetchMyGeneral(currentWorld.id).catch(() => {});
  }, [currentWorld, myGeneral, fetchMyGeneral, loadAll]);

  const nation = useMemo(
    () => (myGeneral ? nations.find((n) => n.id === myGeneral.nationId) : null),
    [myGeneral, nations],
  );

  const cityMap = useMemo(
    () => new Map(cities.map((c) => [c.id, c])),
    [cities],
  );

  const meLevel = myGeneral?.officerLevel ?? 0;
  const nationLevel = nation?.level ?? 0;
  const minChiefLevel = getNationChiefLevel(nationLevel);
  const canManage = meLevel >= 5;
  const isKing = meLevel === 12;

  // All generals in my nation (excluding the king for appointment candidates)
  const nationGenerals = useMemo(() => {
    if (!myGeneral || myGeneral.nationId <= 0) return [];
    return generals.filter((g) => g.nationId === myGeneral.nationId);
  }, [myGeneral, generals]);

  // Candidates for officer appointment
  const candidatesStrength = useMemo(
    () =>
      nationGenerals.filter(
        (g) => g.officerLevel !== 12 && g.strength >= 40,
      ),
    [nationGenerals],
  );

  const candidatesIntel = useMemo(
    () =>
      nationGenerals.filter(
        (g) => g.officerLevel !== 12 && g.intel >= 40,
      ),
    [nationGenerals],
  );

  const candidatesAny = useMemo(
    () => nationGenerals.filter((g) => g.officerLevel !== 12),
    [nationGenerals],
  );

  // Officer slots for the nation
  const officerSlots = useMemo<OfficerSlot[]>(() => {
    const slots: OfficerSlot[] = [];
    const generalByLevel = new Map<number, General>();
    nationGenerals.forEach((g) => {
      if (g.officerLevel > 0) {
        generalByLevel.set(g.officerLevel, g);
      }
    });

    for (let level = 12; level >= minChiefLevel; level--) {
      const statReq: "strength" | "intel" | "any" =
        level === 11
          ? "any"
          : level % 2 === 0
            ? "strength"
            : "intel";
      slots.push({
        level,
        label: formatOfficerLevelText(level, nationLevel),
        general: generalByLevel.get(level) ?? null,
        isLocked: level === 12, // King slot is always locked
        statRequirement: statReq,
      });
    }
    return slots;
  }, [nationGenerals, minChiefLevel, nationLevel]);

  // Five Tiger Generals (오호장군 — top 5 kill count)
  const tigers = useMemo(() => {
    return [...nationGenerals]
      .filter((g) => {
        const v = g.meta?.killNum;
        return typeof v === "number" && v > 0;
      })
      .sort((a, b) => {
        const av = (a.meta?.killNum as number) ?? 0;
        const bv = (b.meta?.killNum as number) ?? 0;
        return bv - av;
      })
      .slice(0, 5)
      .map((g) => ({
        name: g.name,
        value: (g.meta?.killNum as number) ?? 0,
      }));
  }, [nationGenerals]);

  // Seven Scholars (건안칠자 — top 7 fire/stratagem count)
  const eagles = useMemo(() => {
    return [...nationGenerals]
      .filter((g) => {
        const v = g.meta?.fireNum;
        return typeof v === "number" && v > 0;
      })
      .sort((a, b) => {
        const av = (a.meta?.fireNum as number) ?? 0;
        const bv = (b.meta?.fireNum as number) ?? 0;
        return bv - av;
      })
      .slice(0, 7)
      .map((g) => ({
        name: g.name,
        value: (g.meta?.fireNum as number) ?? 0,
      }));
  }, [nationGenerals]);

  // City officer list
  const cityOfficers = useMemo(() => {
    const nationCities = cities.filter(
      (c) => c.nationId === myGeneral?.nationId,
    );
    const officersByCityAndLevel = new Map<string, General>();
    nationGenerals
      .filter((g) => g.officerLevel >= 2 && g.officerLevel <= 4)
      .forEach((g) => {
        officersByCityAndLevel.set(`${g.officerCity}-${g.officerLevel}`, g);
      });

    return nationCities
      .sort((a, b) => {
        const regionCmp = (a.region ?? 0) - (b.region ?? 0);
        if (regionCmp !== 0) return regionCmp;
        return (b.level ?? 0) - (a.level ?? 0);
      })
      .map((city) => ({
        city,
        officers: [4, 3, 2].map(
          (lvl) => officersByCityAndLevel.get(`${city.id}-${lvl}`) ?? null,
        ),
      }));
  }, [cities, nationGenerals, myGeneral]);

  const handleAppoint = useCallback(
    async (level: number, generalId: number, officerCity?: number) => {
      if (!nation || !generalId) return;
      setActionLoading(true);
      setMessage(null);
      try {
        await nationManagementApi.appointOfficer(nation.id, {
          generalId,
          officerLevel: level,
          officerCity,
        });
        setMessage({ text: "임명 완료", type: "success" });
        if (currentWorld) {
          await loadAll(currentWorld.id);
        }
      } catch (err: unknown) {
        const msg =
          err instanceof Error ? err.message : "임명에 실패했습니다.";
        setMessage({ text: msg, type: "error" });
      } finally {
        setActionLoading(false);
      }
    },
    [nation, currentWorld, loadAll],
  );

  const handleExpel = useCallback(
    async (generalId: number) => {
      if (!nation || !generalId) return;
      if (!window.confirm("정말 추방하시겠습니까?")) return;
      setActionLoading(true);
      setMessage(null);
      try {
        await nationManagementApi.expel(nation.id, generalId);
        setMessage({ text: "추방 완료", type: "success" });
        if (currentWorld) {
          await loadAll(currentWorld.id);
        }
      } catch (err: unknown) {
        const msg =
          err instanceof Error ? err.message : "추방에 실패했습니다.";
        setMessage({ text: msg, type: "error" });
      } finally {
        setActionLoading(false);
      }
    },
    [nation, currentWorld, loadAll],
  );

  const getCandidatesForLevel = (level: number): General[] => {
    if (level === 11) return candidatesAny;
    if (level % 2 === 0) return candidatesStrength;
    return candidatesIntel;
  };

  const getStatLabel = (level: number): string => {
    if (level === 11) return "모든 장수";
    if (level % 2 === 0) return "무력 40+";
    return "지력 40+";
  };

  if (!currentWorld) {
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  }

  if (loading) return <LoadingState />;

  if (!myGeneral) {
    return (
      <div className="p-4 text-muted-foreground">장수 정보가 없습니다.</div>
    );
  }

  if (myGeneral.nationId <= 0) {
    return (
      <div className="p-4 text-muted-foreground">재야입니다.</div>
    );
  }

  return (
    <div className="p-4 space-y-4 max-w-5xl mx-auto">
      <PageHeader icon={Shield} title="인사부" />

      {message && (
        <div
          className={`p-3 rounded text-sm ${
            message.type === "success"
              ? "bg-green-500/10 text-green-400 border border-green-500/30"
              : "bg-red-500/10 text-red-400 border border-red-500/30"
          }`}
        >
          {message.text}
        </div>
      )}

      {/* Nation Header */}
      <Card>
        <CardContent className="py-3">
          <div
            className="text-center text-xl font-bold py-2 rounded"
            style={{
              backgroundColor: nation?.color ?? "#333",
              color: "#fff",
            }}
          >
            【 {nation?.name ?? "?"} 】
          </div>
        </CardContent>
      </Card>

      {/* Officer Display — portraits paired */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base flex items-center gap-2">
            <Award className="size-4" />
            수뇌부
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {(() => {
            const rows: React.ReactElement[] = [];
            for (let i = 12; i >= minChiefLevel; i -= 2) {
              const slot1 = officerSlots.find((s) => s.level === i);
              const slot2 = officerSlots.find((s) => s.level === i - 1);
              rows.push(
                <div
                  key={i}
                  className="grid grid-cols-2 gap-2 border-b border-muted/30 pb-2"
                >
                  {[slot1, slot2].map((slot, idx) => {
                    if (!slot) return <div key={idx} />;
                    return (
                      <div key={slot.level} className="flex items-center gap-2">
                        <Badge
                          variant="outline"
                          className="w-20 justify-center shrink-0 text-xs"
                        >
                          {slot.label}
                        </Badge>
                        {slot.general ? (
                          <div className="flex items-center gap-2">
                            <GeneralPortrait
                              picture={slot.general.picture}
                              name={slot.general.name}
                              size="sm"
                            />
                            <span className="text-sm">
                              {slot.general.name}
                              <span className="text-xs text-muted-foreground ml-1">
                                ({slot.general.belong ?? "-"}년)
                              </span>
                            </span>
                          </div>
                        ) : (
                          <span className="text-sm text-muted-foreground">
                            -
                          </span>
                        )}
                      </div>
                    );
                  })}
                </div>,
              );
            }
            return rows;
          })()}

          {/* Tigers & Eagles */}
          {tigers.length > 0 && (
            <div className="flex items-start gap-2 text-sm">
              <Badge variant="outline" className="w-20 justify-center shrink-0">
                오호장군
              </Badge>
              <span>
                {tigers
                  .map((t) => `${t.name}【${t.value.toLocaleString()}】`)
                  .join(", ")}
              </span>
            </div>
          )}
          {eagles.length > 0 && (
            <div className="flex items-start gap-2 text-sm">
              <Badge variant="outline" className="w-20 justify-center shrink-0">
                건안칠자
              </Badge>
              <span>
                {eagles
                  .map((e) => `${e.name}【${e.value.toLocaleString()}】`)
                  .join(", ")}
              </span>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Officer Appointment (수뇌부 임명) — only for level >= 5 */}
      {canManage && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base flex items-center gap-2 text-blue-400">
              <UserPlus className="size-4" />
              수뇌부 임명
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {officerSlots
              .filter((slot) => slot.level !== 12 && slot.level >= 5)
              .map((slot) => {
                const candidates = getCandidatesForLevel(slot.level);
                return (
                  <div
                    key={slot.level}
                    className="flex flex-wrap items-center gap-2 border-b border-muted/20 pb-2"
                  >
                    <Badge
                      variant="outline"
                      className="w-20 justify-center shrink-0 text-xs"
                    >
                      {slot.label}
                    </Badge>
                    <span className="text-[10px] text-muted-foreground">
                      ({getStatLabel(slot.level)})
                    </span>
                    <select
                      className="flex-1 min-w-[200px] h-8 rounded border border-input bg-background px-2 text-sm"
                      defaultValue={slot.general?.id ?? 0}
                      onChange={(e) => {
                        setAppointLevel(slot.level);
                        setAppointGeneral(Number(e.target.value));
                      }}
                    >
                      <option value={0}>____공석____</option>
                      {candidates.map((g) => (
                        <option
                          key={g.id}
                          value={g.id}
                          style={{
                            color:
                              g.officerLevel === slot.level
                                ? "#ef4444"
                                : g.officerLevel > 1
                                  ? "#f97316"
                                  : undefined,
                          }}
                        >
                          {g.name} 【
                          {cityMap.get(g.cityId)?.name ?? "?"}】
                          {g.officerLevel === slot.level
                            ? " (현재)"
                            : g.officerLevel > 1
                              ? ` (${formatOfficerLevelText(g.officerLevel, nationLevel)})`
                              : ""}
                        </option>
                      ))}
                    </select>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={actionLoading}
                      onClick={() => {
                        if (appointLevel === slot.level && appointGeneral > 0) {
                          handleAppoint(slot.level, appointGeneral);
                        }
                      }}
                    >
                      임명
                    </Button>
                  </div>
                );
              })}
            <p className="text-xs text-muted-foreground">
              ※{" "}
              <span className="text-red-400">빨간색</span>은 현재 임명중인
              장수,{" "}
              <span className="text-orange-400">노란색</span>은 다른 관직에
              임명된 장수, 흰색은 일반 장수를 뜻합니다.
            </p>
          </CardContent>
        </Card>
      )}

      {/* City Officer Appointment (도시 관직 임명) */}
      {canManage && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base flex items-center gap-2 text-orange-400">
              <UserPlus className="size-4" />
              도시 관직 임명
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {[4, 3, 2].map((level) => {
              const levelLabel = formatOfficerLevelText(level, nationLevel);
              const candidates =
                level === 3
                  ? candidatesIntel
                  : level === 4
                    ? candidatesStrength
                    : candidatesAny;
              const nationCities = cities.filter(
                (c) => c.nationId === myGeneral.nationId,
              );
              return (
                <div
                  key={level}
                  className="flex flex-wrap items-center gap-2 border-b border-muted/20 pb-2"
                >
                  <Badge
                    variant="outline"
                    className="w-16 justify-center shrink-0 text-xs"
                  >
                    {levelLabel}
                  </Badge>
                  <select
                    className="h-8 rounded border border-input bg-background px-2 text-sm min-w-[140px]"
                    onChange={(e) => setAppointCity(Number(e.target.value))}
                  >
                    {nationCities.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </select>
                  <select
                    className="flex-1 min-w-[180px] h-8 rounded border border-input bg-background px-2 text-sm"
                    onChange={(e) => setAppointGeneral(Number(e.target.value))}
                  >
                    <option value={0}>____공석____</option>
                    {candidates.map((g) => (
                      <option
                        key={g.id}
                        value={g.id}
                        style={{
                          color:
                            g.officerLevel === level
                              ? "#ef4444"
                              : g.officerLevel > 1
                                ? "#f97316"
                                : undefined,
                        }}
                      >
                        {g.name} 【{cityMap.get(g.cityId)?.name ?? "?"}】
                      </option>
                    ))}
                  </select>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={actionLoading}
                    onClick={() => {
                      if (appointGeneral > 0 && appointCity > 0) {
                        handleAppoint(level, appointGeneral, appointCity);
                      }
                    }}
                  >
                    임명
                  </Button>
                </div>
              );
            })}
          </CardContent>
        </Card>
      )}

      {/* City Officer List (도시별 관직 현황) */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">도시별 관직 현황</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-muted/50">
                  <th className="text-left py-1 px-2 w-32">도시</th>
                  <th className="text-left py-1 px-2">
                    {formatOfficerLevelText(4, nationLevel)}
                  </th>
                  <th className="text-left py-1 px-2">
                    {formatOfficerLevelText(3, nationLevel)}
                  </th>
                  <th className="text-left py-1 px-2">
                    {formatOfficerLevelText(2, nationLevel)}
                  </th>
                </tr>
              </thead>
              <tbody>
                {cityOfficers.map(({ city, officers }) => (
                  <tr key={city.id} className="border-b border-muted/20">
                    <td
                      className="py-1 px-2 font-medium"
                      style={{
                        color: nation?.color ?? undefined,
                      }}
                    >
                      {city.name}
                    </td>
                    {officers.map((officer, idx) => (
                      <td key={idx} className="py-1 px-2 text-xs">
                        {officer ? (
                          <span>
                            {officer.name}
                            <span className="text-muted-foreground ml-1">
                              ({officer.belong ?? "-"}년) 【
                              {cityMap.get(officer.cityId)?.name ?? "?"}】
                            </span>
                          </span>
                        ) : (
                          <span className="text-muted-foreground">-</span>
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-xs text-muted-foreground mt-2">
              ※ <span className="text-orange-400">노란색</span>은 변경 불가능,
              흰색은 변경 가능 관직입니다.
            </p>
          </div>
        </CardContent>
      </Card>

      {/* Ambassador / Auditor Permission (외교권자/조언자 임명) — king only */}
      {isKing && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base text-purple-400">
              외교권자 / 조언자 임명
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            {/* 외교권자 */}
            <PermissionSelector
              label="외교권자"
              permType="ambassador"
              maxSlots={2}
              nationGenerals={nationGenerals}
              nation={nation}
              myGeneral={myGeneral}
              actionLoading={actionLoading}
              setActionLoading={setActionLoading}
              setMessage={setMessage}
              currentWorld={currentWorld}
              loadAll={loadAll}
            />
            {/* 조언자 */}
            <PermissionSelector
              label="조언자"
              permType="auditor"
              maxSlots={2}
              nationGenerals={nationGenerals}
              nation={nation}
              myGeneral={myGeneral}
              actionLoading={actionLoading}
              setActionLoading={setActionLoading}
              setMessage={setMessage}
              currentWorld={currentWorld}
              loadAll={loadAll}
            />
          </CardContent>
        </Card>
      )}

      {/* Expel (추방) — level >= 5 */}
      {canManage && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base flex items-center gap-2 text-red-400">
              <UserMinus className="size-4" />
              추방
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm">대상 장수:</span>
              <select
                className="flex-1 min-w-[200px] h-8 rounded border border-input bg-background px-2 text-sm"
                value={kickTarget}
                onChange={(e) => setKickTarget(Number(e.target.value))}
              >
                <option value={0}>선택하세요</option>
                {candidatesAny
                  .filter((g) => g.id !== myGeneral.id)
                  .map((g) => (
                    <option key={g.id} value={g.id}>
                      {g.name} ({g.leadership}/{g.strength}/{g.intel}
                      {g.killTurn != null ? `, ${g.killTurn}턴` : ""})
                    </option>
                  ))}
              </select>
              <Button
                size="sm"
                variant="destructive"
                disabled={actionLoading || kickTarget === 0}
                onClick={() => handleExpel(kickTarget)}
              >
                추방
              </Button>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
