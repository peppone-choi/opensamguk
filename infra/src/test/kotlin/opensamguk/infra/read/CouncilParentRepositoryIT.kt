package opensamguk.infra.read

import opensamguk.common.world.WorldId
import opensamguk.infra.entity.BoardPostEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** 제품 @Query와 실제 JPA raw repository를 사용한다. 테스트 SQL로 제품 조회를 대신하지 않는다. */
@Testcontainers(disabledWithoutDocker = true)
class CouncilParentRepositoryIT {
    @Test fun `native 부모 조회는 본문을 읽기 전에 world 소속 secret 제한을 적용한다`() {
        val datasource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val jdbc = JdbcTemplate(datasource)
        jdbc.execute("""CREATE TABLE board_post (
            world_id integer NOT NULL, id integer NOT NULL, nation_id integer NOT NULL,
            is_secret boolean NOT NULL, author_general_id integer NOT NULL, author_name text NOT NULL,
            title text NOT NULL, content_html text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
            updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(world_id,id))""")
        jdbc.update("""INSERT INTO board_post
            (world_id,id,nation_id,is_secret,author_general_id,author_name,title,content_html)
            VALUES (1,40,3,false,101,'본국','글','본국 본문'), (2,40,3,false,101,'다른 world','글','외부 본문'),
                   (1,41,3,true,101,'기밀','글','기밀 본문'), (1,42,4,false,201,'타국','글','타국 본문'),
                   (2,43,3,false,101,'다른 world','글','외부 전용 본문')""")
        val factory = LocalContainerEntityManagerFactoryBean().apply {
            dataSource = datasource
            setPackagesToScan(BoardPostEntity::class.java.packageName)
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
            afterPropertiesSet()
        }
        val entityManager = factory.`object`!!.createEntityManager()
        try {
            val raw = JpaRepositoryFactory(entityManager).getRepository(BoardPostRawRepository::class.java)
            val scoped = WorldScopedBoardPostRepository(raw, WorldId(1))
            assertEquals("본국 본문", scoped.findAccessibleCouncilPost(40, 3, false)?.contentHtml)
            assertNull(scoped.findAccessibleCouncilPost(41, 3, false))
            assertEquals("기밀 본문", scoped.findAccessibleCouncilPost(41, 3, true)?.contentHtml)
            assertNull(scoped.findAccessibleCouncilPost(42, 3, true))
            assertNull(scoped.findAccessibleCouncilPost(43, 3, true))
            assertThrows(IllegalArgumentException::class.java) { scoped.findAccessibleCouncilPost(40, 0, true) }
            assertThrows(IllegalArgumentException::class.java) { scoped.findAccessibleCouncilPost(0, 3, true) }
            // 구형 구현체가 새 권한 경로를 추측하여 열지 않는다.
            val oldFake = object : BoardPostRepository {
                override fun findByIdAndNationId(id: Int, nationId: Int) = scoped.findByIdAndNationId(id, nationId)
            }
            assertNull(oldFake.findAccessibleCouncilPost(41, 3, true))
        } finally {
            entityManager.close(); factory.destroy()
        }
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
