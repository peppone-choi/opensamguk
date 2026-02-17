"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { User } from "lucide-react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { generalApi, historyApi } from "@/lib/gameApi";
import type { General, Message } from "@/types";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { EmptyState } from "@/components/game/empty-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  formatInjury,
  calcInjury,
  formatGeneralTypeCall,
  formatOfficerLevelText,
  ageColor,
  CREW_TYPE_NAMES,
  isValidObjKey,
  formatDexLevel,
  formatHonor,
} from "@/lib/game-utils";

export default function GeneralDetailPage() {
  const params = useParams<{ id: string }>();
  const generalId = Number(params.id);
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { nations, cities, loadAll } = useGameStore();
  const [general, setGeneral] = useState<General | null>(null);
  const [records, setRecords] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!currentWorld) return;
    loadAll(currentWorld.id);
    Promise.all([
      generalApi.get(generalId),
      historyApi.getGeneralRecords(generalId).catch(() => ({ data: [] })),
    ])
      .then(([genRes, recRes]) => {
        setGeneral(genRes.data);
        setRecords(recRes.data);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [currentWorld, generalId, loadAll]);

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
  const dex1 = formatDexLevel((general.meta?.dex1 as number) ?? 0);
  const honorText = formatHonor(general.experience);

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
                <h2 className="text-lg font-bold">{general.name}</h2>
                <NationBadge name={nation?.name} color={nation?.color} />
                {general.npcState > 0 && <Badge variant="secondary">NPC</Badge>}
              </div>
              <div className="text-sm text-muted-foreground space-y-0.5">
                <p>
                  {officerText} | {typeCall} |{" "}
                  <span style={{ color: injuryInfo.color }}>
                    {injuryInfo.text}
                  </span>
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
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 5-stat */}
      <Card>
        <CardHeader>
          <CardTitle>능력치</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-5 gap-3 text-center">
            {statRows.map((s) => (
              <div key={s.label} className="space-y-1">
                <div className="text-xs text-muted-foreground">{s.label}</div>
                <div className="text-lg font-bold">
                  <span
                    style={{
                      color:
                        s.effective < s.base ? injuryInfo.color : undefined,
                    }}
                  >
                    {s.effective}
                  </span>
                </div>
                {s.effective !== s.base && (
                  <div className="text-xs text-muted-foreground">
                    (기본 {s.base})
                  </div>
                )}
              </div>
            ))}
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
              value={CREW_TYPE_NAMES[general.crewType] ?? `${general.crewType}`}
            />
            <Row label="병사" value={general.crew.toLocaleString()} />
            <Row label="훈련" value={String(general.train)} />
            <Row label="사기" value={String(general.atmos)} />
            <Row
              label="무기"
              value={
                isValidObjKey(general.weaponCode) ? general.weaponCode : "-"
              }
            />
            <Row
              label="서적"
              value={isValidObjKey(general.bookCode) ? general.bookCode : "-"}
            />
            <Row
              label="명마"
              value={isValidObjKey(general.horseCode) ? general.horseCode : "-"}
            />
            <Row
              label="도구"
              value={isValidObjKey(general.itemCode) ? general.itemCode : "-"}
            />
            <Row label="자금" value={general.gold.toLocaleString()} />
            <Row label="군량" value={general.rice.toLocaleString()} />
            <Row
              label="숙련"
              value={<span style={{ color: dex1.color }}>{dex1.name}</span>}
            />
            <Row
              label="특기"
              value={
                isValidObjKey(general.specialCode) ? general.specialCode : "-"
              }
            />
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
