/*
 * Copyright (C) 2024 Lightbend Inc. <https://akka.io>
 */

package docs.http.javadsl.server.directives;

import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;

public class TlsDirectivesExamplesTest extends AllDirectives {

    public void compileOnlySpecClientCert() throws Exception {
        //#client-cert
        final Route route = extractClientCertificate(certificate ->
                complete(certificate.getSubjectX500Principal().getName())
        );
        //#client-cert
    }


    public void compileOnlySpecClientCertIdentity() throws Exception {
        //#client-cert-identity
        final Route route = requireClientCertificateIdentity(".*client1", () ->
                complete("OK")
        );
        //#client-cert-identity
    }
}
