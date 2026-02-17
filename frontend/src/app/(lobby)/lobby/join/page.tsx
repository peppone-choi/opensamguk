"use client";

import { useEffect, useState, useMemo } from "react";
import { useRouter } from "next/navigation";
import { UserPlus, ArrowLeft } from "lucide-react";
import { useWorldStore } from "@/stores/worldStore";
import { useGeneralStore } from "@/stores/generalStore";
import { useGameStore } from "@/stores/gameStore";
import api from "@/lib/api";
import { PageHeader } from "@/components/game/page-header";
import { StatBar } from "@/components/game/stat-bar";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";

const TOTAL_STAT_POINTS = 350;
const STAT_MIN = 10;
const STAT_MAX = 100;

const STAT_KEYS = [
  "leadership",
  "strength",
  "intel",
  "politics",
  "charm",
] as const;
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

const CREW_TYPES: { code: number; label: string }[] = [
  { code: 0, label: "보병" },
  { code: 1, label: "궁병" },
  { code: 2, label: "기병" },
  { code: 3, label: "수군" },
];

export default function LobbyJoinPage() {
  const router = useRouter();
  const { currentWorld } = useWorldStore();
  const { fetchMyGeneral } = useGeneralStore();
  const { cities, nations, loadAll } = useGameStore();

  const [name, setName] = useState("");
  const [cityId, setCityId] = useState<number | "">("");
  const [nationId, setNationId] = useState<number>(0);
  const [crewType, setCrewType] = useState(0);
  const [stats, setStats] = useState<Record<StatKey, number>>({
    leadership: 70,
    strength: 70,
    intel: 70,
    politics: 70,
    charm: 70,
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (currentWorld) loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  const totalUsed = useMemo(
    () => STAT_KEYS.reduce((sum, k) => sum + stats[k], 0),
    [stats],
  );
  const remaining = TOTAL_STAT_POINTS - totalUsed;

  const adjustStat = (key: StatKey, delta: number) => {
    setStats((prev) => {
      const next = Math.max(STAT_MIN, Math.min(STAT_MAX, prev[key] + delta));
      const newTotal = STAT_KEYS.reduce(
        (s, k) => s + (k === key ? next : prev[k]),
        0,
      );
      if (newTotal > TOTAL_STAT_POINTS) return prev;
      return { ...prev, [key]: next };
    });
  };

  const handleStatInput = (key: StatKey, value: number) => {
    const clamped = Math.max(STAT_MIN, Math.min(STAT_MAX, value));
    setStats((prev) => ({ ...prev, [key]: clamped }));
  };

  // Filter cities by selected nation
  const filteredCities = useMemo(() => {
    if (nationId === 0) return cities;
    return cities.filter((c) => c.nationId === nationId || c.nationId === 0);
  }, [cities, nationId]);

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!currentWorld) return;
    if (!name.trim()) {
      setError("이름을 입력해주세요.");
      return;
    }
    if (cityId === "") {
      setError("도시를 선택해주세요.");
      return;
    }
    if (remaining !== 0) {
      setError(
        `능력치 합계가 ${TOTAL_STAT_POINTS}이어야 합니다. (현재: ${totalUsed})`,
      );
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/worlds/${currentWorld.id}/generals`, {
        name: name.trim(),
        cityId,
        nationId: nationId || null,
        crewType,
        ...stats,
      });
      await fetchMyGeneral(currentWorld.id);
      router.push("/");
    } catch (err: unknown) {
      const msg =
        err instanceof Error ? err.message : "장수 생성에 실패했습니다.";
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );

  return (
    <div className="p-4 max-w-lg mx-auto space-y-6">
      <Button
        variant="ghost"
        size="sm"
        onClick={() => router.push("/lobby")}
        className="mb-2"
      >
        <ArrowLeft className="size-4 mr-1" /> 로비로 돌아가기
      </Button>

      <PageHeader icon={UserPlus} title="장수 생성" />

      {error && (
        <div className="text-sm px-3 py-2 rounded bg-destructive/20 text-destructive">
          {error}
        </div>
      )}

      <Card>
        <CardHeader>
          <CardTitle>장수 정보 입력</CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-5">
            {/* Name */}
            <div className="space-y-1">
              <label className="block text-sm text-muted-foreground">
                장수명
              </label>
              <Input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                maxLength={20}
                placeholder="장수 이름 입력"
              />
            </div>

            {/* Nation */}
            <div className="space-y-1">
              <label className="block text-sm text-muted-foreground">
                소속 국가
              </label>
              <select
                value={nationId}
                onChange={(e) => setNationId(Number(e.target.value))}
                className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
              >
                <option value={0}>재야 (무소속)</option>
                {nations.map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.name}
                  </option>
                ))}
              </select>
            </div>

            {/* City */}
            <div className="space-y-1">
              <label className="block text-sm text-muted-foreground">
                시작 도시
              </label>
              <select
                value={cityId}
                onChange={(e) => setCityId(Number(e.target.value))}
                className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
              >
                <option value="">도시 선택</option>
                {filteredCities.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name} (Lv.{c.level})
                  </option>
                ))}
              </select>
            </div>

            {/* Crew Type */}
            <div className="space-y-1">
              <label className="block text-sm text-muted-foreground">
                병종
              </label>
              <select
                value={crewType}
                onChange={(e) => setCrewType(Number(e.target.value))}
                className="w-full px-3 py-2 bg-background border border-input rounded-md text-sm"
              >
                {CREW_TYPES.map((ct) => (
                  <option key={ct.code} value={ct.code}>
                    {ct.label}
                  </option>
                ))}
              </select>
            </div>

            {/* 5-Stat Allocation */}
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <label className="text-sm text-muted-foreground">
                  능력치 배분 (합계 {TOTAL_STAT_POINTS})
                </label>
                <Badge
                  variant={
                    remaining === 0
                      ? "default"
                      : remaining > 0
                        ? "secondary"
                        : "destructive"
                  }
                  className={
                    remaining === 0
                      ? "bg-green-600"
                      : remaining > 0
                        ? "bg-amber-600"
                        : ""
                  }
                >
                  남은: {remaining}
                </Badge>
              </div>

              {STAT_KEYS.map((key) => (
                <div key={key} className="space-y-1">
                  <div className="flex items-center gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      size="icon-xs"
                      onClick={() => adjustStat(key, -5)}
                    >
                      -
                    </Button>
                    <div className="flex-1">
                      <StatBar
                        label={STAT_LABELS[key]}
                        value={stats[key]}
                        max={STAT_MAX}
                        color={STAT_COLORS[key]}
                      />
                    </div>
                    <Button
                      type="button"
                      variant="outline"
                      size="icon-xs"
                      onClick={() => adjustStat(key, 5)}
                    >
                      +
                    </Button>
                    <Input
                      type="number"
                      min={STAT_MIN}
                      max={STAT_MAX}
                      value={stats[key]}
                      onChange={(e) =>
                        handleStatInput(key, Number(e.target.value))
                      }
                      className="w-14 text-center"
                    />
                  </div>
                </div>
              ))}
            </div>

            {/* Submit */}
            <Button
              type="submit"
              disabled={submitting || remaining !== 0}
              className="w-full"
            >
              {submitting ? "생성중..." : "장수 생성"}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
