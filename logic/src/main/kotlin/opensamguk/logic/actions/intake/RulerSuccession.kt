package opensamguk.logic.actions.intake

import kotlin.math.abs

/**
 * 군주 사망 후계 선정 순수 헬퍼 — PHP `func.php:1807 nextRuler`의 NPC 후계 후보 산출.
 *
 * 패리티 핵심 = (1) `npcmatch2` 친화도 거리 공식과 (2) `func.php:1839`의 **연산자 우선순위 버그**를
 * **그대로 재현**(rule #5: PHP를 "고치지" 않는다)하는 동률-후보 수집. 이 둘이 단 하나의 RNG 뽑기
 * `rng.choice(candidates)`의 후보 리스트를 결정하므로 draw-for-draw 패리티에 직결된다. 엔진 핸들러
 * (RulerSuccessionHandler)가 world 질의 + 정렬 + 시드 RNG + 승계 변이/로그를 담당한다.
 */
object RulerSuccession {

    /**
     * PHP `IF(ABS(affinity-ruler)>75, 150-ABS(affinity-ruler), ABS(affinity-ruler))` (func.php:1828).
     * 친화도 거리가 75 초과면 원형 거리(150-d)로 접는다(친화도는 0~149 원형 척도). 낮을수록 좋은 후보.
     */
    fun npcMatch2(candidateAffinity: Int, rulerAffinity: Int): Int {
        val d = abs(candidateAffinity - rulerAffinity)
        return if (d > 75) 150 - d else d
    }

    /**
     * PHP 동률-후보 수집 — **연산자 우선순위 버그 재현**.
     *
     * 원문(func.php:1837-1841):
     * ```php
     * $minNPCMatch = $rawCandidates[0]['npcmatch2'];
     * foreach ($rawCandidates as $candidate) {
     *     if (!$candidate['npcmatch2'] == $minNPCMatch) break;  // 버그: (!x) == min
     *     $candidates[] = $candidate;
     * }
     * ```
     * `!`가 `==`보다 강하게 바인딩 → `(!npcmatch2) == minNPCMatch`(느슨한 ==). PHP 느슨 비교에서
     * 좌변 bool, 우변 int → 우변을 bool 캐스트. 즉 `(npcmatch2==0) == (minNPCMatch!=0)`일 때 break.
     * - minMatch==0: `(npcmatch2==0)==false` → npcmatch2!=0에서 break → **선두 0-런만 수집**.
     * - minMatch!=0: `(npcmatch2==0)==true` → asc 정렬상 npcmatch2==0이 없으므로 **전체 수집**.
     * (의도는 `npcmatch2 != minMatch에서 break`였으나 버그가 후보 집합을 바꾼다. 재현 필수.)
     *
     * @param rawOrderedByMatchAsc PHP SQL `ORDER BY npcmatch2 ASC`와 동일하게 정렬된 후보(엔진이 보장).
     */
    fun <T> collectTiedCandidates(rawOrderedByMatchAsc: List<T>, matchOf: (T) -> Int): List<T> {
        if (rawOrderedByMatchAsc.isEmpty()) return emptyList()
        val minBool = matchOf(rawOrderedByMatchAsc.first()) != 0 // PHP (bool)$minNPCMatch
        val out = mutableListOf<T>()
        for (c in rawOrderedByMatchAsc) {
            val notMatch = matchOf(c) == 0                       // PHP !$candidate['npcmatch2']
            if (notMatch == minBool) break                      // PHP (!npcmatch2) == minNPCMatch
            out.add(c)
        }
        return out
    }
}
