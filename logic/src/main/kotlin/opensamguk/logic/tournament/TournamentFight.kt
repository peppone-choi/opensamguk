package opensamguk.logic.tournament

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.log10
import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.util.PhpMt19937
import opensamguk.logic.util.phpRound

/**
 * 대회 fight() 충실 포팅 — hwe/func_tournament.php:1004-1393 (GRAND TRUTH).
 *
 * ★ RNG divergence 주의: 이 경로만 sammo RandUtil/LiteHashDRBG가 아니라 PHP 네이티브
 *   rand()/mt_rand()/array_rand()(=[PhpMt19937])를 쓴다. draw 순서·횟수·인자 전부 파리티 대상.
 *   골든: logic/src/test/resources/golden/tournament/fight-fixtures.json
 *   (rngConformance 벡터 + fight 케이스 6종 + qualify 승격 — real PHP 캡처).
 */

/** rank_data 테이블 접근 포트 — fight()의 `{tp2}{w|d|l|g}` 읽기/증분(UPDATE는 행 없으면 no-op). */
interface TournamentRankPort {
    /** `SELECT value FROM rank_data WHERE general_id=%i AND type=%s` — 행 없으면 null (PHP `?? 0`은 호출측). */
    fun value(generalId: Int, type: String): Int?

    /** `UPDATE rank_data SET value=value+%i WHERE general_id=%i AND type=%s` — 행 없으면 no-op (PHP UPDATE 동작). */
    fun increase(generalId: Int, type: String, amount: Int)
}

/** rank_data 미연결 컨텍스트용 no-op (더미 장수 no=0의 PHP UPDATE 미적중과 동일 결과). */
object NoopTournamentRankPort : TournamentRankPort {
    override fun value(generalId: Int, type: String): Int? = null
    override fun increase(generalId: Int, type: String, amount: Int) = Unit
}

/** 대회 전투 로그 파일 포트 — eraseTnmtFightLog/pushTnmtFightLog (func_history.php:52-63). */
interface TournamentFightLogPort {
    fun erase(group: Int)
    fun push(group: Int, lines: List<String>)
}

object NoopTournamentFightLogPort : TournamentFightLogPort {
    override fun erase(group: Int) = Unit
    override fun push(group: Int, lines: List<String>) = Unit
}

/**
 * getTwo — func_tournament.php:550-581. 예선(2)/본선(4)의 페이즈별 대전 grp_no 쌍.
 * 예선은 28경기 리그를 2바퀴 돌며 두 번째 바퀴(phase>=28)는 홈/어웨이 스왑.
 */
fun getTwo(tournament: Int, phase: Int): Pair<Int, Int>? = when (tournament) {
    2 -> {
        val candMap = listOf(
            0 to 1, 2 to 3, 4 to 5, 6 to 7,
            0 to 2, 1 to 3, 4 to 6, 5 to 7,
            0 to 3, 1 to 6, 2 to 5, 4 to 7,
            0 to 4, 1 to 5, 2 to 6, 3 to 7,
            0 to 5, 1 to 4, 2 to 7, 3 to 6,
            0 to 6, 1 to 7, 2 to 4, 3 to 5,
            0 to 7, 1 to 2, 3 to 4, 5 to 6,
        )
        val cand = candMap[phase % 28]
        if (phase >= 28) cand.second to cand.first else cand
    }
    4 -> listOf(0 to 1, 2 to 3, 0 to 2, 1 to 3, 0 to 3, 1 to 2)[phase % 6]
    else -> null
}

/**
 * fight()의 tp='total' 값 — SQL `(leadership+strength+intel)*7/15` (func_tournament.php:1011).
 * MariaDB DECIMAL 나눗셈: 소수 4자리(div_precision_increment 기본), half-away 반올림.
 * 예: (90+88+85)*7/15 = 1841/15 → "122.7333", 1057/15 → "70.4667" (골든 total 문자열과 일치).
 */
fun tournamentTotalStat(leadership: Int, strength: Int, intel: Int): Double =
    BigDecimal((leadership + strength + intel) * 7)
        .divide(BigDecimal(15), 4, RoundingMode.HALF_UP)
        .toDouble()

/** getLog — func_tournament.php:993-1001. 10차이 1.1, 50차이 1.17, 100차이 1.2. PHP log($x,10)=log10. */
internal fun tournamentLevelRatio(lvl1: Int, lvl2: Int): Double =
    if (lvl1 >= lvl2) {
        1 + log10((1 + lvl1 - lvl2).toDouble()) / 10
    } else {
        1 - log10((1 + lvl2 - lvl1).toDouble()) / 10
    }

/** StringUtil::padStringAlignRight(str, maxsize, "0") — 숫자 문자열 도메인(mb_strwidth==length). */
private fun padRight0(str: String, maxsize: Int): String =
    if (str.length >= maxsize) str else "0".repeat(maxsize - str.length) + str

/**
 * buildItemClass($code)->isBuyable() — GameConst.allItems cnt>0 ⟺ 비구매(진귀/유니크).
 * (UpdateNationLevel.kt:249-264와 동일 판정 — PHP ActionItem `$buyable` 전수 대조 근거.)
 * 'None'/미등록 → BaseItem 기본 buyable=true.
 */
private fun isItemBuyable(code: String): Boolean {
    if (code.isEmpty() || code == "None") return true
    val cnt = GameConst.allItems.values.firstNotNullOfOrNull { it[code] } ?: return true
    return cnt == 0
}

/**
 * `$item->getRawName()` — 명마/무기/서적(BaseStatItem)은 코드 마지막 토큰(예: che_명마_15_적토마 → "적토마").
 * fight()의 아이템 로그 조사(josa)는 표시명이 아니라 이 원명으로 고른다(PHP 원문).
 */
private fun itemRawNameOf(code: String): String {
    val tokens = code.split("_")
    if (tokens.size < 3) return "-"
    val category = tokens[1]
    if (category == "명마" || category == "무기" || category == "서적") return tokens.last()
    return tokens.drop(2).joinToString(" ")
}

/**
 * fight() — func_tournament.php:1004-1393의 1:1 포팅. 반환값 = 승부 sel (0:g1승, 1:g2승, 2:무).
 *
 * 공유 [rng] 스트림: PHP 전역 mt_rand 상태와 동일하게, 한 프로세스 틱의 모든 fight가 같은
 * [PhpMt19937] 인스턴스를 순서대로 소비한다(qualify의 8조 연속 경기 골든이 이를 증명).
 */
class TournamentFightEngine(
    private val store: TournamentStore,
    private val rank: TournamentRankPort,
    private val fightLog: TournamentFightLogPort,
    private val rng: PhpMt19937,
) {
    fun fight(tnmtType: Int, tnmt: Int, phs: Int, group: Int, g1: Int, g2: Int, type: Int): Int {
        val log = mutableListOf<String>()

        fightLog.erase(group) // eraseTnmtFightLog($group)

        // SELECT *,(l+s+i)*7/15 as total from tournament where grp=%i AND grp_no=%i
        val gen1 = store.entries().first { it.group == group && it.groupNo == g1 }
        val gen2 = store.entries().first { it.group == group && it.groupNo == g2 }

        val turn = if (type == 0) 10 else 100 // 0: 승무패 10합, 1: 승패 100합(재대결)

        val tp2 = when (tnmtType) {
            0 -> "tt"; 1 -> "tl"; 2 -> "ts"; 3 -> "ti"
            else -> error("MustNotBeReached: tnmt_type $tnmtType") // MustNotBeReachedException
        }
        // $gen[$tp] — tnmt_type 0은 SQL DECIMAL total(float), 1~3은 int 스탯.
        fun statOf(e: TournamentEntry): Double = when (tnmtType) {
            0 -> tournamentTotalStat(e.leadership, e.strength, e.intel)
            1 -> e.leadership.toDouble()
            2 -> e.strength.toDouble()
            else -> e.intel.toDouble()
        }
        val stat1 = statOf(gen1)
        val stat2 = statOf(gen2)

        // ★ PHP 원문 그대로: gen2의 배율도 getLog($gen1['lvl'], $gen2['lvl']) — 인자 스왑 안 함(quirk 보존).
        val e1 = phpRound(stat1 * tournamentLevelRatio(gen1.level, gen2.level) * 10)
        val e2 = phpRound(stat2 * tournamentLevelRatio(gen1.level, gen2.level) * 10)
        var energy1 = e1
        var energy2 = e2

        // ── 아이템 플레이버 로그 (gen1 → gen2 순, 비구매 아이템 + tnmt_type 매칭 시에만 draw) ──
        for (gen in listOf(gen1, gen2)) {
            if (!isItemBuyable(gen.horse) && (tnmtType == 0 || tnmtType == 1)) {
                val itemName = GameConst.itemNameOf(gen.horse)
                val itemRawName = itemRawNameOf(gen.horse)
                when (rng.mtRand() % 4) { // DRAW: rand() % 4 (func_tournament.php:1045)
                    0 -> {
                        val josaYi = JosaUtil.pick(itemRawName, "이")
                        log += "<S>●</> <Y>${gen.name}</>의 <S>${itemName}</>${josaYi} 포효합니다!"
                    }
                    1 -> {
                        val josaYi = JosaUtil.pick(itemRawName, "이")
                        log += "<S>●</> <Y>${gen.name}</>의 <S>${itemName}</>${josaYi} 그 위용을 뽐냅니다!"
                    }
                    2 -> {
                        val josaYi = JosaUtil.pick(gen.name, "이")
                        val josaUl = JosaUtil.pick(itemRawName, "을")
                        log += "<S>●</> <Y>${gen.name}</>${josaYi} <S>${itemName}</>${josaUl} 타고 있습니다!"
                    }
                    3 -> {
                        val josaYi = JosaUtil.pick(itemRawName, "이")
                        log += "<S>●</> <Y>${gen.name}</>의 <S>${itemName}</>${josaYi} 갈기를 휘날립니다!"
                    }
                }
            }
            if (!isItemBuyable(gen.weapon) && (tnmtType == 0 || tnmtType == 2)) {
                val itemName = GameConst.itemNameOf(gen.weapon)
                val itemRawName = itemRawNameOf(gen.weapon)
                when (rng.mtRand() % 4) { // DRAW: rand() % 4 (func_tournament.php:1068)
                    0 -> {
                        val josaYi = JosaUtil.pick(itemRawName, "이")
                        log += "<S>●</> <Y>${gen.name}</>의 <S>${itemName}</>${josaYi} 번뜩입니다!"
                    }
                    1 -> {
                        val josaYi = JosaUtil.pick(itemRawName, "이")
                        log += "<S>●</> <Y>${gen.name}</>의 <S>${itemName}</>${josaYi} 푸르게 빛납니다!"
                    }
                    2 -> log += "<S>●</> <Y>${gen.name}</>의 <S>${itemName}</>에서 살기가 느껴집니다!"
                    3 -> {
                        val josaYi = JosaUtil.pick(itemRawName, "이")
                        log += "<S>●</> <Y>${gen.name}</>의 손에는 <S>${itemName}</>${josaYi} 쥐어져 있습니다!"
                    }
                }
            }
            if (!isItemBuyable(gen.book) && (tnmtType == 0 || tnmtType == 3)) {
                val itemName = GameConst.itemNameOf(gen.book)
                val itemRawName = itemRawNameOf(gen.book)
                when (rng.mtRand() % 4) { // DRAW: rand() % 4 (func_tournament.php:1089)
                    0 -> {
                        val josaYi = JosaUtil.pick(gen.name, "이")
                        val josaUl = JosaUtil.pick(itemRawName, "을")
                        log += "<S>●</> <Y>${gen.name}</>${josaYi} <S>${itemName}</>${josaUl} 펼쳐듭니다!"
                    }
                    1 -> {
                        val josaYi = JosaUtil.pick(gen.name, "이")
                        val josaUl = JosaUtil.pick(itemRawName, "을")
                        log += "<S>●</> <Y>${gen.name}</>${josaYi} <S>${itemName}</>${josaUl} 품에서 꺼냅니다!"
                    }
                    2 -> {
                        val josaYi = JosaUtil.pick(gen.name, "이")
                        val josaUl = JosaUtil.pick(itemRawName, "을")
                        log += "<S>●</> <Y>${gen.name}</>${josaYi} <S>${itemName}</>${josaUl} 들고 있습니다!"
                    }
                    3 -> {
                        val josaYi = JosaUtil.pick(itemRawName, "이")
                        log += "<S>●</> <Y>${gen.name}</>의 손에는 <S>${itemName}</>${josaYi} 쥐어져 있습니다!"
                    }
                }
            }
        }

        log += "<S>●</> <Y>${gen1.name}</> <C>(${energy1})</> vs <C>(${energy2})</> <Y>${gen2.name}</>"

        var gd1 = 0
        var gd2 = 0
        var phase = 0
        var sel = 2
        while (phase < turn) {
            phase++
            // 평타 90~110% — DRAW×2 (func_tournament.php:1122-1123)
            var damage1 = phpRound(stat2 * (rng.mtRand() % 21 + 90) / 130.0).toDouble()
            var damage2 = phpRound(stat1 * (rng.mtRand() % 21 + 90) / 130.0).toDouble()
            // 보너스타 — DRAW (%100), 적중 시 DRAW (%41+10) (1125-1132)
            var ratio = rng.mtRand() % 100
            if (stat1 >= ratio) {
                damage2 += phpRound(stat1 * (rng.mtRand() % 41 + 10) / 130.0)
            }
            ratio = rng.mtRand() % 100
            if (stat2 >= ratio) {
                damage1 += phpRound(stat2 * (rng.mtRand() % 41 + 10) / 130.0)
            }
            var critical1 = false
            var critical2 = false

            // 막판 분노 — DRAW (%300), 발동 시 DRAW (%301+200) + choiceRandom(2) (1158-1171)
            var factor1 = 1.0
            var factor2 = 1.0
            ratio = rng.mtRand() % 300
            if (e1 / 5.0 > energy1 && damage1 > damage2 && stat1 >= ratio) {
                factor2 = phpRound((rng.mtRand() % 301 + 200) / 100.0).toDouble() // 200~500%
                critical1 = true
                val str = CRITICAL_SKILL_MAP.getValue(tnmtType)[rng.arrayRand(2)] // DRAW: Util::choiceRandom
                log += "<S>●</> <Y>${gen1.name}</>의 분노의 <M>${str}</> 공격!"
            }
            ratio = rng.mtRand() % 300
            if (e2 / 5.0 > energy2 && damage2 > damage1 && stat2 >= ratio) {
                factor1 = phpRound((rng.mtRand() % 301 + 200) / 100.0).toDouble()
                critical2 = true
                val str = CRITICAL_SKILL_MAP.getValue(tnmtType)[rng.arrayRand(2)]
                log += "<S>●</> <Y>${gen2.name}</>의 분노의 <M>${str}</> 공격!"
            }
            damage1 *= factor1
            damage2 *= factor2

            if (phase == 1) {
                // 1합 승부 — DRAW (%400) 한 번만 뽑아 양쪽 판정에 공용 (1178-1188)
                val ratio400 = rng.mtRand() % 400
                if (stat1 * 0.9 > stat2 && stat1 >= ratio400) {
                    damage1 = 0.0
                    damage2 = e2.toDouble()
                    log += "<S>●</> <Y>${gen1.name}</>의 <M>${FATALITY_SKILL_MAP.getValue(tnmtType)}</>!"
                }
                if (stat2 * 0.9 > stat1 && stat2 >= ratio400) {
                    damage2 = 0.0
                    damage1 = e1.toDouble()
                    log += "<S>●</> <Y>${gen2.name}</>의 <M>${FATALITY_SKILL_MAP.getValue(tnmtType)}</>!"
                }
            } else {
                // 크리티컬 — DRAW (%1000), 발동 시 DRAW mt_rand(150,300) + choiceRandom(6) (1191-1204)
                var ratio1000 = rng.mtRand() % 1000
                if (!critical1 && stat1 >= ratio1000) {
                    damage2 *= rng.mtRand(150, 300) / 100.0 // DRAW: Util::randRangeInt = mt_rand(min,max)
                    critical1 = true
                    val str = SKILL_MAP.getValue(tnmtType)[rng.arrayRand(6)] // DRAW: Util::choiceRandom
                    log += "<S>●</> <Y>${gen1.name}</>의 <M>${str}</>!"
                }
                ratio1000 = rng.mtRand() % 1000
                if (!critical2 && stat2 >= ratio1000) {
                    damage1 *= rng.mtRand(150, 300) / 100.0
                    critical2 = true
                    val str = SKILL_MAP.getValue(tnmtType)[rng.arrayRand(6)]
                    log += "<S>●</> <Y>${gen2.name}</>의 <M>${str}</>!"
                }
            }

            // Util::setRound — half-away int화 (1207-1208)
            var d1 = phpRound(damage1)
            var d2 = phpRound(damage2)

            energy1 -= d1
            energy2 -= d2
            val tDamage1 = d1
            val tDamage2 = d2
            val tEnergy1 = energy1
            val tEnergy2 = energy2
            if (energy1 <= 0 && energy2 <= 0) {
                // 동시 KO — 비율(r1/r2)로 먼저 쓰러진 쪽 판정 후 오프셋 보정 (1216-1232)
                val r1 = tEnergy1.toDouble() / maxOf(tDamage1, 1)
                val r2 = tEnergy2.toDouble() / maxOf(tDamage2, 1)
                if (r1 > r2) {
                    val offset = phpRound(tEnergy2.toDouble() * tDamage1 / maxOf(tDamage2, 1))
                    d1 += offset
                    energy1 -= offset
                    d2 += tEnergy2
                    energy2 = 0
                } else {
                    val offset = phpRound(tEnergy1.toDouble() * tDamage2 / maxOf(tDamage1, 1))
                    d2 += offset
                    energy2 -= offset
                    d1 += tEnergy1
                    energy1 = 0
                }
            } else if (energy1 * energy2 <= 0) {
                // 단독 KO(한쪽 <=0) — 초과분 오프셋 보정 (1233-1248)
                if (energy2 < 0) {
                    val offset = phpRound(tEnergy2.toDouble() * tDamage1 / maxOf(tDamage2, 1))
                    d1 += offset
                    energy1 -= offset
                    d2 += tEnergy2
                    energy2 = 0
                }
                if (energy1 < 0) {
                    val offset = phpRound(tEnergy1.toDouble() * tDamage2 / maxOf(tDamage1, 1))
                    d2 += offset
                    energy2 -= offset
                    d1 += tEnergy1
                    energy1 = 0
                }
            }
            gd1 += d1
            gd2 += d2
            // PHP는 여기서 energy/damage를 재-round하지만 이미 int — no-op (1251-1254)

            log += "<S>●</> " +
                padRight0(phase.toString(), 2) + "合 : " +
                "<C>" + padRight0(energy1.toString(), 3) + "</>" +
                "<span class=\"ev_highlight\">(-" + padRight0(d1.toString(), 3) + ")</span>" +
                " vs " +
                "<span class=\"ev_highlight\">(-" + padRight0(d2.toString(), 3) + ")</span>" +
                "<C>" + padRight0(energy2.toString(), 3) + "</>"

            if (energy1 <= 0 && energy2 <= 0) {
                if (type == 0) {
                    sel = 2
                    break
                }
                // type=1 승패전 — 절반 에너지로 재대결 (1269-1271)
                energy1 = phpRound(e1 / 2.0)
                energy2 = phpRound(e2 / 2.0)
                log += "<S>●</> <span class='ev_highlight'>재대결</span>!"
            }
            if (energy1 <= 0) {
                sel = 1
                break
            }
            if (energy2 <= 0) {
                sel = 0
                break
            }
        }

        // rank_data {tp2}g 사전값 — 행 없으면 0 (1284-1285)
        val gen1gl = rank.value(gen1.id, tp2 + "g") ?: 0
        val gen2gl = rank.value(gen2.id, tp2 + "g") ?: 0

        val gl1: Int
        val gl2: Int
        val gen1resKey: String
        val gen2resKey: String
        when (sel) {
            0 -> {
                log += "<S>●</> <Y>${gen1.name}</> <S>승리</>!"
                val gl = phpRound((gd2 - gd1) / 50.0)
                store.upsert(gen1.copy(win = gen1.win + 1, goal = gen1.goal + gl))
                store.upsert(gen2.copy(lose = gen2.lose + 1, goal = gen2.goal - gl))
                if (gen1gl > gen2gl) {
                    gl1 = 1; gl2 = 0
                } else if (gen1gl == gen2gl) {
                    gl1 = 2; gl2 = -1
                } else {
                    gl1 = 3; gl2 = -2
                }
                gen1resKey = "w"
                gen2resKey = "l"
            }
            1 -> {
                log += "<S>●</> <Y>${gen2.name}</> <S>승리</>!"
                val gl = phpRound((gd1 - gd2) / 50.0)
                store.upsert(gen1.copy(lose = gen1.lose + 1, goal = gen1.goal - gl))
                store.upsert(gen2.copy(win = gen2.win + 1, goal = gen2.goal + gl))
                if (gen2gl > gen1gl) {
                    gl2 = 1; gl1 = 0
                } else if (gen2gl == gen1gl) {
                    gl2 = 2; gl1 = -1
                } else {
                    gl2 = 3; gl1 = -2
                }
                gen1resKey = "l"
                gen2resKey = "w"
            }
            else -> {
                log += "<S>●</> 무승부!"
                store.upsert(gen1.copy(draw = gen1.draw + 1))
                store.upsert(gen2.copy(draw = gen2.draw + 1))
                if (gen1gl > gen2gl) {
                    gl2 = -1; gl1 = 1
                } else if (gen1gl == gen2gl) {
                    gl2 = 0; gl1 = 0
                } else {
                    gl2 = 1; gl1 = -1
                }
                gen1resKey = "d"
                gen2resKey = "d"
            }
        }

        // rank_data 갱신 순서 = PHP UPDATE 순서 (1367-1379)
        rank.increase(gen1.id, tp2 + gen1resKey, 1)
        rank.increase(gen1.id, tp2 + "g", gl1)
        rank.increase(gen2.id, tp2 + gen2resKey, 1)
        rank.increase(gen2.id, tp2 + "g", gl2)

        // 다음경기 예고 — 예선(phs<55)/본선(phs<5)만 (1383-1390)
        if ((tnmt == 2 && phs < 55) || (tnmt == 4 && phs < 5)) {
            val cand = getTwo(tnmt, phs + 1)!!
            val gen1Name = store.entries().firstOrNull { it.group == group && it.groupNo == cand.first }?.name ?: ""
            val gen2Name = store.entries().firstOrNull { it.group == group && it.groupNo == cand.second }?.name ?: ""
            log += "--------------- 다음경기 ---------------<br><S>☞</> <Y>${gen1Name}</> vs <Y>${gen2Name}</>"
        }

        fightLog.push(group, log)
        return sel
    }

    private companion object {
        // func_tournament.php:1136-1153 — 페이즈 루프 안 선언이지만 RNG 미소비 상수라 호이스팅.
        val CRITICAL_SKILL_MAP = mapOf(
            0 to listOf("전력", "집중"),
            1 to listOf("봉시진", "어린진"),
            2 to listOf("삼단", "나선"),
            3 to listOf("독설", "논파"),
        )
        val FATALITY_SKILL_MAP = mapOf(
            0 to "압도",
            1 to "팔문금쇄진",
            2 to "일격 필살",
            3 to "모독 욕설",
        )
        val SKILL_MAP = mapOf(
            0 to listOf("참격", "집중", "역공", "반격", "선제", "도발"),
            1 to listOf("추행진", "학익진", "장사진", "형액진", "기형진", "구행진"),
            2 to listOf("기합", "기염", "반격", "역공", "삼단", "나선"),
            3 to listOf("논파", "항변", "반론", "반박", "도발", "면박"),
        )
    }
}
