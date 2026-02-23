"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { cityApi, frontApi, historyApi, nationApi } from "@/lib/gameApi";
import { subscribeWebSocket } from "@/lib/websocket";
import type { City, GeneralFrontInfo, Message, Nation } from "@/types";
import { User, Swords } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { SammoBar } from "@/components/game/sammo-bar";
import {
  formatOfficerLevelText,
  formatInjury,
  formatGeneralTypeCall,
  formatDexLevel,
  formatHonor,
  CREW_TYPE_NAMES,
  numberWithCommas,
  ageColor,
  getNPCColor,
} from "@/lib/game-utils";

const DEX_NAMES = ["보병", "궁병", "기병", "귀병", "차병"];

const EQUIPMENT_KEYS: Array<{ key: keyof GeneralFrontInfo; label: string }> = [
  { key: "weapon", label: "무기" },
  { key: "book", label: "서적" },
  { key: "horse", label: "군마" },
  { key: "item", label: "도구" },
];

export default function GeneralPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const {
    myGeneral,
    loading: myGeneralLoading,
    fetchMyGeneral,
  } = useGeneralStore();
  const [frontInfo, setFrontInfo] = useState<GeneralFrontInfo | null>(null);
  const [nation, setNation] = useState<Nation | null>(null);
  const [city, setCity] = useState<City | null>(null);
  const [records, setRecords] = useState<Message[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!currentWorld) return;
    fetchMyGeneral(currentWorld.id).catch(() => {
      setError("장수 정보를 불러올 수 없습니다.");
    });
  }, [currentWorld, fetchMyGeneral]);

  const loadGeneralData = useCallback(async () => {
    if (!currentWorld || !myGeneral) return;

    const cityPromise =
      myGeneral.cityId > 0
        ? cityApi.get(myGeneral.cityId).then((res) => res.data)
        : Promise.resolve(null);
    const nationPromise =
      myGeneral.nationId > 0
        ? nationApi.get(myGeneral.nationId).then((res) => res.data)
        : Promise.resolve(null);

    try {
      const [generalFront, history, cityData, nationData] = await Promise.all([
        frontApi.getInfo(currentWorld.id).then((res) => res.data.general),
        historyApi.getGeneralRecords(myGeneral.id).then((res) => res.data),
        cityPromise,
        nationPromise,
      ]);
      setFrontInfo(generalFront);
      setRecords(history);
      setCity(cityData);
      setNation(nationData);
    } catch {
      setError("나의 장수 정보를 불러오지 못했습니다.");
    }
  }, [currentWorld, myGeneral]);

  useEffect(() => {
    loadGeneralData();
  }, [loadGeneralData]);

  useEffect(() => {
    if (!currentWorld || !myGeneral) return;
    return subscribeWebSocket(`/topic/world/${currentWorld.id}/turn`, () => {
      fetchMyGeneral(currentWorld.id).catch(() => {});
      loadGeneralData();
    });
  }, [currentWorld, myGeneral, fetchMyGeneral, loadGeneralData]);

  const biographyRows = useMemo(
    () =>
      records
        .map((record) => {
          const content = record.payload.content;
          const text =
            typeof content === "string"
              ? content
              : JSON.stringify(record.payload);
          return {
            id: record.id,
            sentAt: record.sentAt,
            text,
          };
        })
        .slice(0, 20),
    [records],
  );

  if (!currentWorld) return <LoadingState message="월드를 선택해주세요." />;
  if (myGeneralLoading || (myGeneral && !frontInfo && !error)) {
    return <LoadingState />;
  }
  if (error) return <div className="p-4 text-red-400">{error}</div>;
  if (!myGeneral) return <LoadingState message="장수 정보가 없습니다." />;

  const g = myGeneral;
  const fi = frontInfo;
  const nationLevel = nation?.level ?? 0;
  const commandName = getCurrentCommandName(g.lastTurn);
  const commandTarget = getCurrentCommandTarget(g.lastTurn, city?.name);
  const commandEta = formatEta(g.commandEndTime);
  const officerText = formatOfficerLevelText(g.officerLevel, nationLevel);
  const injuryInfo = formatInjury(g.injury);
  const typeCall = formatGeneralTypeCall(g.leadership, g.strength, g.intel);
  const honorText = formatHonor(g.experience);
  const npcColor = getNPCColor(g.npcState);
  const equipmentValues = [
    fi?.weapon ?? g.weaponCode,
    fi?.book ?? g.bookCode,
    fi?.horse ?? g.horseCode,
    fi?.item ?? g.itemCode,
  ];

  const dexValues = [
    fi?.dex1 ?? g.dex1 ?? 0,
    fi?.dex2 ?? g.dex2 ?? 0,
    fi?.dex3 ?? g.dex3 ?? 0,
    fi?.dex4 ?? g.dex4 ?? 0,
    fi?.dex5 ?? g.dex5 ?? 0,
  ];

  // Battle stats
  const warnum = fi?.warnum ?? g.warnum ?? 0;
  const killnum = fi?.killnum ?? g.killnum ?? 0;
  const deathnum = fi?.deathnum ?? g.deathnum ?? 0;
  const killcrew = fi?.killcrew ?? g.killcrew ?? 0;
  const deathcrew = fi?.deathcrew ?? g.deathcrew ?? 0;
  const firenum = fi?.firenum ?? g.firenum ?? 0;
  const winRate = warnum > 0 ? ((killnum / warnum) * 100).toFixed(1) : "0.0";
  const killRate =
    deathcrew > 0
      ? ((killcrew / Math.max(deathcrew, 1)) * 100).toFixed(1)
      : "0.0";

  return (
    <div className="p-4 space-y-4 max-w-4xl mx-auto">
      <PageHeader icon={User} title="나의 장수" />

      {/* Profile + Basic Info */}
      <Card>
        <CardContent className="pt-6 space-y-4">
          <div className="flex gap-4 items-start">
            <GeneralPortrait picture={g.picture} name={g.name} size="lg" />
            <div className="space-y-1 flex-1">
              <p className="text-lg font-bold" style={{ color: npcColor }}>
                {g.name}
              </p>
              <div className="flex items-center gap-2 flex-wrap">
                <NationBadge name={nation?.name} color={nation?.color} />
                <Badge variant="outline">{officerText}</Badge>
              </div>
              <p className="text-sm">
                <span className="text-muted-foreground">유형:</span>{" "}
                <span className="text-yellow-400">{typeCall}</span>
              </p>
              <p className="text-sm">
                <span className="text-muted-foreground">위치:</span>{" "}
                {city?.name ?? "도시 미상"}
              </p>
              <p className="text-sm">
                <span className="text-muted-foreground">나이:</span>{" "}
                <span
                  style={{
                    color: ageColor(g.age, g.deadYear - g.bornYear),
                  }}
                >
                  {g.age}세
                </span>
              </p>
              <p className="text-sm">
                <span className="text-muted-foreground">상태:</span>{" "}
                <span style={{ color: injuryInfo.color }}>
                  {injuryInfo.text}
                </span>
                {g.injury > 0 && (
                  <span className="text-red-400 ml-1">({g.injury}%)</span>
                )}
              </p>
              <p className="text-sm">
                <span className="text-muted-foreground">명성:</span>{" "}
                <span className="text-yellow-400">{honorText}</span>
                <span className="text-muted-foreground ml-2">
                  ({g.experience.toLocaleString()})
                </span>
              </p>
            </div>
          </div>
        </CardContent>
      </Card>

      <div className="grid gap-4 md:grid-cols-2">
        {/* 5-Stat with bars */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">능력치</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1.5">
            {[
              {
                label: "통솔",
                value: g.leadership,
                exp: g.leadershipExp,
                color: "red",
              },
              {
                label: "무력",
                value: g.strength,
                exp: g.strengthExp,
                color: "orange",
              },
              {
                label: "지력",
                value: g.intel,
                exp: g.intelExp,
                color: "dodgerblue",
              },
              {
                label: "정치",
                value: g.politics,
                exp: 0,
                color: "limegreen",
              },
              {
                label: "매력",
                value: g.charm,
                exp: 0,
                color: "mediumpurple",
              },
            ].map((s) => (
              <div key={s.label} className="flex items-center gap-2">
                <span
                  className="w-8 text-xs text-right"
                  style={{ color: s.color }}
                >
                  {s.label}
                </span>
                <span className="w-6 text-xs text-right font-mono">
                  {s.value}
                </span>
                <div className="flex-1">
                  <SammoBar
                    height={7}
                    percent={s.value}
                    altText={`${s.value}/100`}
                  />
                </div>
                {s.exp > 0 && (
                  <span className="text-[10px] text-yellow-500 w-10 text-right">
                    +{s.exp}
                  </span>
                )}
              </div>
            ))}
          </CardContent>
        </Card>

        {/* Special / Personality / Equipment */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">특성 / 장비</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex flex-wrap gap-1">
              <Badge
                variant={
                  g.specialCode === "None" ? "outline" : "secondary"
                }
              >
                특기: {g.specialCode === "None" ? "없음" : g.specialCode}
              </Badge>
              <Badge
                variant={
                  g.special2Code === "None" ? "outline" : "secondary"
                }
              >
                특기2:{" "}
                {g.special2Code === "None" ? "없음" : g.special2Code}
              </Badge>
              <Badge variant="secondary">성격: {g.personalCode}</Badge>
            </div>
            <div className="grid grid-cols-2 gap-2 text-sm">
              {EQUIPMENT_KEYS.map((entry, index) => {
                const val = equipmentValues[index];
                const hasItem = val && val !== "None" && val !== "";
                return (
                  <div
                    key={entry.key}
                    className="flex items-center justify-between"
                  >
                    <span className="text-muted-foreground">{entry.label}</span>
                    <span
                      className={
                        hasItem ? "text-cyan-300" : "text-gray-500"
                      }
                    >
                      {hasItem ? val : "-"}
                    </span>
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>

        {/* Military */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">군사 / 자원</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
              <div>
                <span className="text-muted-foreground">병사:</span>{" "}
                <span>{g.crew.toLocaleString()}</span>
              </div>
              <div>
                <span className="text-muted-foreground">병종:</span>{" "}
                <span className="text-cyan-300">
                  {CREW_TYPE_NAMES[g.crewType] ?? g.crewType}
                </span>
              </div>
              <div>
                <span className="text-muted-foreground">훈련:</span>{" "}
                <span
                  className={g.train >= 80 ? "text-cyan-400" : ""}
                >
                  {g.train}
                </span>
              </div>
              <div>
                <span className="text-muted-foreground">사기:</span>{" "}
                <span
                  className={g.atmos >= 80 ? "text-cyan-400" : ""}
                >
                  {g.atmos}
                </span>
              </div>
              <div>
                <span className="text-muted-foreground">자금:</span>{" "}
                <span className="text-yellow-400">
                  {numberWithCommas(g.gold)}
                </span>
              </div>
              <div>
                <span className="text-muted-foreground">군량:</span>{" "}
                <span className="text-green-400">
                  {numberWithCommas(g.rice)}
                </span>
              </div>
              <div>
                <span className="text-muted-foreground">레벨:</span>{" "}
                <span>{g.expLevel}</span>
              </div>
              <div>
                <span className="text-muted-foreground">계급:</span>{" "}
                <span>Lv.{g.dedLevel ?? 0} ({g.dedication})</span>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Current Command */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">현재 명령</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">명령</span>
              <span>{commandName}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">목표</span>
              <span>{commandTarget}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">완료 예정</span>
              <span>{commandEta}</span>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Proficiency (숙련도) */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm">숙련도</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1.5">
          {DEX_NAMES.map((name, i) => {
            const dex = dexValues[i];
            const info = formatDexLevel(dex);
            return (
              <div key={name} className="flex items-center gap-2">
                <span className="w-8 text-xs text-muted-foreground">
                  {name}
                </span>
                <span
                  className="w-8 text-xs font-mono text-right"
                  style={{ color: info.color }}
                >
                  {info.name}
                </span>
                <div className="flex-1">
                  <SammoBar
                    height={7}
                    percent={Math.min((info.level / 26) * 100, 100)}
                    altText={`${info.name} (${numberWithCommas(dex)})`}
                  />
                </div>
                <span className="text-[10px] text-muted-foreground w-14 text-right">
                  {numberWithCommas(dex)}
                </span>
              </div>
            );
          })}
        </CardContent>
      </Card>

      {/* Battle Stats */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm flex items-center gap-2">
            <Swords className="size-4" />
            전투 통계
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-x-6 gap-y-2 text-sm">
            <div>
              <span className="text-muted-foreground">전투 횟수:</span>{" "}
              <span className="font-mono">{warnum}</span>
            </div>
            <div>
              <span className="text-muted-foreground">승리:</span>{" "}
              <span className="text-cyan-400 font-mono">{killnum}</span>
            </div>
            <div>
              <span className="text-muted-foreground">패배:</span>{" "}
              <span className="text-red-400 font-mono">{deathnum}</span>
            </div>
            <div>
              <span className="text-muted-foreground">승률:</span>{" "}
              <span
                className={
                  Number(winRate) >= 50 ? "text-cyan-400" : "text-orange-400"
                }
              >
                {winRate}%
              </span>
            </div>
            <div>
              <span className="text-muted-foreground">적 사살:</span>{" "}
              <span className="text-yellow-400 font-mono">
                {numberWithCommas(killcrew)}
              </span>
            </div>
            <div>
              <span className="text-muted-foreground">아군 피해:</span>{" "}
              <span className="text-red-300 font-mono">
                {numberWithCommas(deathcrew)}
              </span>
            </div>
            <div>
              <span className="text-muted-foreground">살상률:</span>{" "}
              <span
                className={
                  Number(killRate) >= 100
                    ? "text-cyan-400"
                    : "text-orange-400"
                }
              >
                {killRate}%
              </span>
            </div>
            <div>
              <span className="text-muted-foreground">계략 성공:</span>{" "}
              <span className="text-green-400 font-mono">{firenum}</span>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Biography */}
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">장수 열전</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {biographyRows.length === 0 ? (
            <div className="text-sm text-muted-foreground">
              기록이 없습니다.
            </div>
          ) : (
            biographyRows.map((row) => (
              <div key={row.id} className="rounded border p-2 text-sm">
                <div className="text-xs text-muted-foreground mb-1">
                  {new Date(row.sentAt).toLocaleString("ko-KR")}
                </div>
                <div className="break-all">{row.text}</div>
              </div>
            ))
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function getRecord(
  value: Record<string, unknown>,
  key: string,
): Record<string, unknown> {
  const raw = value[key];
  if (raw && typeof raw === "object" && !Array.isArray(raw)) {
    return raw as Record<string, unknown>;
  }
  return {};
}

function getCurrentCommandName(lastTurn: Record<string, unknown>): string {
  const actionCode = lastTurn.actionCode;
  if (typeof actionCode === "string" && actionCode.length > 0) {
    return actionCode;
  }
  const brief = lastTurn.brief;
  if (typeof brief === "string" && brief.length > 0) {
    return brief;
  }
  return "대기";
}

function getCurrentCommandTarget(
  lastTurn: Record<string, unknown>,
  currentCityName: string | undefined,
): string {
  const arg = getRecord(lastTurn, "arg");
  const targetCity = arg.destCityId;
  if (typeof targetCity === "number") return `도시 #${targetCity}`;
  if (typeof targetCity === "string" && targetCity.length > 0) {
    return `도시 #${targetCity}`;
  }
  return currentCityName ?? "-";
}

function formatEta(commandEndTime: string | null): string {
  if (!commandEndTime) return "즉시";
  const eta = new Date(commandEndTime);
  if (Number.isNaN(eta.getTime())) return "-";
  return eta.toLocaleString("ko-KR");
}
