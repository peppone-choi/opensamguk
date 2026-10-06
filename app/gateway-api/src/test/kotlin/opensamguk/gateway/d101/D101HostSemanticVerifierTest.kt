package opensamguk.gateway.d101

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class D101HostSemanticVerifierTest {
    @Test fun `every fixed upstream validator receives defensive actual original and verified intent`() {
        val f=D101Fixture();val tree=f.intentTree();val wire=f.mapper.writeValueAsBytes(tree)
        val raw=D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith {if(it=="approvalIntent") wire else "synthetic $it".toByteArray()}
        val seen=mutableSetOf<String>()
        val checks=raw.keys.associateWith {id-> D101HostOriginalSemanticCheck {original,intent->
            assertArrayEquals(raw.getValue(id),original);assertEquals(D101Fixture.hash(wire),intent.sha256)
            seen+=id;original.fill(0)
        }}
        D101HostSemanticVerifier(f.codec,D101Fixture.hash(wire),checks).verifyOriginals(D101VerifiedHostOriginals(raw))
        assertEquals(raw.keys,seen)
    }
    @Test fun `missing validator unknown registry or rejected original has no success default`() {
        val f=D101Fixture();val wire=f.mapper.writeValueAsBytes(f.intentTree())
        val raw=D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith {if(it=="approvalIntent") wire else "synthetic".toByteArray()}
        val checks=raw.keys.associateWith {D101HostOriginalSemanticCheck {_,_->}}
        for(invalid in listOf(checks-"selectedSourceReceipt",checks+mapOf("unknown" to D101HostOriginalSemanticCheck {_,_->}),
            checks+mapOf("isolatedSeedTickReceipt" to D101HostOriginalSemanticCheck {_,_->throw D101PurposeAuthorityUnavailable()}))) {
            assertThrows(D101PurposeAuthorityUnavailable::class.java) {
                D101HostSemanticVerifier(f.codec,D101Fixture.hash(wire),invalid).verifyOriginals(D101VerifiedHostOriginals(raw))
            }
        }
    }
}
