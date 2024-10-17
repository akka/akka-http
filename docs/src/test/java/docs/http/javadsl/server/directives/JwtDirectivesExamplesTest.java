/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import akka.http.javadsl.server.AuthorizationFailedRejection;
import akka.http.javadsl.server.Directives;
import akka.http.javadsl.server.Route;
import akka.http.jwt.javadsl.server.directives.JwtDirectives;

public class JwtDirectivesExamplesTest extends JwtDirectives {

    public void compileOnlySpecJwt() throws Exception {
        //#jwt
        final Route route = jwt(claims -> {
                if (claims.getStringClaim("sub").isPresent())
                    return Directives.complete(claims.getStringClaim("sub").get());
                else
                    return Directives.reject(AuthorizationFailedRejection.get());
            }
        );
        //#jwt
    }
}
