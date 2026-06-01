package opensamguk.logic.inheritance

/**
 * KV storage interface for inheritance point persistence.
 *
 * The implementation uses namespace = "inheritance_{userId}" as the storage scope.
 * This is the seam between the pure logic layer and the infrastructure/persistence layer.
 *
 * PHP equivalent: storage 테이블의 namespace = inheritance_{userID} KV 스토리지
 */
interface InheritanceStorage {
    /** Get a single value by key. Returns null if the key does not exist. */
    fun get(userId: String, key: String): Double?

    /** Set a single key-value pair. */
    fun set(userId: String, key: String, value: Double)

    /** Get all key-value pairs for a user. */
    fun getAll(userId: String): Map<String, Double>

    /**
     * Clear all keys for a user EXCEPT those in [keepKeys].
     * Used by applyInheritanceUser to reset storage, keeping only 'previous'.
     */
    fun clear(userId: String, keepKeys: Set<String>)
}
