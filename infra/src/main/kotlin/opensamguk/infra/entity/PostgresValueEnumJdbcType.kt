package opensamguk.infra.entity

import org.hibernate.type.descriptor.WrapperOptions
import org.hibernate.type.descriptor.ValueBinder
import org.hibernate.type.descriptor.ValueExtractor
import org.hibernate.type.descriptor.java.JavaType
import org.hibernate.type.descriptor.jdbc.BasicBinder
import org.hibernate.type.descriptor.jdbc.BasicExtractor
import org.hibernate.type.descriptor.jdbc.JdbcType
import java.sql.CallableStatement
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * PHP `.value` 라벨을 쓰는 Postgres ENUM 컬럼용 JdbcType.
 *
 * 이 저장소의 PG ENUM 라벨은 PHP 문자열 백킹 enum 의 `.value` 다 — `message_type` 은
 * `private`/`public`/`national`/`diplomacy`, `ng_auction_type` 은 `buyRice` 등 **소문자·캐멀**이며
 * Kotlin enum 상수명(`PRIVATE`)과 다르다. 그래서 Hibernate 내장 타입을 쓸 수 없다.
 *
 *  - `@Enumerated(STRING)` / `SqlTypes.NAMED_ENUM`(`PostgreSQLEnumJdbcType`) 은 `.name()`(대문자)을
 *    보낸다 — 라벨 불일치.
 *  - `SqlTypes.OTHER`(`ObjectJdbcType`) 는 String 을 `[B`(byte[]) 로 unwrap 하려다 죽는다.
 *  - varchar 로 보내면 `operator does not exist: message_type = character varying`(42883).
 *
 * 그래서 값 변환은 `AttributeConverter`(`.value`)에 맡기고, 여기서는 그 **문자열을 `Types.OTHER`
 * 로** 보내 PostgreSQL 이 대상 ENUM 으로 추론하게만 한다. 쓰기 경로(`JdbcFlushExecutor`)의
 * `CAST(:type AS message_type)` 와 대칭이다.
 */
class PostgresValueEnumJdbcType : JdbcType {
    override fun getJdbcTypeCode(): Int = Types.OTHER

    override fun getFriendlyName(): String = "PG_VALUE_ENUM"

    override fun <X> getBinder(javaType: JavaType<X>): ValueBinder<X> =
        object : BasicBinder<X>(javaType, this) {
            override fun doBindNull(st: PreparedStatement, index: Int, options: WrapperOptions) {
                st.setNull(index, Types.OTHER)
            }

            override fun doBindNull(st: CallableStatement, name: String, options: WrapperOptions) {
                st.setNull(name, Types.OTHER)
            }

            override fun doBind(st: PreparedStatement, value: X, index: Int, options: WrapperOptions) {
                st.setObject(index, javaType.unwrap(value, String::class.java, options), Types.OTHER)
            }

            override fun doBind(st: CallableStatement, value: X, name: String, options: WrapperOptions) {
                st.setObject(name, javaType.unwrap(value, String::class.java, options), Types.OTHER)
            }
        }

    override fun <X> getExtractor(javaType: JavaType<X>): ValueExtractor<X> =
        object : BasicExtractor<X>(javaType, this) {
            override fun doExtract(rs: ResultSet, paramIndex: Int, options: WrapperOptions): X =
                javaType.wrap(rs.getString(paramIndex), options)

            override fun doExtract(statement: CallableStatement, index: Int, options: WrapperOptions): X =
                javaType.wrap(statement.getString(index), options)

            override fun doExtract(statement: CallableStatement, name: String, options: WrapperOptions): X =
                javaType.wrap(statement.getString(name), options)
        }
}
