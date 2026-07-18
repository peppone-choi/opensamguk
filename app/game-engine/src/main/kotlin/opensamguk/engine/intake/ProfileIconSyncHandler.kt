package opensamguk.engine.intake

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import org.slf4j.LoggerFactory

/**
 * OPENSAM-94 — 프로필 아이콘 typed sync 핸들러.
 *
 * gateway 계정의 canonical 전콘(picture/imgsvr) 변경을 `owner=userId && npc=0`인 기존 general에
 * 반영한다. Join.php:379-385의 게이트를 **엔진에서 재평가**한다:
 *  - `show_img_level` = world config(authoritative, game_env → state.meta). 클라/wire boolean 무시.
 *  - `grade` = wire가 실은 gateway 계정의 authoritative 원값(서버측 read, verdict 아님).
 *  - 게이트 = `show_img_level >= 1 && grade >= 1` (+ 방어적으로 picture 비어있지 않음).
 *
 * 게이트 미통과·매칭 general 부재 → 변이 0(memory/dirty/DB 불변, 성공 종료). 매칭 general → 현재
 * meta picture/image_server와 wire 값을 비교해 **변경이 있을 때만** meta 변이 + [ChangeRecorder]
 * 전용 채널(picture/image_server 전용 컬럼) 기록 → JdbcFlushExecutor가 배치 flush한다. 동일 payload
 * 재적용은 no-op(idempotent). general을 새로 만들거나 Join gate를 우회하지 않는다.
 *
 * 반환은 null — gateway fanout은 fire-and-forget(요청측이 per-general 결과를 대기하지 않는다).
 */
class ProfileIconSyncHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(command: TurnDaemonCommand.ProfileIconSync): TurnDaemonCommandResult? {
        val showImageLevel = intFromMeta(world.getState().meta["show_img_level"]) ?: DEFAULT_SHOW_IMG_LEVEL
        val eligible = showImageLevel >= 1 && command.grade >= 1 && command.picture.isNotBlank()
        if (!eligible) {
            // 게이트 off / show_img_level=0 / grade=0 / 빈 picture — 변이 없음(criterion 4).
            log.debug("profile-icon.sync skipped eligible=false show_img_level={}", showImageLevel)
            return null
        }

        val ownerKey = command.userId.toString()
        var applied = 0
        // owner=userId && npc=0 exact target predicate (eligibility 통과 뒤에만). NPC/타 owner 불변.
        for (general in world.listGenerals()) {
            if (general.userId != ownerKey || general.npcState != 0) continue

            val currentPicture = general.meta["picture"]?.toString()
            val currentImgsvr = intFromMeta(general.meta["image_server"]) ?: 0
            if (currentPicture == command.picture && currentImgsvr == command.imgsvr) {
                continue // 값 동일 — dirty/flush row 없음(criterion 9/10 idempotency).
            }

            val nextMeta = LinkedHashMap(general.meta)
            nextMeta["picture"] = command.picture
            nextMeta["image_server"] = command.imgsvr
            nextMeta["imgsvr"] = command.imgsvr // 로더가 meta에 미러링하는 별칭 — 일관 유지.
            world.applyGeneralDirtyFree(general.copy(meta = nextMeta))
            recorder.recordProfileIconUpdate(
                linkedMapOf(
                    "id" to general.id,
                    "user_id" to general.userId,
                    "picture" to command.picture,
                    "image_server" to command.imgsvr,
                ),
            )
            applied += 1
        }
        log.debug("profile-icon.sync applied count={}", applied)
        return null
    }

    private fun intFromMeta(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private companion object {
        // Join.php / ScenarioStartEventActions와 동일한 기본값(부재 시 3 → 전콘 허용).
        const val DEFAULT_SHOW_IMG_LEVEL = 3
    }
}
