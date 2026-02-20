"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { cityApi, frontApi, historyApi, nationApi } from "@/lib/gameApi";
import type { City, GeneralFrontInfo, Message, Nation } from "@/types";
import { User } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { formatOfficerLevelText, CREW_TYPE_NAMES } from "@/lib/game-utils";

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

  useEffect(() => {
    if (!currentWorld || !myGeneral) return;

    const cityPromise =
      myGeneral.cityId > 0
        ? cityApi.get(myGeneral.cityId).then((res) => res.data)
        : Promise.resolve(null);
    const nationPromise =
      myGeneral.nationId > 0
        ? nationApi.get(myGeneral.nationId).then((res) => res.data)
        : Promise.resolve(null);

    Promise.all([
      frontApi.getInfo(currentWorld.id).then((res) => res.data.general),
      historyApi.getGeneralRecords(myGeneral.id).then((res) => res.data),
      cityPromise,
      nationPromise,
    ])
      .then(([generalFront, history, cityData, nationData]) => {
        setFrontInfo(generalFront);
        setRecords(history);
        setCity(cityData);
        setNation(nationData);
      })
      .catch(() => {
        setError("나의 장수 정보를 불러오지 못했습니다.");
      });
  }, [currentWorld, myGeneral]);

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

  const commandName = getCurrentCommandName(myGeneral.lastTurn);
  const commandTarget = getCurrentCommandTarget(myGeneral.lastTurn, city?.name);
  const commandEta = formatEta(myGeneral.commandEndTime);
  const officerText = formatOfficerLevelText(
    myGeneral.officerLevel,
    nation?.level,
  );
  const equipmentValues = [
    frontInfo?.weapon ?? myGeneral.weaponCode,
    frontInfo?.book ?? myGeneral.bookCode,
    frontInfo?.horse ?? myGeneral.horseCode,
    frontInfo?.item ?? myGeneral.itemCode,
  ];

  return (
    <div className="p-4 space-y-4 max-w-4xl mx-auto">
      <PageHeader icon={User} title="나의 장수" />

      <Card>
        <CardContent className="pt-6 space-y-4">
          <div className="flex gap-4 items-start">
            <GeneralPortrait
              picture={myGeneral.picture}
              name={myGeneral.name}
              size="lg"
            />
            <div className="space-y-2">
              <div className="text-lg font-bold">{myGeneral.name}</div>
              <div className="flex items-center gap-2">
                <NationBadge name={nation?.name} color={nation?.color} />
                <Badge variant="outline">{officerText}</Badge>
              </div>
              <div className="text-sm text-muted-foreground">
                {city?.name ?? "도시 미상"} / {myGeneral.age}세
              </div>
            </div>
          </div>

          <div className="grid grid-cols-2 md:grid-cols-5 gap-2 text-sm">
            <StatBox label="통솔" value={myGeneral.leadership} />
            <StatBox label="무력" value={myGeneral.strength} />
            <StatBox label="지력" value={myGeneral.intel} />
            <StatBox label="정치" value={myGeneral.politics} />
            <StatBox label="매력" value={myGeneral.charm} />
          </div>

          <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-xs">
            <StatBox label="병사" value={myGeneral.crew.toLocaleString()} />
            <StatBox
              label="병종"
              value={CREW_TYPE_NAMES[myGeneral.crewType] ?? myGeneral.crewType}
            />
            <StatBox
              label="훈련/사기"
              value={`${myGeneral.train}/${myGeneral.atmos}`}
            />
            <StatBox label="레벨" value={myGeneral.expLevel} />
          </div>
        </CardContent>
      </Card>

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">장비</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            {EQUIPMENT_KEYS.map((entry, index) => (
              <div
                key={entry.key}
                className="flex items-center justify-between"
              >
                <span className="text-muted-foreground">{entry.label}</span>
                <span>{equipmentValues[index] || "-"}</span>
              </div>
            ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
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

function StatBox({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="rounded border p-2">
      <div className="text-muted-foreground">{label}</div>
      <div className="font-medium">{value}</div>
    </div>
  );
}
