package typelevel.courses.ui

import cats.effect.{IO, Resource}
import fs2.dom.Node
import org.scalajs.dom

enum Icon(val id: String):
  case ArrowRight extends Icon("arrow-right")
  case ArrowLeft extends Icon("arrow-left")
  case ChevronRight extends Icon("chevron-right")
  case ChevronLeft extends Icon("chevron-left")
  case ChevronDown extends Icon("chevron-down")
  case Check extends Icon("check")
  case CircleCheck extends Icon("circle-check")
  case Circle extends Icon("circle")
  case Play extends Icon("play")
  case CirclePlay extends Icon("circle-play")
  case Pause extends Icon("pause")
  case Search extends Icon("search")
  case X extends Icon("x")
  case Menu extends Icon("menu")
  case Bookmark extends Icon("bookmark")
  case Clock extends Icon("clock")
  case Star extends Icon("star")
  case BookOpen extends Icon("book-open")
  case Code extends Icon("code")
  case Infinity extends Icon("infinity")
  case Layers extends Icon("layers")
  case Sparkles extends Icon("sparkles")
  case Eye extends Icon("eye")
  case EyeOff extends Icon("eye-off")
  case ShieldCheck extends Icon("shield-check")
  case ExternalLink extends Icon("external-link")
  case Compass extends Icon("compass")
  case Captions extends Icon("captions")
  case Download extends Icon("download")
  case FileCode extends Icon("file-code")
  case ListVideo extends Icon("list-video")
  case Maximize extends Icon("maximize")
  case Volume2 extends Icon("volume-2")
  case VolumeX extends Icon("volume-x")
  case SlidersHorizontal extends Icon("sliders-horizontal")
  case Globe extends Icon("globe")
  case LibraryBig extends Icon("library-big")
  case Trophy extends Icon("trophy")
  case Route extends Icon("route")
  case Github extends Icon("github")
  case Discord extends Icon("discord")
  case GitBranch extends Icon("git-branch")
  case MessageCircle extends Icon("message-circle")
  case Rss extends Icon("rss")
  case RefreshCw extends Icon("refresh-cw")
  case UserRound extends Icon("user-round")

object Icons:
  private val SvgNamespace = "http://www.w3.org/2000/svg"

  def apply(
      icon: Icon,
      label: Option[String] = None,
      className: String     = ""
  ): Resource[IO, Node[IO]] = Resource.eval(IO.delay {
    val svg = dom.document.createElementNS(SvgNamespace, "svg")
    val use = dom.document.createElementNS(SvgNamespace, "use")

    svg.setAttribute("viewBox", "0 0 24 24")
    svg.setAttribute("width", "24")
    svg.setAttribute("height", "24")
    svg.setAttribute("fill", "none")
    svg.setAttribute("stroke", "currentColor")
    svg.setAttribute("stroke-width", "2")
    svg.setAttribute("stroke-linecap", "round")
    svg.setAttribute("stroke-linejoin", "round")
    svg.setAttribute("focusable", "false")
    svg.setAttribute("data-lucide", icon.id)
    svg.setAttribute(
      "class",
      List(s"lucide lucide-${icon.id}", className).filter(_.nonEmpty).mkString(" ")
    )

    label match
      case Some(value) =>
        svg.setAttribute("role", "img")
        svg.setAttribute("aria-label", value)
      case None =>
        svg.setAttribute("aria-hidden", "true")

    use.setAttribute("href", s"/icons.svg#${icon.id}")
    svg.appendChild(use)
    svg.asInstanceOf[Node[IO]]
  })
