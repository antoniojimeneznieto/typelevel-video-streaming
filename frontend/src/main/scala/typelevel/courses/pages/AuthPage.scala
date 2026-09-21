package typelevel.courses.pages

import calico.html.io.{*, given}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import fs2.dom.HtmlElement
import org.http4s.Uri
import org.typelevel.video.streaming.backend.identity.api.{
  AuthenticationErrorCode,
  ConflictError,
  ConflictErrorCode,
  InvalidCredentialsError,
}
import org.typelevel.video.streaming.backend.identity.domain.{
  DisplayName,
  Email,
  NewPassword,
  Password,
}
import smithy4s.http.RawErrorResponse
import typelevel.courses.AppContext
import typelevel.courses.components.Brand
import typelevel.courses.routing.AppRoute
import typelevel.courses.ui.{FormEvents, Icon, Icons}

object AuthPage:
  enum Mode:
    case Login, Register

  def apply(
      ctx: AppContext,
      mode: Mode,
      location: Signal[IO, Uri],
  ): Resource[IO, HtmlElement[IO]] =
    val isRegister           = mode == Mode.Register
    val requestedDestination = location.map { current =>
      current.query.params
        .get("from")
        .filter(AppRoute.isSafeReturnPath)
        .getOrElse("/browse")
    }
    val alternateUri = requestedDestination.map { destination =>
      val base = if isRegister then AppRoute.Login().uri else AppRoute.Register.uri
      base.withQueryParam("from", destination)
    }

    for
      name         <- SignallingRef[IO].of("").toResource
      email        <- SignallingRef[IO].of("").toResource
      password     <- SignallingRef[IO].of("").toResource
      showPassword <- SignallingRef[IO].of(false).toResource
      error        <- SignallingRef[IO].of("").toResource
      notice       <- SignallingRef[IO].of("").toResource
      submitting   <- SignallingRef[IO].of(false).toResource
      page         <- mainTag(
                cls := "auth-page",
                sectionTag(
                  cls := "auth-brand-panel",
                  div(
                    cls := "auth-brand-panel__header",
                    Brand(ctx, light = true),
                    a.withSelf { self =>
                      (
                        cls := "auth-back-link",
                        href := ctx.navigator.href(AppRoute.Landing),
                        ctx.navigator.intercept(self, AppRoute.Landing),
                        Icons(Icon.ArrowLeft),
                        " Back home",
                      )
                    },
                  ),
                  div(
                    cls := "auth-brand-panel__content",
                    p(cls := "eyebrow", "From the Typelevel community"),
                    h1("Learn from the people", br(()), span("building the ecosystem.")),
                    p(
                      "Explore talks and videos about effects, streaming, libraries, and the ideas behind functional Scala.",
                    ),
                    ul(
                      authBenefit(Icon.CirclePlay, "Discover talks from across the community"),
                      authBenefit(Icon.Bookmark, "Save videos you want to revisit"),
                      authBenefit(Icon.Clock, "Continue from where you stopped"),
                    ),
                  ),
                  img(
                    cls := "auth-brand-panel__art",
                    src := "/learning-network.webp",
                    alt := "",
                    aria.hidden := true,
                  ),
                  div(
                    cls := "auth-brand-panel__footer",
                    span("typelevel learning center"),
                  ),
                ),
                sectionTag(
                  cls := "auth-form-panel",
                  div(
                    cls := "auth-mobile-brand",
                    Brand(ctx),
                    a.withSelf { self =>
                      (
                        cls := "auth-mobile-back",
                        href := ctx.navigator.href(AppRoute.Landing),
                        ctx.navigator.intercept(self, AppRoute.Landing),
                        aria.label := "Back home",
                        Icons(Icon.ArrowLeft),
                      )
                    },
                  ),
                  div(
                    cls := "auth-form-wrap",
                    div(
                      cls := "auth-form-heading",
                      p(
                        cls := "eyebrow",
                        if isRegister then "Create your account" else "Welcome back",
                      ),
                      h2(if isRegister then "Start learning today." else "Continue your path."),
                      p(
                        if isRegister then "Free to explore. Your progress stays with you."
                        else "Enter your details to pick up where you left off.",
                      ),
                    ),
                    form.withSelf { self =>
                      (
                        FormEvents.preventNativeSubmit(self),
                        cls := "auth-form",
                        noValidate := true,
                        onSubmit(
                          submit(
                            ctx,
                            isRegister,
                            requestedDestination,
                            name,
                            email,
                            password,
                            error,
                            notice,
                            submitting,
                          ),
                        ),
                        aria.busy <-- submitting,
                        Option.when(isRegister)(
                          textField(
                            labelText       = "Name",
                            inputType       = "text",
                            inputState      = name,
                            placeholderText = "Ada Lovelace",
                            autocomplete    = "name",
                            error           = error,
                            submitting      = submitting,
                          ),
                        ),
                        textField(
                          labelText       = "Email address",
                          inputType       = "email",
                          inputState      = email,
                          placeholderText = "you@example.com",
                          autocomplete    = "email",
                          error           = error,
                          submitting      = submitting,
                        ),
                        label(
                          cls := "field",
                          span(
                            cls := "field__label-row",
                            "Password",
                            Option.when(!isRegister)(
                              button(
                                typ := "button",
                                disabled <-- submitting,
                                onClick(
                                  notice.set(
                                    "Password reset is not available yet.",
                                  ) *> error.set(""),
                                ),
                                "Forgot password?",
                              ),
                            ),
                          ),
                          span(
                            cls := "password-field",
                            input.withSelf { self =>
                              (
                                typ <-- showPassword.map(if _ then "text" else "password"),
                                value <-- password,
                                onInput(self.value.get.flatMap(password.set)),
                                placeholder :=
                                  (if isRegister then "At least 12 characters"
                                   else "Your password"),
                                autoComplete :=
                                  (if isRegister then "new-password" else "current-password"),
                                disabled <-- submitting,
                                aria.invalid <-- error.map(message =>
                                  if message.nonEmpty then "true" else "false",
                                ),
                                aria.describedBy <-- error.map(message =>
                                  Option.when(message.nonEmpty)("auth-error"),
                                ),
                              )
                            },
                            button(
                              typ := "button",
                              disabled <-- submitting,
                              onClick(showPassword.update(!_)),
                              aria.label <-- showPassword.map(if _ then "Hide password"
                              else "Show password"),
                              showPassword.map { shown =>
                                Icons(if shown then Icon.EyeOff else Icon.Eye)
                              },
                            ),
                          ),
                        ),
                        Option.when(isRegister)(
                          password.map { value =>
                            Option.when(value.nonEmpty)(passwordStrength(value))
                          },
                        ),
                        error.map { message =>
                          Option.when(message.nonEmpty)(
                            p(
                              idAttr := "auth-error",
                              cls := "form-message form-message--error",
                              role := List("alert"),
                              message,
                            ),
                          )
                        },
                        notice.map { message =>
                          Option.when(message.nonEmpty)(
                            p(cls := "form-message", role := List("status"), message),
                          )
                        },
                        button(
                          cls := "button button--primary button--large auth-submit",
                          typ := "submit",
                          disabled <-- submitting,
                          submitting.map { active =>
                            if active then if isRegister then "Creating account…" else "Logging in…"
                            else if isRegister then "Create account"
                            else "Log in"
                          },
                          submitting.map(active => Option.unless(active)(Icons(Icon.ArrowRight))),
                        ),
                      )
                    },
                    p(
                      cls := "auth-switch",
                      if isRegister then "Already learning with us? "
                      else "New to the Learning Center? ",
                      a.withSelf { self =>
                        (
                          href <-- alternateUri.map(value => ctx.navigator.href(value)),
                          ctx.navigator.intercept(self, alternateUri.get),
                          if isRegister then "Log in" else "Create an account",
                        )
                      },
                    ),
                  ),
                ),
              )
    yield page

  private def submit(
      ctx: AppContext,
      isRegister: Boolean,
      requestedDestination: Signal[IO, String],
      name: SignallingRef[IO, String],
      email: SignallingRef[IO, String],
      password: SignallingRef[IO, String],
      error: SignallingRef[IO, String],
      notice: SignallingRef[IO, String],
      submitting: SignallingRef[IO, Boolean],
  ): IO[Unit] =
    submitting.get.ifM(
      IO.unit,
      error.set("") *> notice.set("") *> (for
        currentName        <- name.get.map(_.trim)
        currentEmail       <- email.get.map(_.trim)
        currentPassword    <- password.get
        currentDestination <- requestedDestination.get
        _                  <- validate(isRegister, currentName, currentEmail, currentPassword) match
               case Some(message) => error.set(message)
               case None =>
                 val authenticate =
                   if isRegister then ctx.store.register(currentName, currentEmail, currentPassword)
                   else ctx.store.signIn(currentEmail, currentPassword)
                 submitting.set(true) *>
                   authenticate.attempt
                     .flatMap {
                       case Right(_) => ctx.navigator.replace(currentDestination)
                       case Left(cause) => error.set(authErrorMessage(cause))
                     }
                     .guarantee(submitting.set(false))
      yield ()),
    )

  private[pages] def validate(
      isRegister: Boolean,
      name: String,
      email: String,
      password: String,
  ): Option[String] =
    if isRegister && DisplayName(name.trim).isLeft then
      Some(
        if name.trim.isEmpty then "Please enter your name."
        else "Your name must be 100 characters or fewer and contain non-whitespace text.",
      )
    else if Email(email).isLeft then Some("Enter a valid email address.")
    else if isRegister && NewPassword(password).isLeft then
      Some("Your password must contain between 12 and 128 characters.")
    else if !isRegister && Password(password).isLeft then
      Some("Your password must contain between 1 and 128 characters.")
    else None

  private[pages] def authErrorMessage(cause: Throwable): String =
    cause match
      case ConflictError(ConflictErrorCode.EMAIL_ALREADY_EXISTS) =>
        "An account already exists for that email address."
      case InvalidCredentialsError(AuthenticationErrorCode.INVALID_CREDENTIALS) =>
        "The email or password is incorrect."
      case response: RawErrorResponse if response.code == 401 =>
        "The email or password is incorrect."
      case response: RawErrorResponse if response.code == 400 =>
        "The account details did not pass validation. Please check each field."
      case _: IllegalArgumentException =>
        "The account details did not pass validation. Please check each field."
      case _ => "The identity service could not complete the request. Please try again shortly."

  private def textField(
      labelText: String,
      inputType: String,
      inputState: SignallingRef[IO, String],
      placeholderText: String,
      autocomplete: String,
      error: SignallingRef[IO, String],
      submitting: SignallingRef[IO, Boolean],
  ) =
    label(
      cls := "field",
      span(labelText),
      input.withSelf { self =>
        (
          typ := inputType,
          value <-- inputState,
          onInput(self.value.get.flatMap(inputState.set)),
          disabled <-- submitting,
          placeholder := placeholderText,
          autoComplete := autocomplete,
          aria.invalid <-- error.map(message => if message.nonEmpty then "true" else "false"),
          aria.describedBy <-- error.map(message => Option.when(message.nonEmpty)("auth-error")),
        )
      },
    )

  private def passwordStrength(password: String) =
    val strength = List(
      password.length >= 12,
      password.exists(_.isUpper),
      password.exists(_.isDigit),
      password.exists(character => !character.isLetterOrDigit),
    ).count(identity)

    div(
      cls := "password-strength",
      aria.live := "polite",
      div(
        (1 to 4).toList.map { step =>
          span(cls := Option.when(step <= strength)("is-filled").getOrElse(""))
        },
      ),
      small(
        if strength < 2 then "Keep going"
        else if strength < 4 then "Good password"
        else "Strong password",
      ),
    )

  private def authBenefit(icon: Icon, copy: String) =
    li(span(Icons(icon)), copy)
