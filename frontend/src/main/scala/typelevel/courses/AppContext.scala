package typelevel.courses

import typelevel.courses.routing.Navigator
import typelevel.courses.state.{AppStore, CatalogStore}

final case class AppContext(navigator: Navigator, store: AppStore, catalog: CatalogStore)
