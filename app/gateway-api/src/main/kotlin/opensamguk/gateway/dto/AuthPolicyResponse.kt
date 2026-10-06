package opensamguk.gateway.dto

import com.fasterxml.jackson.annotation.JsonProperty

/** Public admission flags; this response contains no member or administrator data. */
data class AuthPolicyResponse(
    @get:JsonProperty("allow_join")
    val allowJoin: Boolean,
    @get:JsonProperty("allow_login")
    val allowLogin: Boolean,
)
