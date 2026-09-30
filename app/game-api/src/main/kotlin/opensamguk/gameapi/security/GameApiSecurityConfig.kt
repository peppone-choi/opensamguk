package opensamguk.gameapi.security

import org.springframework.http.HttpMethod
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
 * Public statics (lobby, const, map and front header) omit private identity when no JWT is verified.
 * Controllers enforce ownership before private data reads; my-* views and reserved orders also
 * require route authentication.
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
                    .requestMatchers(HttpMethod.POST, "/api/command/**").authenticated()
                    // Reserved orders always require a verified account.
                    .requestMatchers("/api/reserved-commands").authenticated()
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
