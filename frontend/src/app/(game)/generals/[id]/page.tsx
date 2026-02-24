"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { User, Swords } from "lucide-react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { generalApi, historyApi } from "@/lib/gameApi";
import type { General, Message } from "@/types";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { ErrorState } from "@/components/game/error-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { SammoBar } from "@/components/game/sammo-bar";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  formatInjury,
  calcInjury,
  formatGeneralTypeCall,
  formatOfficerLevelText,
  formatDefenceTrain,
  ageColor,
  CREW_TYPE_NAMES,
  isValidObjKey,
  formatDexLevel,
  formatHonor,
  numberWithCommas,
  getNPCColor,
} from "@/lib/game-utils";

const DEX_NAMES = ["보병", "궁병", "기병", "귀병", "차병"];

export default function GeneralDetailPage() {
  const params = useParams<{ id: string }>();
  const generalId = Number(params.id);
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { nations, cities, loadAll } = useGameStore();
  const [general, setGeneral] = useState<General | null>(null);
  const [records, setRecords] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const fetchData = useCallback(() => {
    if (!currentWorld) return;
    setLoading(true);
    setError(false);
    loadAll(currentWorld.id);
    Promise.all([
      generalApi.get(generalId),
      historyApi.getGeneralRecords(generalId).catch(() => ({ data: [] })),
    ])
      .then(([genRes, recRes]) => {
        setGeneral(genRes.data);
        setRecords(recRes.data);
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false));
  }, [currentWorld, generalId, loadAll]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const nation = useMemo(
    () => (general ? nations.find((n) => n.id === general.nationId) : null),
    [general, nations],
  );

  const city = useMemo(
    () => (general ? cities.find((c) => c.id === general.cityId) : null),
    [general, cities],
  );

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading) return <LoadingState />;
  if (error) return <ErrorState title="장수 정보를 불러오지 못했습니다." onRetry={fetchData} />;
  if (!general)
    return (
      <div className="p-4">
        <EmptyState icon={User} title="장수를 찾을 수 없습니다." />
      </div>
    );

  const injuryInfo = formatInjury(general.injury);
  const typeCall = formatGeneralTypeCall(
    general.leadership,
    general.strength,
    general.intel,
  );
  const officerText = formatOfficerLevelText(
    general.officerLevel,
    nation?.level,
  );
  const dexValues = [general.dex1, general.dex2, general.dex3, general.dex4, general.dex5];
  const honorText = formatHonor(general.experience);
  const npcColor = getNPCColor(general.npcState);
  const defenceTrainText = formatDefenceTrain(general.defenceTrain);

  // Battle stats
  const warnum = general.warnum ?? 0;
  const killnum = general.killnum ?? 0;
  const deathnum = general.deathnum ?? 0;
  const killcrew = general.killcrew ?? 0;
  const deathcrew = general.deathcrew ?? 0;
  const firenum = general.firenum ?? 0;
  const winRate = warnum > 0 ? ((killnum / warnum) * 100).toFixed(1) : "0.0";
  const killRate = deathcrew > 0 ? ((killcrew / Math.max(deathcrew, 1)) * 100).toFixed(1) : "0.0";

  const statRows = [
    {
      label: "통솔",
      base: general.leadership,
      effective: calcInjury(general.leadership, general.injury),
    },
    {
      label: "무력",
      base: general.strength,
      effective: calcInjury(general.strength, general.injury),
    },
    {
      label: "지력",
      base: general.intel,
      effective: calcInjury(general.intel, general.injury),
    },
    { label: "정치", base: general.politics, effective: general.politics },
    { label: "매력", base: general.charm, effective: general.charm },
  ];

  return (
    <div className="p-4 space-y-6 max-w-2xl mx-auto">
      <PageHeader icon={User} title="장수 상세" />

      {/* Basic info */}
      <Card>
        <CardContent>
          <div className="flex items-start gap-4">
            <GeneralPortrait
              picture={general.picture}
              name={general.name}
              size="lg"
            />
            <div className="flex-1 space-y-2">
              <div className="flex items-center gap-2">
                <h2 className="text-lg font-bold" style={{ color: npcColor }}>{general.name}</h2>
                <NationBadge name={nation?.name} color={nation?.color} />
                {general.npcState > 0 && <Badge variant="secondary">NPC</Badge>}
              </div>
              <div className="text-sm text-muted-foreground space-y-0.5">
                <p>
                  {officerText} | {typeCall} |{" "}
                  <span style={{ color: injuryInfo.color }}>
                    {injuryInfo.text}
                  </span>
                  {general.injury > 0 && (
                    <span className="text-red-400 ml-1">({general.injury}%)</span>
                  )}
                </p>
                <p>
                  도시: {city?.name ?? `#${general.cityId}`} | 연령:{" "}
                  <span style={{ color: ageColor(general.age) }}>
                    {general.age}세
                  </span>
                </p>
                <p>
                  명성: {honorText} ({general.experience.toLocaleString()}) |
                  Lv.{general.expLevel}
                </p>
                <p>
                  성격: {general.personalCode} | 수비숙련: {defenceTrainText}
                </p>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 5-stat with bars */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm">능력치</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1.5">
          {statRows.map((s) => (
            <div key={s.label} className="flex items-center gap-2">
              <span className="w-8 text-xs text-right text-muted-foreground">{s.label}</span>
              <span className="w-6 text-xs text-right font-mono"
                style={{ color: s.effective < s.base ? injuryInfo.color : undefined }}
              >
                {s.effective}
              </span>
              <div className="flex-1">
                <SammoBar height={7} percent={s.base} altText={`${s.effective}/${s.base}`} />
              </div>
              {s.effective !== s.base && (
                <span className="text-[10px] text-muted-foreground w-12 text-right">
                  (기본 {s.base})
                </span>
              )}
            </div>
          ))}
        </CardContent>
      </Card>

      {/* Special / Personality */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm">특성</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap gap-1">
            <Badge variant={isValidObjKey(general.specialCode) ? "secondary" : "outline"}>
              특기: {isValidObjKey(general.specialCode) ? general.specialCode : "없음"}
            </Badge>
            <Badge variant={isValidObjKey(general.special2Code) ? "secondary" : "outline"}>
              특기2: {isValidObjKey(general.special2Code) ? general.special2Code : "없음"}
            </Badge>
            <Badge variant="secondary">성격: {general.personalCode}</Badge>
          </div>
        </CardContent>
      </Card>

      {/* Equipment & Military */}
      <Card>
        <CardHeader>
          <CardTitle>장비 &amp; 군사</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 gap-3 text-sm">
            <Row
              label="병종"
              value={<span className="text-cyan-300">{CREW_TYPE_NAMES[general.crewType] ?? `${general.crewType}`}</span>}
            />
            <Row label="병사" value={general.crew.toLocaleString()} />
            <Row label="훈련" value={<span className={general.train >= 80 ? "text-cyan-400" : ""}>{general.train}</span>} />
            <Row label="사기" value={<span className={general.atmos >= 80 ? "text-cyan-400" : ""}>{general.atmos}</span>} />
            <Row
              label="무기"
              value={
                isValidObjKey(general.weaponCode) ? <span className="text-cyan-300">{general.weaponCode}</span> : "-"
              }
            />
            <Row
              label="서적"
              value={isValidObjKey(general.bookCode) ? <span className="text-cyan-300">{general.bookCode}</span> : "-"}
            />
            <Row
              label="명마"
              value={isValidObjKey(general.horseCode) ? <span className="text-cyan-300">{general.horseCode}</span> : "-"}
            />
            <Row
              label="도구"
              value={isValidObjKey(general.itemCode) ? <span className="text-cyan-300">{general.itemCode}</span> : "-"}
            />
            <Row label="자금" value={<span className="text-yellow-400">{numberWithCommas(general.gold)}</span>} />
            <Row label="군량" value={<span className="text-green-400">{numberWithCommas(general.rice)}</span>} />
            <Row label="계급" value={`Lv.${general.dedLevel ?? 0} (${general.dedication})`} />
          </div>
        </CardContent>
      </Card>

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
                <span className="w-8 text-xs text-muted-foreground">{name}</span>
                <span className="w-8 text-xs font-mono text-right" style={{ color: info.color }}>
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
              <span className={Number(winRate) >= 50 ? "text-cyan-400" : "text-orange-400"}>
                {winRate}%
              </span>
            </div>
            <div>
              <span className="text-muted-foreground">적 사살:</span>{" "}
              <span className="text-yellow-400 font-mono">{numberWithCommas(killcrew)}</span>
            </div>
            <div>
              <span className="text-muted-foreground">아군 피해:</span>{" "}
              <span className="text-red-300 font-mono">{numberWithCommas(deathcrew)}</span>
            </div>
            <div>
              <span className="text-muted-foreground">살상률:</span>{" "}
              <span className={Number(killRate) >= 100 ? "text-cyan-400" : "text-orange-400"}>
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

      {/* Records */}
      <Card>
        <CardHeader>
          <CardTitle>최근 기록</CardTitle>
        </CardHeader>
        <CardContent>
          {records.length === 0 ? (
            <p className="text-sm text-muted-foreground">기록이 없습니다.</p>
          ) : (
            <div className="space-y-1 max-h-60 overflow-y-auto">
              {records.slice(0, 30).map((r) => (
                <div
                  key={r.id}
                  className="border-b border-gray-600/30 px-1 py-0.5 text-xs"
                >
                  <span className="text-gray-400">
                    [{r.sentAt?.substring(0, 10)}]
                  </span>{" "}
                  {(r.payload.message as string) ?? ""}
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex justify-between">
      <span className="text-muted-foreground">{label}</span>
      <span>{value}</span>
    </div>
  );
}
