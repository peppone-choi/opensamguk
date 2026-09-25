package opensamguk.gameapi.web

import opensamguk.gameapi.read.HwihaStratagemHandReader
import opensamguk.gameapi.read.StratagemHandForbidden
import org.springframework.http.ResponseEntity
import org.springframework.http.CacheControl
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
class HwihaStratagemHandController(private val reader:HwihaStratagemHandReader) {
    @GetMapping("/api/commands/stratagem-hand")
    fun hand(@AuthenticationPrincipal userId:Long?,@RequestParam generalId:Int):ResponseEntity<Any> {
        if(userId==null || userId<=0 || userId>Int.MAX_VALUE)return ResponseEntity.status(401).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reader.read(generalId,userId)) }
        catch (_:StratagemHandForbidden) { ResponseEntity.status(403).build() }
    }
}
