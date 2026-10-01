package opensamguk.gameapi.read

/** Uses the same account ownership predicate as the domestic engine projection. */
internal fun GeneralReadEntity.hasHumanController(): Boolean = (userId?.toLongOrNull() ?: 0L) > 0L
