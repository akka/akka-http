/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 * The Cookie SameSite attribute as defined by <a href="https://tools.ietf.org/html/draft-ietf-httpbis-cookie-same-site-00">RFC6265bis</a>
 * and <a href="https://tools.ietf.org/html/draft-west-cookie-incrementalism-00">Incrementally Better Cookies</a>.
 */
public enum SameSite {
    Strict,
    Lax,
    // SameSite.None is different from not adding the SameSite attribute in a cookie.
    // - Cookies without a SameSite attribute will be treated as SameSite=Lax.
    // - Cookies for cross-site usage must specify `SameSite=None; Secure` to enable inclusion in third party
    //   context. We are not enforcing `; Secure` when `SameSite=None`, but users should.
    None;

    public akka.http.scaladsl.model.headers.SameSite asScala() {
        if (this == Strict) return akka.http.scaladsl.model.headers.SameSite.Strict$.MODULE$;
        if (this == Lax) return akka.http.scaladsl.model.headers.SameSite.Lax$.MODULE$;
        return akka.http.scaladsl.model.headers.SameSite.None$.MODULE$;
    }
}
