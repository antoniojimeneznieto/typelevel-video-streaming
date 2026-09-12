package org.typelevel.video.streaming.backend.runtime.context

import cats.effect.IO
import weaver.SimpleIOSuite

object IOLocalRequestContextSuite extends SimpleIOSuite:

  test("the context is empty outside a scope") {
    for
      context <- IOLocalRequestContext.create[String]
      current <- context.get
    yield expect(current.isEmpty)
  }

  test("a value is visible only inside its scope") {
    for
      context <- IOLocalRequestContext.create[String]
      inside  <- context.scope("caller")(context.get)
      outside <- context.get
    yield expect(inside.contains("caller")) and expect(outside.isEmpty)
  }

  test("nested scopes restore the previous value") {
    for
      context <- IOLocalRequestContext.create[String]
      result  <- context.scope("outer") {
                  for
                    inner    <- context.scope("inner")(context.get)
                    restored <- context.get
                  yield expect(inner.contains("inner")) and expect(restored.contains("outer"))
                }
    yield result
  }

  test("a failed effect still restores the previous value") {
    for
      context <- IOLocalRequestContext.create[String]
      _       <- context
             .scope("caller")(IO.raiseError[Unit](new RuntimeException("boom")))
             .attempt
      current <- context.get
    yield expect(current.isEmpty)
  }
