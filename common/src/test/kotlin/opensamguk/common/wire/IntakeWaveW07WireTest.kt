package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * W0-7 wire 계약 widen 라운드트립 (FOUNDATION — 엔진 핸들러는 W1 G/K/N 에이전트가 구현).
 *
 * 검증 대상:
 *  - [TurnDaemonCommand.DiploRespondLetter] 신규 변형 — PHP `j_diplomacy_respond_letter.php:16-18`
 *    POST 인자(`letterNo`/`isAgree`/`reason`) verbatim 미러. isAgree 기본 false / reason 기본 ''
 *    (PHP `Util::getPost('isAgree','bool',false)` / `Util::getPost('reason','string','')`).
 *  - [TurnDaemonCommand.MakeGeneral] widen — PHP `sammo/API/General/Join.php:142-145` 유산 4필드
 *    (`inheritSpecial`/`inheritTurntimeZone`/`inheritCity`/`inheritBonusStat`, 모두 `?? null`) +
 *    `Join.php:379-385` 전콘 resolved `picture`/`imgsvr` 쌍. 부재(null) 시 기존 페이로드와
 *    하위호환(구 JSON이 그대로 디코드).
 *  - 결과 셀렉터: `diploRespondLetter`가 [DiploLetterResult] 콜랩스 셋에 합류;
 *    `appoint`/`kick`/`changePermission`은 기존 [GeneralBoolResult] boolean-ok 그룹 그대로.
 *
 * 순수 구조 라운드트립(PHP 골든 아님) — 행동 패러티 골든은 각 W1 에이전트의 게이트에서 닫는다.
 */
class IntakeWaveW07WireTest {

    private fun cmdRoundTrip(c: TurnDaemonCommand): TurnDaemonCommand {
        val raw = WireJson.encodeToString(TurnDaemonCommand.serializer(), c)
        return WireJson.decodeFromString(TurnDaemonCommand.serializer(), raw)
    }

    private fun resRoundTrip(r: TurnDaemonCommandResult): TurnDaemonCommandResult {
        val raw = WireJson.encodeToString(TurnDaemonCommandResult.serializer(), r)
        return WireJson.decodeFromString(TurnDaemonCommandResult.serializer(), raw)
    }

    @Test
    fun `diploRespondLetter 커맨드가 라운드트립된다 - 승인과 거부 양쪽`() {
        // 승인 (isAgree=true, reason 빈 문자열 기본)
        val approve = TurnDaemonCommand.DiploRespondLetter(generalId = 10, letterNo = 7, isAgree = true)
        assertEquals(approve, cmdRoundTrip(approve))

        // 거부 + 사유 (PHP: reason은 trim 후 메시지에 ' 이유 : ' 접미)
        val decline = TurnDaemonCommand.DiploRespondLetter(
            generalId = 10, letterNo = 7, isAgree = false, reason = "조건이 맞지 않습니다",
        )
        assertEquals(decline, cmdRoundTrip(decline))

        // isAgree 부재 시 false (PHP Util::getPost('isAgree','bool',false))
        val decoded = WireJson.decodeFromString(
            TurnDaemonCommand.serializer(),
            """{"type":"diploRespondLetter","generalId":10,"letterNo":7}""",
        )
        val respond = assertIs<TurnDaemonCommand.DiploRespondLetter>(decoded)
        assertEquals(false, respond.isAgree)
        assertEquals("", respond.reason)
    }

    @Test
    fun `MakeGeneral 유산 4필드 + 전콘 picture-imgsvr가 라운드트립된다`() {
        val cmd = TurnDaemonCommand.MakeGeneral(
            userId = 3, name = "조운", leadership = 80, strength = 75, intel = 65,
            character = "Random",
            picture = "1234.jpg", imgsvr = 1,
            inheritSpecial = "che_귀병",
            inheritTurntimeZone = 30,
            inheritCity = 15,
            inheritBonusStat = listOf(3, 2, 1),
        )
        assertEquals(cmd, cmdRoundTrip(cmd))
    }

    @Test
    fun `MakeGeneral 구 페이로드(유산 필드 부재)가 null 기본값으로 디코드된다 - 하위호환`() {
        // widen 이전 publisher가 보낸 그대로의 JSON — 유산/imgsvr 필드 없음.
        val decoded = WireJson.decodeFromString(
            TurnDaemonCommand.serializer(),
            """{"type":"makeGeneral","userId":3,"name":"조운","leadership":80,"strength":75,"intel":65}""",
        )
        val make = assertIs<TurnDaemonCommand.MakeGeneral>(decoded)
        assertEquals(null, make.inheritSpecial)
        assertEquals(null, make.inheritTurntimeZone)
        assertEquals(null, make.inheritCity)
        assertEquals(null, make.inheritBonusStat)
        assertEquals(null, make.imgsvr)
        assertEquals(null, make.picture)
    }

    @Test
    fun `diploRespondLetter 결과가 DiploLetterResult 콜랩스 셋으로 라우팅된다`() {
        val ok = DiploLetterResult(type = "diploRespondLetter", ok = true, generalId = 10, letterNo = 7)
        assertEquals(ok, assertIs<DiploLetterResult>(resRoundTrip(ok)))

        val fail = DiploLetterResult(
            type = "diploRespondLetter", ok = false, generalId = 10, letterNo = 7,
            reason = "권한이 부족합니다. 수뇌부가 아닙니다.",
        )
        assertEquals(fail, assertIs<DiploLetterResult>(resRoundTrip(fail)))
    }

    @Test
    fun `appoint-kick-changePermission 결과는 기존 GeneralBoolResult 그룹으로 라우팅된다`() {
        for (type in listOf("appoint", "kick", "changePermission")) {
            val deny = GeneralBoolResult(type = type, ok = false, generalId = 10, reason = "아직 구현되지 않은 명령입니다 (엔진 핸들러 W1 대기)")
            assertEquals(deny, assertIs<GeneralBoolResult>(resRoundTrip(deny)))
        }
    }
}
