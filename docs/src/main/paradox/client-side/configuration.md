# Configuration

HTTP client settings are split into different sections  
 
 * `akka.http.client`: basic client settings
 * `akka.http.host-connection-pool`: pool settings
 
## Basic Client Settings

These settings influence the basic library behavior for each HTTP connection. When changed in the application's
`application.conf` they globally affect the default client behavior.

Basic client settings can be overridden in multiple ways:

 * by passing custom @apidoc[ClientConnectionSettings] instances to APIs in @apidoc[Http$]
 * by overriding settings in `akka.http.host-connection-pool.client`, these overrides will take effect whenever a pool is used
   like with `Http().singleRequest`
 * by putting custom @apidoc[ClientConnectionSettings] into @apidoc[ConnectionPoolSettings] and passing those to APIs in `Http`
 * by using [per-host overrides](#per-host-overrides)

@@snip [reference.conf](/akka-http-core/src/main/resources/reference.conf) { #client-settings }

## Pool Settings

Pool settings influence the behavior of client connection pools as used with APIs like `Http.singleRequest`
(see @ref[request-level](request-level.md) and @ref[host-level](host-level.md)).

This includes the amount of total concurrent connections a pool should open to a target host and other settings.
These settings include a (by default empty) section `client` that can be used to override basic client settings
when used in the context of a pool.

Pool settings can be overridden on a [per-target-host](#per-host-overrides) basis.

@@snip [reference.conf](/akka-http-core/src/main/resources/reference.conf) { #pool-settings }

## Per Host Overrides

Settings can be overridden on a per-host basis by creating a list of `host-patterns` together with overridden settings
in the `akka.http.host-connection-pool.per-host-override` setting.

Note that only the first matching entry is selected and used even if multiple entries would match.

@@snip [reference.conf](/akka-http-core/src/main/resources/reference.conf) { #per-host-overrides }

## Precedence of settings

When using pool APIs, settings take precedence like this (highest precedence first):

 * client settings in first `per-host-override` entry whose `host-pattern` matches the given target host
 * settings in `akka.http.host-connection-pool.client`
 * settings in `akka.http.client`
