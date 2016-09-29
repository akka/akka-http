package example;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class Ciphers {
    /**
     * The following list is derived from <a
     * href="http://docs.oracle.com/javase/8/docs/technotes/guides/security/SunProviders.html">SunJSSE Supported
     * Ciphers</a> and <a
     * href="https://wiki.mozilla.org/Security/Server_Side_TLS#Non-Backward_Compatible_Ciphersuite">Mozilla Cipher
     * Suites</a> in accordance with the <a
     * href="https://tools.ietf.org/html/draft-ietf-httpbis-http2-16#section-9.2.2">HTTP/2 Specification</a>.
     */
    public static final List<String> CIPHERS;

    private static final List<String> CIPHERS_JAVA_MOZILLA_INCREASED_SECURITY = Collections.unmodifiableList(Arrays
            .asList(
            /* Java 8 */
                    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", /* openssl = ECDHE-ECDSA-AES256-GCM-SHA384 */
                    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", /* openssl = ECDHE-ECDSA-AES128-GCM-SHA256 */
                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", /* openssl = ECDHE-RSA-AES256-GCM-SHA384 */
            /* REQUIRED BY HTTP/2 SPEC */
                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", /* openssl = ECDHE-RSA-AES128-GCM-SHA256 */
            /* REQUIRED BY HTTP/2 SPEC */
                    "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256", /* openssl = DHE-RSA-AES128-GCM-SHA256 */
                    "TLS_DHE_DSS_WITH_AES_128_GCM_SHA256" /* openssl = DHE-DSS-AES128-GCM-SHA256 */));

    private static final List<String> CIPHERS_JAVA_NO_MOZILLA_INCREASED_SECURITY = Collections.unmodifiableList(Arrays
            .asList(
            /* Java 8 */
                    "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", /* openssl = DHE-RSA-AES256-GCM-SHA384 */
                    "TLS_DHE_DSS_WITH_AES_256_GCM_SHA384" /* openssl = DHE-DSS-AES256-GCM-SHA384 */));

    static {
        List<String> ciphers = new ArrayList<String>(CIPHERS_JAVA_MOZILLA_INCREASED_SECURITY.size()
                + CIPHERS_JAVA_NO_MOZILLA_INCREASED_SECURITY.size());
        ciphers.addAll(CIPHERS_JAVA_MOZILLA_INCREASED_SECURITY);
        ciphers.addAll(CIPHERS_JAVA_NO_MOZILLA_INCREASED_SECURITY);
        CIPHERS = Collections.unmodifiableList(ciphers);
    }

    private Ciphers() { }
}