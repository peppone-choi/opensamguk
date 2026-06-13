package opensamguk.infra.seed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * RTK14 divergence 스탯 로더의 **외부 경로(prod 사이드로드)** 동작 단위 테스트.
 * 코에이 IP 데이터는 미커밋(git-ignore)이라 prod 이미지에 없다 → 호스트 파일을 env/property로 주입한다.
 * 글로벌 system-property를 건드리지 않도록 readRaw(ext) 에 경로를 직접 전달한다(lazy table 오염 방지).
 */
class Rtk14StatsTest {

    @Test
    fun `readRaw가 외부 파일시스템 경로에서 원문을 읽는다 (prod 사이드로드)`() {
        val tmp = File.createTempFile("rtk14-sideload", ".json")
        try {
            tmp.writeText("""{"테스트장수":{"politics":11,"charm":22}}""", Charsets.UTF_8)
            val raw = Rtk14Stats.readRaw(tmp.absolutePath)
            assertEquals("""{"테스트장수":{"politics":11,"charm":22}}""", raw)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `존재하지 않는 외부 경로는 throw 없이 클래스패스로 폴백한다`() {
        // 부재 경로 → 예외 없이 클래스패스 리소스(로컬 존재) 또는 null(CI 부재)로 안착.
        val raw = Rtk14Stats.readRaw("/nonexistent/rtk14/does-not-exist.json")
        assertTrue(raw == null || raw.isNotEmpty())
    }

    @Test
    fun `빈 경로는 무시하고 클래스패스로 폴백한다`() {
        val raw = Rtk14Stats.readRaw("")
        assertTrue(raw == null || raw.isNotEmpty())
    }

    @Test
    fun `잘못된 경로가 디렉터리면 throw 없이 폴백한다`() {
        val dir = File(System.getProperty("java.io.tmpdir"))
        // 디렉터리는 isFile=false → 폴백(예외 없음).
        val raw = Rtk14Stats.readRaw(dir.absolutePath)
        assertTrue(raw == null || raw.isNotEmpty())
    }
}
