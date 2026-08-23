package opensamguk.logic.actions.instant

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * General-instant 액션 `DieOnPrestart` — 개전 전(prestart) 자기 장수 자살(삭제).
 *
 * 역사 PHP 기준 (ADR-LITE-042; 현재 제품 정본 아님): `legacy/devsam-core/hwe/sammo/API/General/DieOnPrestart.php` (`launch()` verbatim).
 *
 * 서버 **가오픈**(opentime 이전, `turntime <= opentime`) 동안만, 아직 어느 국가에도 소속되지 않은
 * (`nation == 0`) 자기 장수를, 마지막 새로고침(`lastRefresh`)으로부터 [GameConst.minTurnDieOnPrestart]
 * (=2)턴이 지난 뒤 삭제할 수 있다. 삭제 시 "홀연히 모습을 감추었습니다" 사망 로그를 전체 공지하고
 * (`kill(db,true,$dyingMessage)`), 게임 세션에서 로그아웃한다.
 *
 * 예약 커맨드([opensamguk.logic.actions.CommandRegistry]의 `che_*`)가 **아니라** `sammo\BaseAPI`를 상속한
 * **instant API 핸들러**(`launch()`)다 — dispatch 계약/배선은 [InstantActionRegistry] 참조. [CheckOwner]/
 * [opensamguk.logic.actions.intake.InheritResets]와 동일하게, 이 리졸버는 Spring/DB/World 없이 PHP
 * `launch()`가 읽는 환경 입력(자기 장수 행·game_env 시각·마지막 새로고침)을 받아 엔진 핸들러가 적용할
 * [DieOnPrestartOutcome]만 산출한다.
 *
 * ## RNG (parity)
 * **draws NOTHING** — 0-draw. 전부 시각/소속 게이트 + 결정적 사망 로그 1건이다(골든 N).
 *
 * ## 부수효과 (DieOnPrestart.php:31-82 실행 순서, byte-exact)
 *  1. `increaseRefresh("장수 삭제", 1)` (php:53) — `general_access_log` 새로고침 카운터 +1. 이는
 *     **game-api 인테이크/세션 측 부수효과**(userGrade/opentime/generalID로 게이트되는 access-log)이지
 *     데몬 사망 효과가 아니다 → 본 리졸버 밖(인테이크 seam). [DieOnPrestartOutcome]에 싣지 않는다.
 *  2. 게이트(아래 [resolve]의 deny 순서). PHP는 `!$general` 체크가 `increaseRefresh` **뒤**(php:49)에
 *     있으나, 행이 없으면 애초에 인테이크가 호출되지 않으므로(소유 장수 전제) `general 없음`은 게이트 선두로
 *     모델링한다(엔진 핸들러가 행 부재 시 [DieOnPrestartOutcome.Denied]("장수가 없습니다")로 단락).
 *  3. 통과 시 `kill(db, true, "<Y>{name}</>{josaYi} 홀연히 모습을 <R>감추었습니다</>")` (php:78):
 *     사망 로그를 **글로벌 액션 로그**로 공지([DieOnPrestartOutcome.Killed.dyingLog]) + 장수 사망 처리
 *     (storeOldGeneral → general/general_turn/rank_data/general_access_log 삭제, nation gennum-1 등
 *     `General.php:515-600` kill 캐스케이드)는 엔진 [ChangeRecorder] tombstone seam에 위임한다.
 *     prestart 장수는 `nation==0`이므로 officer_level 12(군주) 분기·소속국 gennum 감소는 무효.
 *  4. `$session->logoutGame()` (php:80) — 세션 로그아웃은 game-api 세션 측 효과(데몬 write 아님).
 */
object DieOnPrestart {

    /** PHP `substr($targetTime, 0, 19)` 및 `addTurn(...)` 직렬화 — `Y-m-d H:i:s`(소수초 절단). */
    val YMDHIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * DieOnPrestart.php `launch()`. 게이트 통과 시 자기 장수를 삭제(kill)하고 사망 로그를 산출한다.
     * 뽑지 않음(0-draw).
     *
     * 게이트 입력(엔진 핸들러가 자기 장수 행 + game_env KV + access-log에서 해석해 주입):
     * @param generalExists  자기 장수 행 존재 여부(`!$general` ⇒ deny). 통상 소유 장수 전제로 true.
     * @param generalName    자기 장수명(`general.name`) — 사망 로그 구성용.
     * @param nationId       자기 장수 `nation`(0이 아니면 deny — DieOnPrestart.php:60).
     * @param turntime       game_env `turntime`(현재 게임 진행 시각 문자열).
     * @param opentime       game_env `opentime`(서버 정식 개전 시각 문자열).
     * @param lastRefresh    `general_access_log.last_refresh` — 마지막 새로고침 시각(`addTurn` 기준).
     * @param turnterm       game_env `turnterm`(턴 간격 분) — `addTurn`의 분 환산.
     * @param now            현재 wall-clock(`TimeUtil::now()`; 결정성 위해 주입).
     */
    fun resolve(
        generalExists: Boolean,
        generalName: String,
        nationId: Int,
        turntime: String,
        opentime: String,
        lastRefresh: LocalDateTime,
        turnterm: Int,
        now: LocalDateTime,
    ): DieOnPrestartOutcome {
        // DieOnPrestart.php:49 — 자기 장수 행 부재.
        if (!generalExists) return DieOnPrestartOutcome.Denied("장수가 없습니다")

        // DieOnPrestart.php:56 — 게임이 이미 시작(turntime > opentime)되면 불가. PHP는 'Y-m-d H:i:s.u'
        //   문자열 비교(사전식 = 시간식). turntime/opentime 모두 동일 포맷이므로 문자열 비교로 byte-동일.
        if (turntime > opentime) return DieOnPrestartOutcome.Denied("게임이 시작되었습니다.")

        // DieOnPrestart.php:60 — 이미 국가에 소속(nation != 0)되면 불가.
        if (nationId != 0) return DieOnPrestartOutcome.Denied("이미 국가에 소속되어있습니다.")

        // DieOnPrestart.php:65 — addTurn(lastRefresh, turnterm, minTurnDieOnPrestart):
        //   lastRefresh + (turnterm * 2)분. 아직 이 시각 전이면(targetTime > now) 삭제 불가.
        val targetTime = lastRefresh.plusMinutes(turnterm.toLong() * GameConst.minTurnDieOnPrestart)
        if (targetTime.isAfter(now)) {
            // DieOnPrestart.php:67 — substr($targetTime, 0, 19) = 'Y-m-d H:i:s'(소수초 제외).
            val targetTimeShort = targetTime.format(YMDHIS)
            return DieOnPrestartOutcome.Denied("아직 삭제할 수 없습니다. $targetTimeShort 부터 가능합니다.")
        }

        // DieOnPrestart.php:76-78 — 사망 로그(글로벌 액션). JosaUtil::pick(name,'이') = 이/가.
        val josaYi = JosaUtil.pick(generalName, "이")
        val dyingLog = "<Y>$generalName</>$josaYi 홀연히 모습을 <R>감추었습니다</>"

        return DieOnPrestartOutcome.Killed(dyingLog = dyingLog)
    }
}

/** DieOnPrestart가 산출하는 효과(엔진 핸들러가 적용). */
sealed interface DieOnPrestartOutcome {
    /** 게이트/검증 거부 — PHP-충실 사유 문자열(API `launch` 반환값). */
    data class Denied(val reason: String) : DieOnPrestartOutcome

    /**
     * 성공: 자기 장수를 삭제(kill)한다. [dyingLog]를 **글로벌 액션 로그**로 공지하고, 장수 사망
     * 캐스케이드(`General.php:515-600` kill: storeOldGeneral → general/general_turn/rank_data/
     * general_access_log 삭제 등)는 엔진 [opensamguk.engine.turn.ChangeRecorder] tombstone seam이
     * 수행한다. 세션 로그아웃(`logoutGame`)은 game-api 세션 측 효과(데몬 write 아님).
     */
    data class Killed(val dyingLog: String) : DieOnPrestartOutcome
}
