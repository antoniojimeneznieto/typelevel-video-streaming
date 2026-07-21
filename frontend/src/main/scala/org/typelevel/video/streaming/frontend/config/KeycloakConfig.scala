package org.typelevel.video.streaming.frontend.config

final case class KeycloakConfig(
    baseUrl: String,
    realm: String,
    clientId: String,
    redirectUri: String
):
  val realmUrl: String = s"$baseUrl/realms/$realm"
  val authorizationEndpoint: String = s"$realmUrl/protocol/openid-connect/auth"
  val tokenEndpoint: String = s"$realmUrl/protocol/openid-connect/token"
  val logoutEndpoint: String = s"$realmUrl/protocol/openid-connect/logout"
