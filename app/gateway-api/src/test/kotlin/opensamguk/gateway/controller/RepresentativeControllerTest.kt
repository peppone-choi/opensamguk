package opensamguk.gateway.controller

import opensamguk.gateway.dto.RepresentativeResponse
import opensamguk.gateway.dto.RepresentativeState
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.service.RepresentativeService
import opensamguk.infra.entity.UserEntity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** PR 비평 F1 — `{"generalId": null}` (대표 장수 해제)이 MVC 검증을 통과해 서비스의 null 분기에 닿는다. */
class RepresentativeControllerTest {

    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `posting generalId null clears the representative instead of a 400`() {
        val service = mock(RepresentativeService::class.java)
        val user = CustomUserDetails(UserEntity(id = 30, username = "uitest", password = "x", nickname = "유아이", role = "USER"))
        // Kotlin non-null 파라미터에는 Mockito any() 가 null 을 돌려주므로 매처를 등록한 뒤 더미 값을 넘긴다.
        `when`(service.set(ArgumentMatchers.any(CustomUserDetails::class.java) ?: user, ArgumentMatchers.isNull()))
            .thenReturn(RepresentativeResponse(RepresentativeState(null, null, null), emptyList()))
        val mvc = MockMvcBuilders.standaloneSetup(RepresentativeController(service))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(user, null, user.authorities)

        mvc.perform(post("/auth/account/representative").contentType(MediaType.APPLICATION_JSON).content("""{"generalId": null}"""))
            .andExpect(status().isOk)
        verify(service).set(ArgumentMatchers.any(CustomUserDetails::class.java) ?: user, ArgumentMatchers.isNull())

        // 바인딩 자체는 살아 있다(문자열 id 는 400).
        mvc.perform(post("/auth/account/representative").contentType(MediaType.APPLICATION_JSON).content("""{"generalId": "x"}"""))
            .andExpect(status().isBadRequest)
    }
}
