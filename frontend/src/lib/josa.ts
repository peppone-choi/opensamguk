// Korean postposition (조사) utility — simplified from legacy JosaUtil.ts
// Handles 은/는, 이/가, 을/를, 과/와, 으로/로, 이나/나, 이라/라, 이랑/랑

const KO_START = 44032;
const KO_END = 55203;

const JOSA_MAP: Record<string, [string, string]> = {
  "은": ["은", "는"],
  "는": ["은", "는"],
  "이": ["이", "가"],
  "가": ["이", "가"],
  "을": ["을", "를"],
  "를": ["을", "를"],
  "과": ["과", "와"],
  "와": ["과", "와"],
  "으로": ["으로", "로"],
  "로": ["으로", "로"],
  "이나": ["이나", "나"],
  "나": ["이나", "나"],
  "이라": ["이라", "라"],
  "라": ["이라", "라"],
  "이랑": ["이랑", "랑"],
  "랑": ["이랑", "랑"],
};

/**
 * Check if the last character of the text has a jongsung (final consonant).
 * For non-Korean characters, uses heuristic based on common English endings.
 */
function hasJongsung(text: string): boolean {
  if (!text) return false;
  const clean = text.replace(/[^a-zA-Z0-9ㄱ-ㅎ가-힣\s]/g, "").trim();
  if (!clean) return false;
  const last = clean.charCodeAt(clean.length - 1);

  // Korean syllable
  if (last >= KO_START && last <= KO_END) {
    return (last - KO_START) % 28 !== 0;
  }

  // Korean jamo consonant (ㄱ-ㅎ)
  if (last >= 0x3131 && last <= 0x314e) return true;

  // Number
  if (last >= 0x30 && last <= 0x39) {
    // 0,1,3,6,7,8 have jongsung in Korean reading
    return [0, 1, 3, 6, 7, 8].includes(last - 0x30);
  }

  // English — approximate based on common endings
  const ch = clean[clean.length - 1].toLowerCase();
  // Letters that typically end with jongsung-like sounds: l, m, n, b, p, t, k, ng
  return "lmnbptk".includes(ch);
}

/**
 * Check if the last character triggers the 으로→로 exception (ends in ㄹ).
 */
function endsWithRieul(text: string): boolean {
  if (!text) return false;
  const clean = text.replace(/[^a-zA-Z0-9ㄱ-ㅎ가-힣\s]/g, "").trim();
  if (!clean) return false;
  const last = clean.charCodeAt(clean.length - 1);

  if (last >= KO_START && last <= KO_END) {
    return (last - KO_START) % 28 === 8; // ㄹ jongsung
  }
  if (last === 0x3139) return true; // ㄹ jamo

  const ch = clean[clean.length - 1].toLowerCase();
  return ch === "l" || ch === "r";
}

/**
 * Pick the correct josa for the given text.
 * @param text - The preceding word
 * @param josa - Any form of the josa pair (e.g. "은", "는", "이", "가", etc.)
 * @returns The correct josa form
 *
 * @example
 * pick("검", "을") // "을"
 * pick("칼", "을") // "을"
 * pick("도끼", "을") // "를"
 * pick("바람", "이랑") // "이랑"
 * pick("불", "으로") // "로" (ㄹ exception)
 */
export function pick(text: string, josa: string): string {
  const pair = JOSA_MAP[josa];
  if (!pair) return josa;
  const [withJong, withoutJong] = pair;

  // Special case for 으로/로: ㄹ ending uses 로
  if (withJong === "으로") {
    if (endsWithRieul(text)) return "로";
    return hasJongsung(text) ? "으로" : "로";
  }

  return hasJongsung(text) ? withJong : withoutJong;
}

/**
 * Append the correct josa to the text.
 * @example
 * put("검", "을") // "검을"
 * put("도끼", "을") // "도끼를"
 */
export function put(text: string, josa: string): string {
  return `${text}${pick(text, josa)}`;
}
