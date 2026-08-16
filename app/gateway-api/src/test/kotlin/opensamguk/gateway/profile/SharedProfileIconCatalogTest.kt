package opensamguk.gateway.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat

class SharedProfileIconCatalogTest {
    @Test
    fun `resolves only synthetic licensed manifest entries by logical id`() {
        val entry = sharedEntry()
        val catalog = SharedProfileIconCatalog(listOf(entry))

        assertTrue(catalog.isAllowedSharedIconId("wiki:liu-bei"))
        assertEquals(entry, catalog.resolveSharedIcon("wiki:liu-bei"))
        assertFalse(catalog.isAllowedSharedIconId("wiki:unknown"))
        assertNull(catalog.resolveSharedIcon("../../etc/passwd"))
        assertNull(catalog.resolveSharedIcon("0123abcd.png"))
    }

    @Test
    fun `rejects unsafe manifest filenames and uploaded-name collisions`() {
        assertThrows(IllegalArgumentException::class.java) {
            SharedProfileIconCatalog(listOf(sharedEntry(canonicalFilename = "../liu-bei.png")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SharedProfileIconCatalog(listOf(sharedEntry(canonicalFilename = "0123abcd.png")))
        }
    }

    @Test
    fun `production manifest preserves the established trusted shared icon`() {
        val catalog = SharedProfileIconCatalog.fromClasspath("profile-icons/shared-manifest.json")

        assertEquals(2, catalog.entries().size)
        val existing = requireNotNull(catalog.resolveSharedIcon("1001"))
        assertEquals("1001.jpg", existing.canonicalFilename)
        assertEquals("4d27da9a19571236183fd9ec40f5cd9550432ef574000ab78519692c1176d3b5", existing.sha256)
        assertEquals(SharedProfileIconScope.EXISTING_SHARED_CDN, existing.scope)
        assertEquals("icons/1001.jpg", existing.sourcePath)
        // 2026-08-17: opensamguk-images 히스토리를 재작성해 옵션 IP 초상 2,335장을 제거하면서
        // 태그 v2026.05.21 이 새 커밋으로 옮겨졌다. 파일 바이트와 경로는 불변이라 위 sha256 은
        // 그대로고, 리비전 핀만 갱신된다.
        assertTrue(requireNotNull(existing.deliveryUrl).contains("@05842c61132fd5a71268fd9babd80ba74e27be62/"))
        assertFalse(existing.operationalFallback)
        assertFalse(existing.clearedFallback)

        val fallback = requireNotNull(catalog.resolveSharedIcon("default"))
        assertEquals("default.jpg", fallback.canonicalFilename)
        assertEquals("f53c76d05281db09a9d859e14c6bf3f6ecbc8001b70330a62d6041d4e168141b", fallback.sha256)
        assertEquals(64, fallback.width)
        assertEquals(64, fallback.height)
        assertTrue(fallback.operationalFallback)
        assertFalse(fallback.clearedFallback)
        assertEquals(fallback, catalog.operationalFallback())
        assertFalse(catalog.hasClearedEligibleFallback())
        assertTrue(catalog.hasReleaseBlockingRights())
        assertEquals(0, catalog.entries().count { it.scope == SharedProfileIconScope.BUNDLED_CLEARED })
        assertFalse(catalog.isAllowedSharedIconId("0123abcd.jpg"))
        assertFalse(catalog.isAllowedSharedIconId("../1001"))
    }

    @Test
    fun `manifest requires explicit provenance license and redistribution status`() {
        assertInvalidManifest(
            "profile-icons/invalid-missing-rights-manifest.json",
            "Shared profile icon manifest field is missing: provenance_status",
        )
    }

    @Test
    fun `manifest requires exactly one fallback`() {
        val reason = "Exactly one operational shared profile fallback is required"
        assertInvalidManifest("profile-icons/invalid-missing-fallback-manifest.json", reason)
        assertInvalidManifest("profile-icons/invalid-duplicate-fallback-manifest.json", reason)
    }

    @Test
    fun `manifest rejects duplicate ids and duplicate content hashes`() {
        assertInvalidManifest("profile-icons/invalid-duplicate-id-manifest.json", "Duplicate shared profile icon_id")
        assertInvalidManifest("profile-icons/invalid-duplicate-hash-manifest.json", "Duplicate shared profile sha256")
    }

    @Test
    fun `manifest rejects unsupported license status and media type`() {
        assertInvalidManifest(
            "profile-icons/invalid-license-manifest.json",
            "Unsupported shared profile license_status",
        )
        assertInvalidManifest(
            "profile-icons/invalid-media-manifest.json",
            "Unsupported shared profile icon media_type",
        )
    }

    @Test
    fun `bundled catalog rejects missing assets and hash mismatches`() {
        assertInvalidManifest(
            "profile-icons/invalid-missing-manifest.json",
            "Bundled shared profile icon is missing",
        )
        assertInvalidManifest(
            "profile-icons/invalid-hash-manifest.json",
            "Bundled shared profile icon hash mismatch",
        )
    }

    @Test
    fun `bundled catalog hashes and fully decodes the declared raster and dimensions`() {
        val resourcePath = "profile-icons/bundled/synthetic.png"
        val bytes = TestImageFixtures.image("png")
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val loader: (String) -> ByteArray? = { path -> bytes.takeIf { path == resourcePath } }

        val catalog = SharedProfileIconCatalog.fromManifestBytes(
            bundledManifest(sha256, resourcePath, dimension = 80),
            loader,
        )

        assertTrue(catalog.hasClearedEligibleFallback())
        assertFalse(catalog.hasReleaseBlockingRights())
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SharedProfileIconCatalog.fromManifestBytes(
                bundledManifest(sha256, resourcePath, dimension = 81),
                loader,
            )
        }
        assertEquals("Bundled shared profile icon dimensions mismatch", failure.message)
    }

    @Test
    fun `manifest rejects orphan bundled inventory`() {
        assertInvalidManifest(
            "profile-icons/invalid-orphan-manifest.json",
            "Shared profile bundled resource inventory does not match bundled entries",
        )
    }

    @Test
    fun `manifest rejects expected count drift and nondeterministic entry order`() {
        assertInvalidManifest(
            "profile-icons/invalid-expected-count-manifest.json",
            "Shared profile icon expected_count does not match entries",
        )
        assertInvalidManifest(
            "profile-icons/invalid-nondeterministic-manifest.json",
            "Shared profile icon entries are not deterministically ordered",
        )
    }

    private fun assertInvalidManifest(resourcePath: String, expectedReason: String) {
        assertNotNull(SharedProfileIconCatalog::class.java.classLoader.getResource(resourcePath), resourcePath)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SharedProfileIconCatalog.fromClasspath(resourcePath)
        }
        assertEquals(expectedReason, failure.message)
    }

    private fun bundledManifest(sha256: String, resourcePath: String, dimension: Int): ByteArray = """
        {
          "version": 1,
          "expected_count": 1,
          "expected_cleared_fallback_count": 1,
          "bundled_resource_inventory": ["$resourcePath"],
          "existing_shared_cdn": [],
          "bundled_cleared": [
            {
              "icon_id": "synthetic",
              "canonical_id": "synthetic",
              "canonical_filename": "synthetic.png",
              "portrait_asset_id": "pa_$sha256",
              "sha256": "$sha256",
              "media_type": "image/png",
              "width": $dimension,
              "height": $dimension,
              "operational_fallback": true,
              "cleared_fallback": true,
              "source_repository": "https://github.com/example/cleared-icons",
              "source_revision": "1111111111111111111111111111111111111111",
              "source_path": "source/synthetic.png",
              "resource_path": "$resourcePath",
              "provenance_status": "pinned-immutable-byte-evidence",
              "license_status": "cleared",
              "redistribution_status": "cleared-for-redistribution"
            }
          ]
        }
    """.trimIndent().toByteArray()

    private fun sharedEntry(canonicalFilename: String = "wiki-liu-bei.png") = SharedProfileIconEntry(
        iconId = "wiki:liu-bei",
        canonicalId = "liu-bei",
        canonicalFilename = canonicalFilename,
        portraitAssetId = "pa_${"a".repeat(64)}",
        sha256 = "a".repeat(64),
        mediaType = "image/png",
        width = 80,
        height = 80,
        operationalFallback = true,
        clearedFallback = false,
        scope = SharedProfileIconScope.EXISTING_SHARED_CDN,
        sourceRepository = "https://github.com/example/test-icons",
        sourceRevision = "1".repeat(40),
        sourcePath = "icons/$canonicalFilename",
        deliveryUrl = "https://cdn.jsdelivr.net/gh/example/test-icons@${"1".repeat(40)}/icons/$canonicalFilename",
        resourcePath = null,
        redistributionStatus = "unknown",
    )
}
