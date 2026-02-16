"use client";

import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { useGameStore } from "@/stores/gameStore";
import { useGeneralStore } from "@/stores/generalStore";
import type { City, General, Nation } from "@/types";

/** Arg schema for each command that requires user input */
type ArgField =
  | { type: "city"; key: string; label: string }
  | { type: "nation"; key: string; label: string }
  | { type: "general"; key: string; label: string }
  | { type: "number"; key: string; label: string; placeholder?: string }
  | { type: "boolean"; key: string; label: string }
  | { type: "text"; key: string; label: string; placeholder?: string }
  | {
      type: "select";
      key: string;
      label: string;
      options: { value: string; label: string }[];
    };

const CREW_TYPE_OPTIONS = [
  { value: "0", label: "보병" },
  { value: "1", label: "궁병" },
  { value: "2", label: "기병" },
  { value: "3", label: "수군" },
];

const ITEM_TYPE_OPTIONS = [
  { value: "weapon", label: "무기" },
  { value: "book", label: "서적" },
  { value: "horse", label: "군마" },
  { value: "item", label: "도구" },
];

const COMMAND_ARGS: Record<string, ArgField[]> = {
  // Military - recruitment
  모병: [
    {
      type: "select",
      key: "crewType",
      label: "병종",
      options: CREW_TYPE_OPTIONS,
    },
    { type: "number", key: "amount", label: "수량", placeholder: "최대" },
  ],
  징병: [
    {
      type: "select",
      key: "crewType",
      label: "병종",
      options: CREW_TYPE_OPTIONS,
    },
    { type: "number", key: "amount", label: "수량", placeholder: "최대" },
  ],
  // Movement
  출병: [{ type: "city", key: "destCityId", label: "목표 도시" }],
  이동: [{ type: "city", key: "destCityId", label: "목표 도시" }],
  // Espionage
  첩보: [{ type: "city", key: "destCityId", label: "목표 도시" }],
  // Personnel
  등용: [{ type: "general", key: "destGeneralID", label: "대상 장수" }],
  장수대상임관: [{ type: "general", key: "destGeneralID", label: "대상 장수" }],
  // Economy
  증여: [
    { type: "general", key: "destGeneralID", label: "대상 장수" },
    { type: "boolean", key: "isGold", label: "금화 여부 (off=쌀)" },
    { type: "number", key: "amount", label: "수량" },
  ],
  // Equipment
  장비매매: [
    {
      type: "select",
      key: "itemType",
      label: "장비 종류",
      options: ITEM_TYPE_OPTIONS,
    },
    {
      type: "text",
      key: "itemCode",
      label: "아이템 코드",
      placeholder: "예: S_sword",
    },
  ],
  // Nation commands
  포상: [
    { type: "general", key: "destGeneralID", label: "대상 장수" },
    { type: "boolean", key: "isGold", label: "금화 여부 (off=쌀)" },
    { type: "number", key: "amount", label: "수량" },
  ],
  몰수: [
    { type: "general", key: "destGeneralID", label: "대상 장수" },
    { type: "boolean", key: "isGold", label: "금화 여부 (off=쌀)" },
    { type: "number", key: "amount", label: "수량" },
  ],
  감축: [
    { type: "city", key: "cityId", label: "대상 도시" },
    { type: "number", key: "amount", label: "수량" },
  ],
  증축: [
    { type: "city", key: "cityId", label: "대상 도시" },
    { type: "number", key: "amount", label: "수량" },
  ],
  발령: [
    { type: "general", key: "destGeneralID", label: "대상 장수" },
    { type: "city", key: "cityId", label: "이동 도시" },
  ],
  천도: [{ type: "city", key: "destCityId", label: "새 수도" }],
  백성동원: [
    { type: "city", key: "cityId", label: "대상 도시" },
    { type: "number", key: "amount", label: "동원 수" },
  ],
  물자원조: [
    { type: "nation", key: "destNationId", label: "대상 국가" },
    { type: "boolean", key: "isGold", label: "금화 여부 (off=쌀)" },
    { type: "number", key: "amount", label: "수량" },
  ],
  국호변경: [
    { type: "text", key: "name", label: "새 국호", placeholder: "국가명" },
  ],
  // Diplomacy
  선전포고: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  종전제의: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  종전수락: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  불가침제의: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  불가침수락: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  불가침파기제의: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  불가침파기수락: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  허보: [{ type: "nation", key: "destNationId", label: "대상 국가" }],
  // Strategic
  급습: [{ type: "city", key: "cityId", label: "대상 도시" }],
  수몰: [{ type: "city", key: "cityId", label: "대상 도시" }],
  초토화: [{ type: "city", key: "cityId", label: "대상 도시" }],
  의병모집: [
    { type: "city", key: "cityId", label: "대상 도시" },
    { type: "number", key: "amount", label: "모집 수" },
  ],
  부대탈퇴지시: [{ type: "number", key: "troopId", label: "부대 ID" }],
  인구이동: [
    { type: "city", key: "srcCityId", label: "출발 도시" },
    { type: "city", key: "destCityId", label: "도착 도시" },
    { type: "number", key: "amount", label: "이동 인구" },
  ],
};

interface CommandArgFormProps {
  actionCode: string;
  onSubmit: (arg: Record<string, unknown>) => void;
}

export function CommandArgForm({ actionCode, onSubmit }: CommandArgFormProps) {
  const { cities, nations, generals } = useGameStore();
  const { myGeneral } = useGeneralStore();
  const [valuesByCommand, setValuesByCommand] = useState<
    Record<string, Record<string, string>>
  >({});

  const fields = COMMAND_ARGS[actionCode];
  const values = valuesByCommand[actionCode] ?? {};

  if (!fields) {
    // No args needed - auto-submit
    return null;
  }

  const setValue = (key: string, val: string) => {
    setValuesByCommand((prev) => ({
      ...prev,
      [actionCode]: {
        ...(prev[actionCode] ?? {}),
        [key]: val,
      },
    }));
  };

  const handleSubmit = () => {
    const arg: Record<string, unknown> = {};
    for (const field of fields) {
      const raw = values[field.key];
      if (!raw && raw !== "0") continue;
      if (
        field.type === "number" ||
        field.type === "city" ||
        field.type === "nation" ||
        field.type === "general"
      ) {
        arg[field.key] = Number(raw);
      } else if (field.type === "boolean") {
        arg[field.key] = raw === "true";
      } else if (field.type === "select" && !isNaN(Number(raw))) {
        arg[field.key] = Number(raw);
      } else {
        arg[field.key] = raw;
      }
    }
    onSubmit(arg);
  };

  // Filter cities to own nation for some commands
  const myCities = myGeneral
    ? cities.filter((c) => c.nationId === myGeneral.nationId)
    : cities;

  const renderField = (field: ArgField) => {
    switch (field.type) {
      case "city": {
        const list: City[] = field.key === "destCityId" ? cities : myCities;
        return (
          <select
            key={field.key}
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            className="w-full bg-background border border-input rounded-md px-2 py-1.5 text-xs"
          >
            <option value="">{field.label}...</option>
            {list.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} (Lv.{c.level})
              </option>
            ))}
          </select>
        );
      }
      case "nation": {
        const list: Nation[] = nations.filter(
          (n) => !myGeneral || n.id !== myGeneral.nationId,
        );
        return (
          <select
            key={field.key}
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            className="w-full bg-background border border-input rounded-md px-2 py-1.5 text-xs"
          >
            <option value="">{field.label}...</option>
            {list.map((n) => (
              <option key={n.id} value={n.id}>
                {n.name}
              </option>
            ))}
          </select>
        );
      }
      case "general": {
        const list: General[] = generals.filter(
          (g) => !myGeneral || g.id !== myGeneral.id,
        );
        return (
          <select
            key={field.key}
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            className="w-full bg-background border border-input rounded-md px-2 py-1.5 text-xs"
          >
            <option value="">{field.label}...</option>
            {list.map((g) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
          </select>
        );
      }
      case "select":
        return (
          <select
            key={field.key}
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            className="w-full bg-background border border-input rounded-md px-2 py-1.5 text-xs"
          >
            <option value="">{field.label}...</option>
            {field.options.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        );
      case "boolean":
        return (
          <label
            key={field.key}
            className="flex items-center gap-2 text-xs text-muted-foreground"
          >
            <input
              type="checkbox"
              checked={values[field.key] === "true"}
              onChange={(e) =>
                setValue(field.key, e.target.checked ? "true" : "false")
              }
              className="accent-amber-400"
            />
            {field.label}
          </label>
        );
      case "number":
        return (
          <Input
            key={field.key}
            type="number"
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            placeholder={field.placeholder ?? field.label}
            className="text-xs h-8"
          />
        );
      case "text":
        return (
          <Input
            key={field.key}
            type="text"
            value={values[field.key] ?? ""}
            onChange={(e) => setValue(field.key, e.target.value)}
            placeholder={field.placeholder ?? field.label}
            className="text-xs h-8"
          />
        );
    }
  };

  return (
    <div className="space-y-2">
      <p className="text-xs text-muted-foreground">명령 인자</p>
      {fields.map(renderField)}
      <Button size="sm" onClick={handleSubmit} className="w-full">
        확인
      </Button>
    </div>
  );
}

export { COMMAND_ARGS };
