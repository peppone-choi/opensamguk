package opensamguk.gameapi.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * F2 Wave 1 — minimal STATELESS Spring Security for game-api.
 *
 * Trust boundary: game-api verifies (does not issue) the gateway `sam_access` JWT. The
 * [JwtVerifyFilter] runs before the username/password filter and sets a verified-userId principal when
 * a valid Bearer token is present.
 *
 * Public (no identity needed): the lobby map preview, the health probe, the const/global-menu reads,
 * and — during the F2 transition — the existing read controllers that still accept `?generalId=`
 * (auction/betting/mailbox/diplomacy/command/sse/front-info). Those keep working unauthenticated so
 * web/game Wave 2 can migrate incrementally; the proxy injects the Bearer where it has one.
 *
 * Identity-required: the possession + my-* endpoints, which resolve the caller's general from the
 * verified principal and have no `?generalId=` fallback.
 *
 * CSRF is disabled (stateless token API, no cookies on this origin).
 */
@Configuration
class GameApiSecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity, jwtVerifyFilter: JwtVerifyFilter): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    // ── identity-required (resolve caller's general from the verified principal) ──
                    .requestMatchers("/api/my-page", "/api/my-generals", "/api/my-cities", "/api/my-boss", "/api/my-nation-detail").authenticated()
                    // Phase 4X-A 가신·부곡 읽기 — 본인/같은 국가만(spec v3 F4). 등록하지 않으면 anyRequest permitAll 로 공개된다.
                    .requestMatchers("/api/my-retinue", "/api/generals/*/retinue").authenticated()
                    // Phase 4X-B 작전 읽기 — 국가 내부 정보(타국 403).
                    .requestMatchers("/api/operations", "/api/operations/*").authenticated()
                    .requestMatchers("/api/general/claim").authenticated()
                    .requestMatchers("/api/generals/claimable").authenticated()
                    .requestMatchers("/api/select-pool", "/api/select-pool/**").authenticated()
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
