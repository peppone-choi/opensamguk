package opensamguk.infra.read

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant

/**
 * `vote_poll` / `vote` 테이블용 JDBC read seam (F4 Wave 투표).
 *
 * 데몬 [opensamguk.engine.intake.VoteHandler]의 VoteCast 게이트는 PHP `API/Vote/Vote.php`의 cast 가드를
 * 충실히 재현하려면 설문 상태를 read 해야 한다 (`$voteStor->getValue("vote_{voteID}")`). PHP는 설문을
 * KVStorage(`vote` 네임스페이스, `vote_{voteID}` 키)에 저장하지만 opensamguk 스키마는 vote_poll/vote
 * DB 테이블로 매핑한다. PHP의 게임-레벨 `voteID`(`game_env.lastVote` 카운터 = `lastVote+1`)는 opensamguk
 * 에서 vote_poll.id SERIAL과 동일한 단조 시퀀스로 할당되므로(NewVote가 lastVote 순서대로 INSERT) —
 * `voteID` == `vote_poll.id`이고, `vote.vote_id` 역시 같은 `voteID`를 참조한다(V1 baseline:395 FK).
 *
 * Vote.php cast 가드가 읽는 필드만 담는다:
 *  - 설문 존재          : vote_poll(id=voteID) 행 존재 (`!$rawVoteInfo` → '설문조사가 없습니다.').
 *  - 종료 여부          : end_at < now 또는 closed_at 설정 (`$voteInfo->endDate && < now` → '…종료되었습니다.').
 *  - 선택 항목 수 상한  : multiple_options (`>=1 && count(selection) > multipleOptions` → '…너무 많습니다.').
 *  - 옵션 인덱스 범위   : jsonb options 배열 길이 (`$sel >= $optionsCnt` → '선택한 항목이 없습니다.').
 *  - opener            : opener_name (PHP VoteInfo->opener, 표시·재현 부수정보).
 *  - 이미 투표함        : vote UNIQUE(vote_id,general_id) 행 존재 (`affectedRows==0` → '이미 …완료하였습니다.').
 *
 * READ 전용 — write 경로(vote_poll/vote/vote_comment INSERT·UPDATE)는
 * [opensamguk.infra.persistence.JdbcFlushExecutor] step-8e를 거치며, 절대 여기서 쓰지 않는다
 * (one-daemon-write 규칙). board/auction read-repo와 동일하게 핸들러에 주입된다.
 */
class VotePollRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {

    /**
     * 게임-레벨 voteID(=vote_poll.id)로 설문 상태를 read 하고, 해당 장수의 투표 완료 여부를 함께 확인한다.
     *
     * `expired`는 PHP의 `$voteInfo->endDate && $voteInfo->endDate < new DateTimeImmutable()`를 충실히
     * 재현한다 — end_at이 설정돼 있고 [now]보다 과거이면 종료. opensamguk은 명시적 마감(closeOldVote)을
     * `closed_at`에 기록하므로, closed_at IS NOT NULL도 종료로 본다(둘 중 하나라도 만족하면 expired).
     * `hasEndDate`는 closeOldVote no-op 판정용 (end_at 또는 closed_at 중 하나라도 있으면 true).
     *
     * @param voteId    게임-레벨 voteID (== vote_poll.id).
     * @param generalId 이미-투표 확인 대상 장수 id (vote.general_id).
     * @param now       만료 비교 기준 시각 (PHP `new DateTimeImmutable()` = 데몬 현재 시각).
     * @return 설문 상태 DTO, 또는 설문이 없으면 null ('설문조사가 없습니다.').
     */
    fun findPollState(voteId: Int, generalId: Int, now: Instant): VotePollReadRow? {
        val rows = jdbc.query(
            """
            SELECT id,
                   multiple_options,
                   jsonb_array_length(options) AS options_count,
                   end_at,
                   closed_at,
                   opener_name,
                   (end_at IS NOT NULL AND end_at < :now) AS end_expired,
                   (closed_at IS NOT NULL)                AS has_closed,
                   EXISTS (
                       SELECT 1 FROM vote
                        WHERE vote.vote_id = vote_poll.id
                          AND vote.general_id = :general_id
                   ) AS already_voted
              FROM vote_poll
             WHERE id = :vote_id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("vote_id", voteId)
                .addValue("general_id", generalId)
                .addValue("now", java.sql.Timestamp.from(now)),
        ) { rs, _ ->
            val endExpired = rs.getBoolean("end_expired")
            val hasClosed = rs.getBoolean("has_closed")
            val endAt: Instant? = rs.getTimestamp("end_at")?.toInstant()
            VotePollReadRow(
                id = rs.getInt("id"),
                multipleOptions = rs.getInt("multiple_options"),
                optionsCount = rs.getInt("options_count"),
                // PHP: endDate && endDate < now → 종료. opensamguk closed_at(명시 마감)도 종료로 본다.
                expired = endExpired || hasClosed,
                // closeOldVote no-op 판정: end_at 또는 closed_at 중 하나라도 있으면 이미 마감 정보 존재.
                hasEndDate = endAt != null || hasClosed,
                opener = rs.getString("opener_name"),
                alreadyVoted = rs.getBoolean("already_voted"),
            )
        }
        return rows.firstOrNull()
    }
}

/**
 * `vote_poll` 한 행의 cast-가드 read 서브셋 (PHP `VoteInfo`의 데몬-게이트 부분집합). 엔진의
 * `VotePollState`로 매핑돼 [opensamguk.engine.intake.VoteHandler]의 VoteCast/closeOldVote 게이트를
 * 충족시킨다. infra-local DTO — 엔진 의존 사이클을 피하기 위해 엔진 `VotePollState`와 독립적으로 미러링.
 */
data class VotePollReadRow(
    /** vote_poll.id (== 게임-레벨 voteID). */
    val id: Int,
    /** vote_poll.multiple_options (선택 항목 수 상한; 0이면 무제한). */
    val multipleOptions: Int,
    /** count(vote_poll.options) — jsonb 배열 길이 (옵션 인덱스 범위 가드). */
    val optionsCount: Int,
    /** end_at < now 또는 closed_at 설정 (이미 종료). */
    val expired: Boolean,
    /** end_at 또는 closed_at 존재 (closeOldVote no-op 판정). */
    val hasEndDate: Boolean,
    /** vote_poll.opener_name (PHP VoteInfo->opener). */
    val opener: String,
    /** 이 장수가 이미 투표함 (vote UNIQUE(vote_id,general_id) 존재) — 보상/추첨 중복 방지. */
    val alreadyVoted: Boolean,
)
