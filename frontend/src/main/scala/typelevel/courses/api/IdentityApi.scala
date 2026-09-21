package typelevel.courses.api

import cats.effect.IO
import org.http4s.client.Client
import org.http4s.Uri
import org.typelevel.video.streaming.backend.identity.api.{
  IdentityService,
  LoginInput,
  LoginResponse,
  RegisterInput,
  UserResponse,
}
import smithy4s.http4s.SimpleRestJsonBuilder

final class IdentityApi(baseUri: Uri, client: Client[IO]):
  private val smithy = SmithyClient(
    client,
    transport => SimpleRestJsonBuilder(IdentityService).client(transport).uri(baseUri).resource,
  )

  def register(request: RegisterInput): IO[UserResponse] =
    smithy.call()(_.register(request.email, request.password, request.displayName))

  def login(request: LoginInput): IO[LoginResponse] =
    smithy.call()(_.login(request.email, request.password))

  def currentUser(accessToken: String): IO[UserResponse] =
    smithy.call(Some(accessToken))(_.getCurrentUser())
