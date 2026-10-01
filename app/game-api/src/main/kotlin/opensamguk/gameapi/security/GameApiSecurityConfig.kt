package opensamguk.gameapi.security

import org.springframework.http.HttpMethod
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * F2 Wave 1 — minimal STATELESS Spring Security for game-api.
 *
 * Trust boundary: game-api verifies (does not issue) the gateway `sam_access` JWT. The
 * [JwtVerifyFilter] runs before the username/password filter and sets a verified-userId principal when
 * a valid Bearer token is present.
 *
 * 공개 정적 정보는 인증이 없으면 개인 정보를 제외한다. 개인 조회는 실제 소유를 먼저 검사한다.
 * 예약 입력·서신·my-* 조회는 경로 인증도 요구하며 generalId로 인증을 대체하지 않는다.
 *
 * Method-limited public: exact province-name paths allow GET and deny every other method.
 *
 * CSRF is disabled (stateless token API, no cookies on this origin).
 */
@Configuration
class GameApiSecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity, jwtVerifyFilter: JwtVerifyFilter): SecurityFilterChain {
        val publicNamePaths = arrayOf("/api/map/provinces/names", "/api/map/provinces/names/v1")
        val publicNames = OrRequestMatcher(publicNamePaths.map { AntPathRequestMatcher(it) })
        // 인증해도 허용되지 않는 비GET은 로그인 요청과 구분해 기존 403을 유지한다.
        val deniedPublicNames = RequestMatcher { request ->
            request.method != HttpMethod.GET.name() && publicNames.matches(request)
        }
        val entryPoint = DelegatingAuthenticationEntryPoint(
            linkedMapOf<RequestMatcher, AuthenticationEntryPoint>(deniedPublicNames to Http403ForbiddenEntryPoint())
        ).apply { setDefaultEntryPoint(AuthRequiredAuthenticationEntryPoint()) }
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .exceptionHandling { it.authenticationEntryPoint(entryPoint) }
            .authorizeHttpRequests { auth ->
                auth
                    // K4-21: 공개 이름표는 이 두 경로의 GET만 허용한다.
                    .requestMatchers(HttpMethod.GET, *publicNamePaths).permitAll()
                    .requestMatchers(*publicNamePaths).denyAll()
                    .requestMatchers(HttpMethod.POST, "/api/command/**").authenticated()
                    // 예약 입력은 모든 HTTP 메서드에서 인증된 계정만 받는다.
                    .requestMatchers("/api/reserved-commands").authenticated()
                    // Mailbox IDs and single-message IDs must never make private correspondence public.
                    .requestMatchers("/api/mailbox/**", "/api/messages/**").authenticated()
                    // 전용 회의실과 모든 게시/댓글/열람 하위 경로는 principal 인증이 선행한다.
                    .requestMatchers("/api/council", "/api/council/**").authenticated()
                    // ── identity-required (resolve caller's general from the verified principal) ──
                    .requestMatchers("/api/my-page", "/api/my-generals", "/api/my-cities", "/api/my-nation-detail").authenticated()
                    .requestMatchers("/api/events").authenticated()
                    // Phase 4X-B 작전 읽기 — 국가 내부 정보(타국 403).
                    .requestMatchers("/api/operations", "/api/operations/*").authenticated()
                    // Phase 4X-C 출병 계획·리플레이 읽기 — 본인/공격국·수비국만(타국 403).
                    .requestMatchers("/api/my-battle-plans", "/api/battles/replays", "/api/battles/replays/*").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/battles/*/*/join-ticket").authenticated()
                    .requestMatchers("/api/v2/commands/**").authenticated()
                    .requestMatchers("/api/v2/garrison-recruit", "/api/v2/city-transport").authenticated()
                    .requestMatchers("/api/command/v2GarrisonRecruit", "/api/command/v2CityTransport").authenticated()
                    // ── everything else stays public (transition: ?generalId= reads, health, const, menu, map) ──
                    .anyRequest().permitAll()
            }
            .addFilterBefore(jwtVerifyFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
