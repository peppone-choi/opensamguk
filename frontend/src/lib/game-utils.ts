// ============================================================
// Game utility functions — ported from legacy hwe/ts/utilGame & util
// ============================================================

// --- Color utilities ---

function hexToRgb(hex: string): { r: number; g: number; b: number } | null {
  const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
  return result
    ? {
        r: parseInt(result[1], 16),
        g: parseInt(result[2], 16),
        b: parseInt(result[3], 16),
      }
    : null;
}

export function isBrightColor(color: string): boolean {
  const cv = hexToRgb(color);
  if (!cv) return false;
  return cv.r * 0.299 + cv.g * 0.587 + cv.b * 0.114 > 140;
}

// --- Injury ---

export function calcInjury(baseStat: number, injury: number): number {
  return Math.round((baseStat * (100 - injury)) / 100);
}

export function formatInjury(injury: number): { text: string; color: string } {
  if (injury <= 0) return { text: "건강", color: "white" };
  if (injury <= 20) return { text: "경상", color: "yellow" };
  if (injury <= 40) return { text: "중상", color: "orange" };
  if (injury <= 60) return { text: "심각", color: "magenta" };
  return { text: "위독", color: "red" };
}

// --- General type classification ---

// Default game constants (from legacy GameConstBase / default.json)
const DEFAULT_CHIEF_STAT_MIN = 65;
const DEFAULT_STAT_GRADE_LEVEL = 5;

export function formatGeneralTypeCall(
  leadership: number,
  strength: number,
  intel: number,
  chiefStatMin: number = DEFAULT_CHIEF_STAT_MIN,
  statGradeLevel: number = DEFAULT_STAT_GRADE_LEVEL,
): string {
  if (leadership < 40) {
    if (strength + intel < 40) return "아둔";
    if (intel >= chiefStatMin && strength < intel * 0.8) return "학자";
    if (strength >= chiefStatMin && intel < strength * 0.8) return "장사";
    return "명사";
  }

  const maxStat = Math.max(leadership, strength, intel);
  const sum2Stat = Math.min(
    leadership + strength,
    strength + intel,
    intel + leadership,
  );
  if (maxStat >= chiefStatMin + statGradeLevel && sum2Stat >= maxStat * 1.7)
    return "만능";
  if (strength >= chiefStatMin - statGradeLevel && intel < strength * 0.8)
    return "용장";
  if (intel >= chiefStatMin - statGradeLevel && strength < intel * 0.8)
    return "명장";
  if (
    leadership >= chiefStatMin - statGradeLevel &&
    strength + intel < leadership
  )
    return "차장";
  return "평범";
}

// --- NPC color ---

export function getNPCColor(npcState: number): string | undefined {
  if (npcState === 6) return "mediumaquamarine";
  if (npcState === 5) return "darkcyan";
  if (npcState === 4) return "deepskyblue";
  if (npcState >= 2) return "cyan";
  if (npcState === 1) return "skyblue";
  return undefined;
}

// --- Refresh score ---

const refreshScoreMap: [number, string][] = [
  [0, "안함"],
  [50, "무관심"],
  [100, "보통"],
  [200, "가끔"],
  [400, "자주"],
  [800, "열심"],
  [1600, "중독"],
  [3200, "폐인"],
  [6400, "경고"],
  [12800, "헐..."],
];

/** Binary-search a sorted [threshold, label][] array and return the label for the matching bucket. */
function searchThresholdMap(map: [number, string][], value: number): string {
  let lo = 0;
  let hi = map.length - 1;
  let result = 0;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    if (map[mid][0] <= value) {
      result = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return map[result][1];
}

export function formatRefreshScore(score: number): string {
  if (!score) score = 0;
  return searchThresholdMap(refreshScoreMap, score);
}

// --- Experience / level ---

export function nextExpLevelRemain(
  experience: number,
  expLevel: number,
): [number, number] {
  if (experience < 1000) {
    return [experience - expLevel * 100, 100];
  }
  const expBase = 10 * expLevel ** 2;
  const expNext = 10 * (expLevel + 1) ** 2;
  return [experience - expBase, expNext - expBase];
}

// --- Officer level text ---

const OfficerLevelMapDefault: Record<number, string> = {
  12: "군주",
  11: "참모",
  10: "제1장군",
  9: "제1모사",
  8: "제2장군",
  7: "제2모사",
  6: "제3장군",
  5: "제3모사",
  4: "태수",
  3: "군사",
  2: "종사",
  1: "일반",
  0: "재야",
};

const OfficerLevelMapByNationLevel: Record<number, Record<number, string>> = {
  7: {
    12: "황제",
    11: "승상",
    10: "표기장군",
    9: "사공",
    8: "거기장군",
    7: "태위",
    6: "위장군",
    5: "사도",
  },
  6: {
    12: "왕",
    11: "광록훈",
    10: "좌장군",
    9: "상서령",
    8: "우장군",
    7: "중서령",
    6: "전장군",
    5: "비서령",
  },
  5: {
    12: "공",
    11: "광록대부",
    10: "안국장군",
    9: "집금오",
    8: "파로장군",
    7: "소부",
  },
  4: {
    12: "주목",
    11: "태사령",
    10: "아문장군",
    9: "낭중",
    8: "호군",
    7: "종사중랑",
  },
  3: {
    12: "주자사",
    11: "주부",
    10: "편장군",
    9: "간의대부",
  },
  2: {
    12: "군벌",
    11: "참모",
    10: "비장군",
    9: "부참모",
  },
  1: {
    12: "영주",
    11: "참모",
  },
  0: {
    12: "두목",
    11: "부두목",
  },
};

export function formatOfficerLevelText(
  officerLevel: number,
  nationLevel?: number,
): string {
  if (officerLevel < 5) {
    return OfficerLevelMapDefault[officerLevel] ?? "???";
  }

  const nationMap =
    nationLevel === undefined
      ? OfficerLevelMapDefault
      : (OfficerLevelMapByNationLevel[nationLevel] ?? OfficerLevelMapDefault);

  return (
    nationMap[officerLevel] ?? OfficerLevelMapDefault[officerLevel] ?? "???"
  );
}

// --- Age color (legacy parity: 3-color based on retirementYear) ---

export function ageColor(age: number, retirementYear: number = 80): string {
  if (age < retirementYear * 0.75) return "limegreen";
  if (age < retirementYear) return "yellow";
  return "red";
}

// --- Defence train ---

const defenceMap: [number, string][] = [
  [0, "△"],
  [60, "○"],
  [80, "◎"],
  [90, "☆"],
  [999, "×"],
];

export function formatDefenceTrain(defenceTrain: number): string {
  return searchThresholdMap(defenceMap, defenceTrain);
}

// --- Dexterity / dedication level ---

const DexLevelMap: [number, string, string][] = [
  [0, "navy", "F-"],
  [350, "navy", "F"],
  [1375, "navy", "F+"],
  [3500, "skyblue", "E-"],
  [7125, "skyblue", "E"],
  [12650, "skyblue", "E+"],
  [20475, "seagreen", "D-"],
  [31000, "seagreen", "D"],
  [44625, "seagreen", "D+"],
  [61750, "teal", "C-"],
  [82775, "teal", "C"],
  [108100, "teal", "C+"],
  [138125, "limegreen", "B-"],
  [173250, "limegreen", "B"],
  [213875, "limegreen", "B+"],
  [260400, "darkorange", "A-"],
  [313225, "darkorange", "A"],
  [372750, "darkorange", "A+"],
  [439375, "tomato", "S-"],
  [513500, "tomato", "S"],
  [595525, "tomato", "S+"],
  [685850, "darkviolet", "Z-"],
  [784875, "darkviolet", "Z"],
  [893000, "darkviolet", "Z+"],
  [1010625, "gold", "EX-"],
  [1138150, "gold", "EX"],
  [1275975, "white", "EX+"],
];

export interface DexInfo {
  level: number;
  name: string;
  color: string;
}

export function formatDexLevel(dex: number): DexInfo {
  let lo = 0;
  let hi = DexLevelMap.length - 1;
  let result = 0;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    if (DexLevelMap[mid][0] <= dex) {
      result = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  const [, color, name] = DexLevelMap[result];
  return { level: result, name, color };
}

// --- Honor (experience label) ---

const honorMap: [number, string][] = [
  [0, "전무"],
  [640, "무명"],
  [2560, "신동"],
  [5760, "약간"],
  [10240, "평범"],
  [16000, "지역적"],
  [23040, "전국적"],
  [31360, "세계적"],
  [40960, "유명"],
  [45000, "명사"],
  [51840, "호걸"],
  [55000, "효웅"],
  [64000, "영웅"],
  [77440, "구세주"],
];

export function formatHonor(experience: number): string {
  return searchThresholdMap(honorMap, experience);
}

// --- Tech level ---

export const TECH_LEVEL_STEP = 1000;

export function convTechLevel(tech: number, maxTechLevel: number): number {
  return Math.min(
    Math.max(Math.floor(tech / TECH_LEVEL_STEP), 0),
    maxTechLevel,
  );
}

export function getMaxRelativeTechLevel(
  startYear: number,
  year: number,
  maxTechLevel: number,
  initialAllowedTechLevel: number,
  techLevelIncYear: number,
): number {
  const relYear = year - startYear;
  return Math.min(
    Math.max(
      Math.floor(relYear / techLevelIncYear) + initialAllowedTechLevel,
      1,
    ),
    maxTechLevel,
  );
}

export function isTechLimited(
  startYear: number,
  year: number,
  tech: number,
  maxTechLevel: number,
  initialAllowedTechLevel: number,
  techLevelIncYear: number,
): boolean {
  const relMaxTech = getMaxRelativeTechLevel(
    startYear,
    year,
    maxTechLevel,
    initialAllowedTechLevel,
    techLevelIncYear,
  );
  const techLevel = convTechLevel(tech, maxTechLevel);
  return techLevel >= relMaxTech;
}

// --- Number formatting ---

export function numberWithCommas(x: number): string {
  return x.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

// --- Valid object key check ---

export function isValidObjKey<T>(key: T | "None" | undefined | null): boolean {
  if (key === "None" || key === undefined || key === null) return false;
  return true;
}

// --- Crew type names ---

export const CREW_TYPE_NAMES: Record<number, string> = {
  0: "보병",
  1: "궁병",
  2: "기병",
  3: "근위병",
  4: "창병",
  5: "노병",
  6: "철기병",
  7: "귀병",
};

// --- Region names ---

export const REGION_NAMES: Record<number, string> = {
  0: "중원",
  1: "하북",
  2: "서북",
  3: "서남",
  4: "강남",
  5: "형남",
};

// --- Stat color (ability value → color) ---

export function statColor(value: number): string {
  if (value >= 90) return "#eab308"; // gold
  if (value >= 80) return "#f97316"; // orange
  if (value >= 70) return "#22c55e"; // green
  if (value >= 60) return "#06b6d4"; // cyan
  if (value >= 50) return "#94a3b8"; // gray
  return "#6b7280"; // dim
}

// --- Trust (민심) color ---

export function trustColor(trust: number): string {
  if (trust >= 80) return "#22c55e";
  if (trust >= 60) return "#eab308";
  if (trust >= 40) return "#f97316";
  return "#ef4444";
}

// --- City level names ---

export const CITY_LEVEL_NAMES: Record<number, string> = {
  1: "소도시",
  2: "도시",
  3: "대도시",
  4: "특대도시",
  5: "거대도시",
  6: "거점",
  7: "수도",
};
