"use client";

import { useEffect, useMemo, useState } from "react";
import { useWorldStore } from "@/stores/worldStore";
import { useGameStore } from "@/stores/gameStore";
import { rankingApi } from "@/lib/gameApi";
import type { General } from "@/types";
import { Medal, Trophy } from "lucide-react";
import { PageHeader } from "@/components/game/page-header";
import { LoadingState } from "@/components/game/loading-state";
import { GeneralPortrait } from "@/components/game/general-portrait";
import { NationBadge } from "@/components/game/nation-badge";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatDexLevel } from "@/lib/game-utils";

/* ── Category definitions ── */

type CategoryGroup = "stats" | "combat" | "dex" | "tournament" | "other";

interface Category {
  key: string;
  label: string;
  sortBy: string;
  valueType?: "percent";
  getValue: (g: General) => number;
}

const m = (g: General, key: string): number => (g.meta?.[key] as number) ?? 0;

const GROUPS: {
  key: CategoryGroup;
  label: string;
  categories: Category[];
}[] = [
  {
    key: "stats",
    label: "능력치",
    categories: [
      {
        key: "total",
        label: "총합",
        sortBy: "experience",
        getValue: (g) =>
          g.leadership + g.strength + g.intel + g.politics + g.charm,
      },
      {
        key: "leadership",
        label: "통솔",
        sortBy: "leadership",
        getValue: (g) => g.leadership,
      },
      {
        key: "strength",
        label: "무력",
        sortBy: "strength",
        getValue: (g) => g.strength,
      },
      {
        key: "intel",
        label: "지력",
        sortBy: "intel",
        getValue: (g) => g.intel,
      },
      {
        key: "politics",
        label: "정치",
        sortBy: "politics",
        getValue: (g) => g.politics,
      },
      {
        key: "charm",
        label: "매력",
        sortBy: "charm",
        getValue: (g) => g.charm,
      },
    ],
  },
  {
    key: "combat",
    label: "전투",
    categories: [
      {
        key: "experience",
        label: "명성",
        sortBy: "experience",
        getValue: (g) => g.experience,
      },
      {
        key: "dedication",
        label: "계급",
        sortBy: "dedication",
        getValue: (g) => g.dedication,
      },
      {
        key: "warnum",
        label: "전투수",
        sortBy: "warnum",
        getValue: (g) => m(g, "warnum"),
      },
      {
        key: "killnum",
        label: "승수",
        sortBy: "killnum",
        getValue: (g) => m(g, "killnum"),
      },
      {
        key: "winrate",
        label: "승률",
        sortBy: "winrate",
        valueType: "percent",
        getValue: (g) => {
          const w = m(g, "warnum");
          const k = m(g, "killnum");
          return w >= 10 ? k / Math.max(1, w) : 0;
        },
      },
      {
        key: "occupied",
        label: "점령",
        sortBy: "occupied",
        getValue: (g) => m(g, "occupied"),
      },
      {
        key: "killcrew",
        label: "사살",
        sortBy: "killcrew",
        getValue: (g) => m(g, "killcrew"),
      },
      {
        key: "killrate",
        label: "살상률",
        sortBy: "killrate",
        valueType: "percent",
        getValue: (g) => {
          const kc = m(g, "killcrew");
          const dc = m(g, "deathcrew");
          return m(g, "warnum") >= 10 ? kc / Math.max(1, dc) : 0;
        },
      },
      {
        key: "firenum",
        label: "계략성공",
        sortBy: "firenum",
        getValue: (g) => m(g, "firenum"),
      },
    ],
  },
  {
    key: "dex",
    label: "숙련도",
    categories: [
      {
        key: "dex1",
        label: "보병",
        sortBy: "dex1",
        getValue: (g) => m(g, "dex1"),
      },
      {
        key: "dex2",
        label: "궁병",
        sortBy: "dex2",
        getValue: (g) => m(g, "dex2"),
      },
      {
        key: "dex3",
        label: "기병",
        sortBy: "dex3",
        getValue: (g) => m(g, "dex3"),
      },
      {
        key: "dex4",
        label: "귀병",
        sortBy: "dex4",
        getValue: (g) => m(g, "dex4"),
      },
      {
        key: "dex5",
        label: "차병",
        sortBy: "dex5",
        getValue: (g) => m(g, "dex5"),
      },
    ],
  },
  {
    key: "tournament",
    label: "토너먼트",
    categories: [
      {
        key: "ttw",
        label: "전력전",
        sortBy: "ttw",
        valueType: "percent",
        getValue: (g) => {
          const w = m(g, "ttw"),
            d = m(g, "ttd"),
            l = m(g, "ttl");
          const t = w + d + l;
          return t >= 50 ? w / Math.max(1, t) : 0;
        },
      },
      {
        key: "tlw",
        label: "통솔전",
        sortBy: "tlw",
        valueType: "percent",
        getValue: (g) => {
          const w = m(g, "tlw"),
            d = m(g, "tld"),
            l = m(g, "tll");
          const t = w + d + l;
          return t >= 50 ? w / Math.max(1, t) : 0;
        },
      },
      {
        key: "tsw",
        label: "일기토",
        sortBy: "tsw",
        valueType: "percent",
        getValue: (g) => {
          const w = m(g, "tsw"),
            d = m(g, "tsd"),
            l = m(g, "tsl");
          const t = w + d + l;
          return t >= 50 ? w / Math.max(1, t) : 0;
        },
      },
      {
        key: "tiw",
        label: "설전",
        sortBy: "tiw",
        valueType: "percent",
        getValue: (g) => {
          const w = m(g, "tiw"),
            d = m(g, "tid"),
            l = m(g, "til");
          const t = w + d + l;
          return t >= 50 ? w / Math.max(1, t) : 0;
        },
      },
    ],
  },
  {
    key: "other",
    label: "기타",
    categories: [
      {
        key: "betgold",
        label: "베팅투자",
        sortBy: "betgold",
        getValue: (g) => m(g, "betgold"),
      },
      {
        key: "betwingold",
        label: "베팅수익",
        sortBy: "betwingold",
        getValue: (g) => m(g, "betwingold"),
      },
      {
        key: "betrate",
        label: "베팅수익률",
        sortBy: "betrate",
        valueType: "percent",
        getValue: (g) => {
          const gold = m(g, "betgold");
          const win = m(g, "betwingold");
          return gold >= 1000 ? win / Math.max(1, gold) : 0;
        },
      },
    ],
  },
];

const DEFAULT_CATEGORY_BY_GROUP: Record<CategoryGroup, string> = {
  stats: GROUPS.find((g) => g.key === "stats")?.categories[0]?.key ?? "total",
  combat:
    GROUPS.find((g) => g.key === "combat")?.categories[0]?.key ?? "experience",
  dex: GROUPS.find((g) => g.key === "dex")?.categories[0]?.key ?? "dex1",
  tournament:
    GROUPS.find((g) => g.key === "tournament")?.categories[0]?.key ?? "ttw",
  other: GROUPS.find((g) => g.key === "other")?.categories[0]?.key ?? "betgold",
};

/* ── Helpers ── */

function formatValue(value: number, type?: "percent"): string {
  if (type === "percent") return (value * 100).toFixed(2) + "%";
  return value.toLocaleString();
}

function rankMedal(idx: number) {
  if (idx === 0)
    return <Trophy className="size-5 text-amber-400 inline-block" />;
  if (idx === 1)
    return <Trophy className="size-5 text-gray-300 inline-block" />;
  if (idx === 2)
    return <Trophy className="size-5 text-amber-700 inline-block" />;
  return <Badge variant="outline">#{idx + 1}</Badge>;
}

function dexLabel(key: string, value: number): React.ReactNode {
  if (!key.startsWith("dex")) return null;
  const info = formatDexLevel(value);
  return (
    <span className="ml-1 text-xs" style={{ color: info.color }}>
      ({info.name})
    </span>
  );
}

/* ── Component ── */

export default function BestGeneralsPage() {
  const currentWorld = useWorldStore((s) => s.currentWorld);
  const { nations, loadAll } = useGameStore();
  const [groupKey, setGroupKey] = useState<CategoryGroup>("stats");
  const [categoryByGroup, setCategoryByGroup] = useState<
    Record<CategoryGroup, string>
  >(DEFAULT_CATEGORY_BY_GROUP);
  const [npcMode, setNpcMode] = useState<"user" | "npc">("user");
  const [allGenerals, setAllGenerals] = useState<General[]>([]);
  const [loading, setLoading] = useState(true);

  const group = GROUPS.find((g) => g.key === groupKey)!;
  const categoryKey = categoryByGroup[groupKey] ?? group.categories[0].key;
  const category =
    group.categories.find((c) => c.key === categoryKey) ?? group.categories[0];

  useEffect(() => {
    if (!currentWorld) return;
    loadAll(currentWorld.id);
  }, [currentWorld, loadAll]);

  // Fetch data when category or world changes
  useEffect(() => {
    if (!currentWorld) return;
    rankingApi
      .bestGenerals(currentWorld.id, category.sortBy, 300)
      .then(({ data }) => setAllGenerals(data))
      .catch(() => setAllGenerals([]))
      .finally(() => setLoading(false));
  }, [currentWorld, category.sortBy]);

  const nationMap = useMemo(
    () => new Map(nations.map((n) => [n.id, n])),
    [nations],
  );

  const ranked = allGenerals
    .filter((g) => (npcMode === "npc" ? g.npcState >= 2 : g.npcState < 2))
    .map((g) => ({ ...g, __value: category.getValue(g) }))
    .sort((a, b) => b.__value - a.__value)
    .filter((g) => g.__value > 0)
    .slice(0, 50);

  if (!currentWorld)
    return (
      <div className="p-4 text-muted-foreground">월드를 선택해주세요.</div>
    );
  if (loading)
    return (
      <div className="p-4">
        <LoadingState />
      </div>
    );

  const isStatGroup = groupKey === "stats";

  return (
    <div className="p-4 space-y-4">
      <PageHeader icon={Medal} title="명장일람" />

      {/* NPC toggle */}
      <div className="flex items-center gap-3">
        <div className="flex border border-gray-600 rounded-md overflow-hidden">
          {(["user", "npc"] as const).map((mode) => (
            <button
              key={mode}
              onClick={() => setNpcMode(mode)}
              className={`px-3 py-1.5 text-xs transition-colors ${
                npcMode === mode
                  ? "bg-[#141c65] text-white"
                  : "text-gray-400 hover:text-white"
              }`}
            >
              {mode === "user" ? "유저 보기" : "NPC 보기"}
            </button>
          ))}
        </div>
      </div>

      {/* Group tabs */}
      <Tabs
        value={groupKey}
        onValueChange={(v) => setGroupKey(v as CategoryGroup)}
      >
        <TabsList>
          {GROUPS.map((g) => (
            <TabsTrigger key={g.key} value={g.key}>
              {g.label}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      {/* Sub-category tabs */}
      <Tabs
        value={categoryKey}
        onValueChange={(value) =>
          setCategoryByGroup((prev) => ({ ...prev, [groupKey]: value }))
        }
      >
        <TabsList>
          {group.categories.map((c) => (
            <TabsTrigger key={c.key} value={c.key}>
              {c.label}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      {/* Rankings table */}
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-12">#</TableHead>
            <TableHead>이름</TableHead>
            <TableHead>소속</TableHead>
            {isStatGroup ? (
              <>
                <TableHead>통솔</TableHead>
                <TableHead>무력</TableHead>
                <TableHead>지력</TableHead>
                <TableHead>정치</TableHead>
                <TableHead>매력</TableHead>
                <TableHead>총합</TableHead>
              </>
            ) : (
              <TableHead>{category.label}</TableHead>
            )}
          </TableRow>
        </TableHeader>
        <TableBody>
          {ranked.map((g, idx) => {
            const nation = nationMap.get(g.nationId);
            const total =
              g.leadership + g.strength + g.intel + g.politics + g.charm;

            return (
              <TableRow key={g.id}>
                <TableCell>{rankMedal(idx)}</TableCell>
                <TableCell className="font-medium">
                  <div className="flex items-center gap-2">
                    <GeneralPortrait
                      picture={g.picture}
                      name={g.name}
                      size="sm"
                    />
                    {g.name}
                  </div>
                </TableCell>
                <TableCell>
                  <NationBadge name={nation?.name} color={nation?.color} />
                </TableCell>
                {isStatGroup ? (
                  <>
                    <TableCell
                      className={
                        categoryKey === "leadership"
                          ? "text-amber-400 font-medium"
                          : ""
                      }
                    >
                      {g.leadership}
                    </TableCell>
                    <TableCell
                      className={
                        categoryKey === "strength"
                          ? "text-amber-400 font-medium"
                          : ""
                      }
                    >
                      {g.strength}
                    </TableCell>
                    <TableCell
                      className={
                        categoryKey === "intel"
                          ? "text-amber-400 font-medium"
                          : ""
                      }
                    >
                      {g.intel}
                    </TableCell>
                    <TableCell
                      className={
                        categoryKey === "politics"
                          ? "text-amber-400 font-medium"
                          : ""
                      }
                    >
                      {g.politics}
                    </TableCell>
                    <TableCell
                      className={
                        categoryKey === "charm"
                          ? "text-amber-400 font-medium"
                          : ""
                      }
                    >
                      {g.charm}
                    </TableCell>
                    <TableCell
                      className={
                        categoryKey === "total"
                          ? "text-amber-400 font-medium"
                          : ""
                      }
                    >
                      {total}
                    </TableCell>
                  </>
                ) : (
                  <TableCell className="text-amber-400 font-medium">
                    {formatValue(g.__value, category.valueType)}
                    {dexLabel(categoryKey, g.__value)}
                  </TableCell>
                )}
              </TableRow>
            );
          })}
          {ranked.length === 0 && (
            <TableRow>
              <TableCell
                colSpan={isStatGroup ? 9 : 4}
                className="text-center text-muted-foreground"
              >
                데이터가 없습니다.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
}
