package opensamguk.logic.actions.instant

import opensamguk.common.josa.JosaUtil
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GOLDEN — DieOnPrestart ([DieOnPrestart]) instant-action, draw COUNT = 0.
 *
 * PHP `legacy/devsam-core/hwe/sammo/API/General/DieOnPrestart.php` `launch()`는 RNG를 단 한 번도 끌지
 * 않는다(draw COUNT = 0). docker 골든 불필요 — 본 테스트가 골든을 대신한다:
 *
 *  - **0-draw (구조적)**: [DieOnPrestart.resolve]는 `RandUtil`/`LiteHashDrbg`를 **인자로 받지도,
 *    참조하지도 않는다**(InheritResets/CheckOwner 패턴의 순수 입력→Outcome). 시그니처상 draw가 불가능하므로
 *    0-draw가 강제된다.
 *  - **게이트(deny 사유) byte-exact**: 장수 없음 / 게임 시작됨 / 국가 소속 / 아직 삭제 불가(시각 포함).
 *  - **effect + log byte-exact**: 통과 시 [DieOnPrestartOutcome.Killed]의 사망 로그(글로벌 액션) 본문.
 *
 * 시각 픽스처는 모두 명시 주입(now/lastRefresh/turnterm) — wall-clock 비결정성 제거.
 */
class DieOnPrestartGoldenTest {

    private val opentime = "2024-01-01 00:00:00.000000"
    private val beforeOpen = "2023-12-31 23:59:00.000000"   // turntime <= opentime (가오픈 중)
    private val afterOpen = "2024-01-01 00:01:00.000000"    // turntime > opentime (개전됨)

    // ── deny: 자기 장수 행 부재(DieOnPrestart.php:49) ─────────────────────────────────────────────
    @Test
    fun `missing general is denied with the PHP-exact reason`() {
        val outcome = DieOnPrestart.resolve(
            generalExists = false,
            generalName = "관우",
            nationId = 0,
            turntime = beforeOpen,
            opentime = opentime,
            lastRefresh = LocalDateTime.of(2023, 12, 31, 0, 0, 0),
            turnterm = 60,
            now = LocalDateTime.of(2023, 12, 31, 23, 0, 0),
        )
        assertTrue(outcome is DieOnPrestartOutcome.Denied)
        assertEquals("장수가 없습니다", (outcome as DieOnPrestartOutcome.Denied).reason)
    }

    // ── deny: 게임 시작됨(turntime > opentime, DieOnPrestart.php:56) ───────────────────────────────
    @Test
    fun `game already started is denied`() {
        val outcome = DieOnPrestart.resolve(
            generalExists = true,
            generalName = "관우",
            nationId = 0,
            turntime = afterOpen,        // > opentime
            opentime = opentime,
            lastRefresh = LocalDateTime.of(2023, 12, 31, 0, 0, 0),
            turnterm = 60,
            now = LocalDateTime.of(2024, 1, 1, 0, 2, 0),
        )
        assertEquals(
            "게임이 시작되었습니다.",
            (outcome as DieOnPrestartOutcome.Denied).reason,
        )
    }

    // ── deny: 이미 국가 소속(nation != 0, DieOnPrestart.php:60) ───────────────────────────────────
    @Test
    fun `already belonging to a nation is denied`() {
        val outcome = DieOnPrestart.resolve(
            generalExists = true,
            generalName = "관우",
            nationId = 1,                // != 0
            turntime = beforeOpen,
            opentime = opentime,
            lastRefresh = LocalDateTime.of(2023, 12, 31, 0, 0, 0),
            turnterm = 60,
            now = LocalDateTime.of(2023, 12, 31, 23, 0, 0),
        )
        assertEquals(
            "이미 국가에 소속되어있습니다.",
            (outcome as DieOnPrestartOutcome.Denied).reason,
        )
    }

    // ── deny: 아직 삭제 불가 — targetTime = lastRefresh + turnterm*2분 > now (DieOnPrestart.php:65-68) ─
    @Test
    fun `too soon to delete is denied with the Y-m-d H-i-s target timestamp`() {
        // lastRefresh 22:30 + (60*2)분 = 다음날 00:30 가 targetTime. now=23:00 < targetTime → deny.
        val outcome = DieOnPrestart.resolve(
            generalExists = true,
            generalName = "관우",
            nationId = 0,
            turntime = beforeOpen,
            opentime = opentime,
            lastRefresh = LocalDateTime.of(2023, 12, 31, 22, 30, 0),
            turnterm = 60,
            now = LocalDateTime.of(2023, 12, 31, 23, 0, 0),
        )
        // substr(targetTime,0,19) = 'Y-m-d H:i:s'(소수초 절단). 22:30 + 120분 = 다음날 00:30.
        assertEquals(
            "아직 삭제할 수 없습니다. 2024-01-01 00:30:00 부터 가능합니다.",
            (outcome as DieOnPrestartOutcome.Denied).reason,
        )
    }

    // ── kill: 게이트 통과 — 사망 로그(받침 없는 이름 '관우' → josa '가') ───────────────────────────────
    @Test
    fun `gate passes and produces the dying log — no-final-consonant name picks 가`() {
        // lastRefresh 22:00 + 120분 = 다음날 00:00 = targetTime; now 00:00 → targetTime !after now → 통과.
        val outcome = DieOnPrestart.resolve(
            generalExists = true,
            generalName = "관우",       // 받침 없음 → JosaUtil.pick(_, "이") = "가"
            nationId = 0,
            turntime = beforeOpen,
            opentime = opentime,
            lastRefresh = LocalDateTime.of(2023, 12, 31, 22, 0, 0),
            turnterm = 60,
            now = LocalDateTime.of(2024, 1, 1, 0, 0, 0),
        )
        assertTrue(outcome is DieOnPrestartOutcome.Killed)
        val killed = outcome as DieOnPrestartOutcome.Killed

        // DieOnPrestart.php:78 — "<Y>{name}</>{josaYi} 홀연히 모습을 <R>감추었습니다</>".
        assertEquals("가", JosaUtil.pick("관우", "이"))   // josa 가정 검증.
        assertEquals(
            "<Y>관우</>가 홀연히 모습을 <R>감추었습니다</>",
            killed.dyingLog,
        )
    }

    // ── kill: 받침 있는 이름 '조운' → josa '이' ────────────────────────────────────────────────────
    @Test
    fun `dying log of a final-consonant name picks 이`() {
        val outcome = DieOnPrestart.resolve(
            generalExists = true,
            generalName = "조운",       // 'ㄴ' 받침 → JosaUtil.pick(_, "이") = "이"
            nationId = 0,
            turntime = beforeOpen,
            opentime = opentime,
            lastRefresh = LocalDateTime.of(2023, 12, 31, 0, 0, 0),  // 한참 전 → 즉시 통과.
            turnterm = 60,
            now = LocalDateTime.of(2024, 1, 1, 0, 0, 0),
        )
        val killed = outcome as DieOnPrestartOutcome.Killed
        assertEquals("이", JosaUtil.pick("조운", "이"))
        assertEquals(
            "<Y>조운</>이 홀연히 모습을 <R>감추었습니다</>",
            killed.dyingLog,
        )
    }

    // ── deny 우선순위: 게임시작 체크가 시각/소속 체크보다 먼저(DieOnPrestart.php:56→60→65) ───────────
    @Test
    fun `game-started gate precedes the nation and time gates`() {
        // nation!=0 이고 시각도 미달이지만, turntime>opentime 이 우선 → '게임이 시작되었습니다.'.
        val outcome = DieOnPrestart.resolve(
            generalExists = true,
            generalName = "관우",
            nationId = 5,
            turntime = afterOpen,
            opentime = opentime,
            lastRefresh = LocalDateTime.of(2099, 1, 1, 0, 0, 0),  // 미래 → 시각 미달이지만 우선순위 낮음.
            turnterm = 60,
            now = LocalDateTime.of(2024, 1, 1, 0, 2, 0),
        )
        assertEquals(
            "게임이 시작되었습니다.",
            (outcome as DieOnPrestartOutcome.Denied).reason,
        )
    }
}
