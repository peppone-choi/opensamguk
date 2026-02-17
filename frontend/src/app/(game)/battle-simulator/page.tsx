"use client";

import { useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { battleSimApi } from "@/lib/gameApi";
import type { BattleSimUnit, BattleSimCity, BattleSimResult } from "@/types";
import { Swords, Play, RotateCcw } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";

const CREW_TYPES: Record<number, string> = {
  0: "보병",
  1: "궁병",
  2: "기병",
  3: "귀병",
  4: "차병",
  5: "노병",
  6: "연노병",
  7: "근위기병",
  8: "무당병",
  9: "서량기병",
  10: "등갑병",
  11: "수군",
};

interface UnitFormState {
  name: string;
  leadership: number;
  strength: number;
  intel: number;
  crew: number;
  crewType: number;
  train: number;
  atmos: number;
  weaponCode: string;
  bookCode: string;
  horseCode: string;
  specialCode: string;
}

const defaultUnit = (name: string): UnitFormState => ({
  name,
  leadership: 50,
  strength: 50,
  intel: 50,
  crew: 7000,
  crewType: 0,
  train: 100,
  atmos: 100,
  weaponCode: "",
  bookCode: "",
  horseCode: "",
  specialCode: "",
});

interface CityDefenseState {
  def: number;
  wall: number;
  level: number;
}

const defaultCityDefense: CityDefenseState = {
  def: 1000,
  wall: 1000,
  level: 5,
};

function NumberField({
  label,
  value,
  onChange,
  min,
  max,
  step,
}: {
  label: string;
  value: number;
  onChange: (v: number) => void;
  min?: number;
  max?: number;
  step?: number;
}) {
  return (
    <div className="flex items-center gap-1">
      <span className="text-xs text-muted-foreground whitespace-nowrap w-10 text-right">
        {label}
      </span>
      <Input
        type="number"
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        min={min}
        max={max}
        step={step}
        className="w-20 h-7 text-xs tabular-nums"
      />
    </div>
  );
}

function UnitBuilder({
  title,
  unit,
  onChange,
}: {
  title: string;
  unit: UnitFormState;
  onChange: (u: UnitFormState) => void;
}) {
  const set = <K extends keyof UnitFormState>(key: K, val: UnitFormState[K]) =>
    onChange({ ...unit, [key]: val });

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm">{title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        <div className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground w-10 text-right">
            이름
          </span>
          <Input
            value={unit.name}
            onChange={(e) => set("name", e.target.value)}
            className="w-24 h-7 text-xs"
          />
        </div>

        <div className="flex flex-wrap gap-2">
          <NumberField
            label="통솔"
            value={unit.leadership}
            onChange={(v) => set("leadership", v)}
            min={1}
            max={100}
          />
          <NumberField
            label="무력"
            value={unit.strength}
            onChange={(v) => set("strength", v)}
            min={1}
            max={100}
          />
          <NumberField
            label="지력"
            value={unit.intel}
            onChange={(v) => set("intel", v)}
            min={1}
            max={100}
          />
        </div>

        <div className="flex flex-wrap gap-2">
          <div className="flex items-center gap-1">
            <span className="text-xs text-muted-foreground w-10 text-right">
              병종
            </span>
            <select
              value={unit.crewType}
              onChange={(e) => set("crewType", Number(e.target.value))}
              className="h-7 border border-gray-600 bg-[#111] px-1 text-xs text-white"
            >
              {Object.entries(CREW_TYPES).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </div>
          <NumberField
            label="병사"
            value={unit.crew}
            onChange={(v) => set("crew", v)}
            min={100}
            step={100}
          />
        </div>

        <div className="flex flex-wrap gap-2">
          <NumberField
            label="훈련"
            value={unit.train}
            onChange={(v) => set("train", v)}
            min={40}
            max={100}
          />
          <NumberField
            label="사기"
            value={unit.atmos}
            onChange={(v) => set("atmos", v)}
            min={40}
            max={100}
          />
        </div>
      </CardContent>
    </Card>
  );
}

export default function BattleSimulatorPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);

  const [year, setYear] = useState(currentWorld?.currentYear ?? 200);
  const [month, setMonth] = useState(currentWorld?.currentMonth ?? 1);
  const [seed, setSeed] = useState("");
  const [repeatCount, setRepeatCount] = useState<1 | 1000>(1);

  const [attacker, setAttacker] = useState<UnitFormState>(
    defaultUnit("공격측"),
  );
  const [defender, setDefender] = useState<UnitFormState>(
    defaultUnit("방어측"),
  );
  const [cityDef, setCityDef] = useState<CityDefenseState>({
    ...defaultCityDefense,
  });

  const [result, setResult] = useState<BattleSimResult | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSimulate = async () => {
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      const attackerUnit: BattleSimUnit = {
        name: attacker.name,
        leadership: attacker.leadership,
        strength: attacker.strength,
        intel: attacker.intel,
        crew: attacker.crew,
        crewType: attacker.crewType,
        train: attacker.train,
        atmos: attacker.atmos,
        weaponCode: attacker.weaponCode || undefined,
        bookCode: attacker.bookCode || undefined,
        horseCode: attacker.horseCode || undefined,
        specialCode: attacker.specialCode || undefined,
      };
      const defenderUnit: BattleSimUnit = {
        name: defender.name,
        leadership: defender.leadership,
        strength: defender.strength,
        intel: defender.intel,
        crew: defender.crew,
        crewType: defender.crewType,
        train: defender.train,
        atmos: defender.atmos,
        weaponCode: defender.weaponCode || undefined,
        bookCode: defender.bookCode || undefined,
        horseCode: defender.horseCode || undefined,
        specialCode: defender.specialCode || undefined,
      };
      const defCity: BattleSimCity = {
        def: cityDef.def,
        wall: cityDef.wall,
        level: cityDef.level,
      };
      const { data } = await battleSimApi.simulate(
        attackerUnit,
        defenderUnit,
        defCity,
      );
      setResult(data);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "시뮬레이션 오류");
    } finally {
      setRunning(false);
    }
  };

  const handleReset = () => {
    setAttacker(defaultUnit("공격측"));
    setDefender(defaultUnit("방어측"));
    setCityDef({ ...defaultCityDefense });
    setResult(null);
    setError(null);
  };

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );

  return (
    <div className="space-y-4 max-w-4xl mx-auto">
      <PageHeader
        icon={Swords}
        title="전투 시뮬레이터"
        description="장수 간 전투를 시뮬레이션합니다."
      />

      {/* Global Config */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm">전역 설정</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-1">
              <span className="text-xs text-muted-foreground">년</span>
              <Input
                type="number"
                value={year}
                onChange={(e) => setYear(Number(e.target.value))}
                min={1}
                className="w-20 h-7 text-xs tabular-nums"
              />
            </div>
            <div className="flex items-center gap-1">
              <span className="text-xs text-muted-foreground">월</span>
              <Input
                type="number"
                value={month}
                onChange={(e) => setMonth(Number(e.target.value))}
                min={1}
                max={12}
                className="w-16 h-7 text-xs tabular-nums"
              />
            </div>
            <div className="flex items-center gap-1">
              <span className="text-xs text-muted-foreground">시드</span>
              <Input
                value={seed}
                onChange={(e) => setSeed(e.target.value)}
                placeholder="랜덤"
                className="w-32 h-7 text-xs"
              />
            </div>
            <div className="flex items-center gap-1">
              <span className="text-xs text-muted-foreground">반복</span>
              <select
                value={repeatCount}
                onChange={(e) =>
                  setRepeatCount(Number(e.target.value) as 1 | 1000)
                }
                className="h-7 border border-gray-600 bg-[#111] px-1 text-xs text-white"
              >
                <option value={1}>1회 (로그)</option>
                <option value={1000}>1000회 (요약)</option>
              </select>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Attacker / Defender */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <UnitBuilder
          title="공격측 장수"
          unit={attacker}
          onChange={setAttacker}
        />
        <div className="space-y-4">
          <UnitBuilder
            title="방어측 장수"
            unit={defender}
            onChange={setDefender}
          />

          {/* City Defense */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">방어측 도시</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex flex-wrap gap-2">
                <NumberField
                  label="수비"
                  value={cityDef.def}
                  onChange={(v) => setCityDef({ ...cityDef, def: v })}
                  min={0}
                  step={10}
                />
                <NumberField
                  label="성벽"
                  value={cityDef.wall}
                  onChange={(v) => setCityDef({ ...cityDef, wall: v })}
                  min={0}
                  step={10}
                />
                <div className="flex items-center gap-1">
                  <span className="text-xs text-muted-foreground w-10 text-right">
                    규모
                  </span>
                  <select
                    value={cityDef.level}
                    onChange={(e) =>
                      setCityDef({ ...cityDef, level: Number(e.target.value) })
                    }
                    className="h-7 border border-gray-600 bg-[#111] px-1 text-xs text-white"
                  >
                    {[1, 2, 3, 4, 5, 6, 7].map((lv) => (
                      <option key={lv} value={lv}>
                        Lv.{lv}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>

      {/* Action buttons */}
      <div className="flex gap-2">
        <Button onClick={handleSimulate} disabled={running}>
          <Play className="size-4" />
          {running ? "시뮬레이션 중..." : "전투 시작"}
        </Button>
        <Button variant="outline" onClick={handleReset}>
          <RotateCcw className="size-4" />
          초기화
        </Button>
      </div>

      {/* Error */}
      {error && (
        <div className="text-sm text-destructive border border-red-900 bg-red-950/30 p-3 rounded">
          {error}
        </div>
      )}

      {/* Result */}
      {result && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm">
              전투 결과
              <Badge
                variant={
                  result.winner === "attacker" ? "default" : "destructive"
                }
              >
                {result.winner === "attacker"
                  ? "공격측 승리"
                  : result.winner === "defender"
                    ? "방어측 승리"
                    : "무승부"}
              </Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="grid grid-cols-3 gap-4 text-sm">
              <div className="space-y-1">
                <div className="text-xs text-muted-foreground">
                  공격측 잔여 병력
                </div>
                <div className="text-lg font-bold tabular-nums">
                  {result.attackerRemaining.toLocaleString()}
                </div>
              </div>
              <div className="space-y-1">
                <div className="text-xs text-muted-foreground">
                  방어측 잔여 병력
                </div>
                <div className="text-lg font-bold tabular-nums">
                  {result.defenderRemaining.toLocaleString()}
                </div>
              </div>
              <div className="space-y-1">
                <div className="text-xs text-muted-foreground">전투 라운드</div>
                <div className="text-lg font-bold tabular-nums">
                  {result.rounds}회
                </div>
              </div>
            </div>

            {/* Battle Log */}
            {result.logs.length > 0 && (
              <div className="space-y-1">
                <div className="text-xs text-muted-foreground font-medium">
                  전투 로그
                </div>
                <div className="max-h-80 overflow-y-auto border border-gray-800 bg-[#0a0a0a] p-2 rounded text-xs space-y-0.5">
                  {result.logs.map((log, i) => (
                    <div
                      key={i}
                      className="text-gray-300"
                      dangerouslySetInnerHTML={{ __html: log }}
                    />
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
