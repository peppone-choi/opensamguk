package opensamguk.gateway.publication.api

data class ChangeServerVisibilityRequest(val publiclyVisible: Boolean?, val expectedRevision: String)
