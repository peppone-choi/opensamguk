"use client";

import { useState } from "react";
import { Swords } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { battleSimApi } from "@/lib/gameApi";

interface SimUnit {
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

interface SimCity {
  def: number;
  wall: number;
  level: number;
}

interface SimResult {
  winner: string;
  attackerRemaining: number;
  defenderRemaining: number;
  rounds: number;
  logs: string[];
}

const defaultUnit = (): SimUnit => ({
  name: "장수",
  leadership: 50,
  strength: 50,
  intel: 50,
  crew: 1000,
  crewType: 0,
  train: 50,
  atmos: 50,
  weaponCode: "None",
  bookCode: "None",
  horseCode: "None",
  specialCode: "None",
});

const defaultCity = (): SimCity => ({ def: 0, wall: 0, level: 5 });

function UnitForm({
  label,
  unit,
  onChange,
}: {
  label: string;
  unit: SimUnit;
  onChange: (u: SimUnit) => void;
}) {
  const set = (key: keyof SimUnit, val: string) => {
    const numKeys: (keyof SimUnit)[] = [
      "leadership",
      "strength",
      "intel",
      "crew",
      "crewType",
      "train",
      "atmos",
    ];
    if (numKeys.includes(key)) {
      onChange({ ...unit, [key]: parseInt(val) || 0 });
    } else {
      onChange({ ...unit, [key]: val });
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm">{label}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        <div className="grid grid-cols-2 gap-2">
          <Field
            label="이름"
            value={unit.name}
            onChange={(v) => set("name", v)}
          />
          <Field
            label="통솔"
            value={unit.leadership}
            type="number"
            onChange={(v) => set("leadership", v)}
          />
          <Field
            label="무력"
            value={unit.strength}
            type="number"
            onChange={(v) => set("strength", v)}
          />
          <Field
            label="지력"
            value={unit.intel}
            type="number"
            onChange={(v) => set("intel", v)}
          />
          <Field
            label="병사"
            value={unit.crew}
            type="number"
            onChange={(v) => set("crew", v)}
          />
          <Field
            label="훈련"
            value={unit.train}
            type="number"
            onChange={(v) => set("train", v)}
          />
          <Field
            label="사기"
            value={unit.atmos}
            type="number"
            onChange={(v) => set("atmos", v)}
          />
          <Field
            label="병종"
            value={unit.crewType}
            type="number"
            onChange={(v) => set("crewType", v)}
          />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Field
            label="무기"
            value={unit.weaponCode}
            onChange={(v) => set("weaponCode", v)}
          />
          <Field
            label="서적"
            value={unit.bookCode}
            onChange={(v) => set("bookCode", v)}
          />
          <Field
            label="말"
            value={unit.horseCode}
            onChange={(v) => set("horseCode", v)}
          />
          <Field
            label="특기"
            value={unit.specialCode}
            onChange={(v) => set("specialCode", v)}
          />
        </div>
      </CardContent>
    </Card>
  );
}

function CityForm({
  city,
  onChange,
}: {
  city: SimCity;
  onChange: (c: SimCity) => void;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm">방어 도시</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="grid grid-cols-3 gap-2">
          <Field
            label="수비"
            value={city.def}
            type="number"
            onChange={(v) => onChange({ ...city, def: parseInt(v) || 0 })}
          />
          <Field
            label="성벽"
            value={city.wall}
            type="number"
            onChange={(v) => onChange({ ...city, wall: parseInt(v) || 0 })}
          />
          <Field
            label="레벨"
            value={city.level}
            type="number"
            onChange={(v) => onChange({ ...city, level: parseInt(v) || 0 })}
          />
        </div>
      </CardContent>
    </Card>
  );
}

function Field({
  label,
  value,
  type = "text",
  onChange,
}: {
  label: string;
  value: string | number;
  type?: string;
  onChange: (v: string) => void;
}) {
  return (
    <div>
      <label className="text-xs text-muted-foreground">{label}</label>
      <Input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="h-8 text-xs"
      />
    </div>
  );
}

export default function BattleSimulatorPage() {
  const [attacker, setAttacker] = useState<SimUnit>(defaultUnit());
  const [defender, setDefender] = useState<SimUnit>(defaultUnit());
  const [defenderCity, setDefenderCity] = useState<SimCity>(defaultCity());
  const [result, setResult] = useState<SimResult | null>(null);
  const [simulating, setSimulating] = useState(false);

  const handleSimulate = async () => {
    setSimulating(true);
    try {
      const { data } = await battleSimApi.simulate(
        attacker as unknown as Record<string, unknown>,
        defender as unknown as Record<string, unknown>,
        defenderCity as unknown as Record<string, unknown>,
      );
      setResult(data);
    } catch {
      /* ignore */
    } finally {
      setSimulating(false);
    }
  };

  const handleReset = () => {
    setAttacker(defaultUnit());
    setDefender(defaultUnit());
    setDefenderCity(defaultCity());
    setResult(null);
  };

  return (
    <div className="max-w-4xl mx-auto space-y-4">
      <PageHeader icon={Swords} title="전투 시뮬레이터" />

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <UnitForm label="공격측" unit={attacker} onChange={setAttacker} />
        <UnitForm label="방어측" unit={defender} onChange={setDefender} />
      </div>

      <CityForm city={defenderCity} onChange={setDefenderCity} />

      <div className="flex gap-2">
        <Button onClick={handleSimulate} disabled={simulating}>
          {simulating ? "시뮬레이션 중..." : "시뮬레이션 실행"}
        </Button>
        <Button variant="outline" onClick={handleReset}>
          초기화
        </Button>
      </div>

      {result && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              결과
              <Badge
                variant={
                  result.winner === "공격측 승리"
                    ? "default"
                    : result.winner === "방어측 승리"
                      ? "secondary"
                      : "outline"
                }
              >
                {result.winner}
              </Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="grid grid-cols-3 gap-4 text-sm">
              <div>
                <span className="text-muted-foreground">라운드</span>
                <p className="font-medium">{result.rounds}</p>
              </div>
              <div>
                <span className="text-muted-foreground">공격 잔여병력</span>
                <p className="font-medium">{result.attackerRemaining}</p>
              </div>
              <div>
                <span className="text-muted-foreground">방어 잔여병력</span>
                <p className="font-medium">{result.defenderRemaining}</p>
              </div>
            </div>
            <div className="border border-border rounded p-3 max-h-64 overflow-y-auto">
              {result.logs.map((log, i) => (
                <p key={i} className="text-xs font-mono leading-relaxed">
                  {log}
                </p>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
