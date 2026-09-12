package org.typelevel.video.streaming.backend.runtime.context

trait RequestContext[F[_], A]:

  def get: F[Option[A]]

  def scope[B](value: A)(effect: F[B]): F[B]
