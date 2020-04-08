/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.actor.{ ActorRefFactory, ActorSystem, ClassicActorSystemProvider }
import akka.annotation.InternalApi
import com.typesafe.config.Config
import akka.http.impl.util._

/** INTERNAL API */
@InternalApi
private[akka] trait SettingsCompanion[T] {

  /**
   * Creates an instance of settings using the configuration provided by the given ActorSystem.
   */
  final def apply(system: ActorSystem): T = apply(system.settings.config)
  final def apply(system: ClassicActorSystemProvider): T = apply(system.classicSystem.settings.config)
  implicit def default(implicit system: ClassicActorSystemProvider): T = apply(system.classicSystem)
  def default(actorRefFactory: ActorRefFactory): T = apply(actorSystem(actorRefFactory))

  /**
   * Creates an instance of settings using the given Config.
   */
  def apply(config: Config): T

  /**
   * Create an instance of settings using the given String of config overrides to override
   * settings set in the class loader of this class (i.e. by application.conf or reference.conf files in
   * the class loader of this class).
   */
  def apply(configOverrides: String): T
}
