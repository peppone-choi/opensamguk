package opensamguk.engine.turn

/**
 * Raw inheritance log intent captured by [ChangeRecorder].
 * Year/month are stamped at flush time by [opensamguk.engine.flush.DatabaseHooks]
 * (mirrors [LogEntryDraft] → [opensamguk.infra.persistence.LogRow] finalize).
 *
 * `date`(W0-8, V17 inheritance_log.date): PHP user_record.date(DATETIME NULL)에 대응하는
 * ISO-8601 timestamptz 문자열 — 기록 시점의 실제 시각. null이면 NULL 바인딩(기존 행 패러티;
 * 실제 스탬프는 W1-J inherit 에이전트가 채운다).
 */
data class InheritanceLogDraft(
    val ownerID: Int,
    val text: String,
    val tag: String,
    val date: String? = null,
)
