/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectionContext;
import akka.pki.pem.DERPrivateKeyLoader;
import akka.pki.pem.PEMDecoder;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/* COMPILE ONLY TEST */
public class HttpsServerExampleTest extends JUnitSuite {

  @Test
  public void compileOnlySpec() throws Exception {
    // just making sure for it to be really compiled / run even if empty
  }

  void requireClientAuth() {
    final ActorSystem system = ActorSystem.create();
    SSLContext sslContext = null;
    //#require-client-auth
    ConnectionContext.httpsServer(() -> {
            SSLEngine engine = sslContext.createSSLEngine();
            engine.setUseClientMode(false);

            engine.setNeedClientAuth(true);
            // or: engine.setWantClientAuth(true);

            return engine;
    });
    //#require-client-auth
  }

  static //#rotate-certs
  class RefreshableSSLContextReader {
    private final Path certPath;
    private final List<Path> caCertPaths;
    private final Path keyPath;
    private final Duration refreshPeriod;

    private final SecureRandom rng = new SecureRandom();

    // invoked from different threads so important that this cache is thread safe
    private class CachedContext {
      final SSLContext context;
      final Instant expires;

      public CachedContext(SSLContext context, Instant expires) {
        this.context = context;
        this.expires = expires;
      }
    }

    // accessed from different threads so important that this cache is thread safe
    private AtomicReference<CachedContext> contextRef = new AtomicReference<CachedContext>();


    public RefreshableSSLContextReader(Path certPath, List<Path> caCertPaths, Path keyPath, Duration refreshPeriod) {
      this.certPath = certPath;
      this.caCertPaths = caCertPaths;
      this.keyPath = keyPath;
      this.refreshPeriod = refreshPeriod;
    }

    public SSLContext getContext() {
      CachedContext cached = contextRef.get();
      if (cached == null || Instant.now().isAfter(cached.expires)) {
        SSLContext context = constructContext();
        contextRef.set(new CachedContext(context, Instant.now().plus(refreshPeriod)));
        return context;
      } else {
        return cached.context;
      }
    }

    private SSLContext constructContext() {
      try {
        List<X509Certificate> certChain = readCerts(certPath);
        List<X509Certificate> caCertChain = new ArrayList<>();
        for (Path caCertPath: caCertPaths) {
          caCertChain.addAll(readCerts(caCertPath));
        }
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null);
        // Load the private key
        PrivateKey privateKey =
            DERPrivateKeyLoader.load(PEMDecoder.decode(Files.readString(keyPath)));

        List<X509Certificate> allCerts = new ArrayList<>();
        allCerts.addAll(certChain);
        allCerts.addAll(caCertChain);

        int idx = 1;
        for (X509Certificate cert: allCerts) {
          keyStore.setCertificateEntry("cert-" + idx, cert);
          idx += 1;
        }
        keyStore.setKeyEntry(
          "private-key",
          privateKey,
          "changeit".toCharArray(),
          allCerts.toArray(new X509Certificate[0])
        );

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());
        KeyManager[] keyManagers = kmf.getKeyManagers();

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(keyManagers, new TrustManager[0], rng);
        return ctx;
      } catch (FileNotFoundException e) {
        throw new RuntimeException(
            "SSL context could not be loaded because a cert or key file could not be found",
            e
        );
      } catch (IOException e) {
        throw new RuntimeException(
            "SSL context could not be loaded due to error reading cert or key file",
            e
        );
      } catch (GeneralSecurityException | IllegalArgumentException e) {
        throw new RuntimeException("SSL context could not be loaded", e);
      }
    }

    private List<X509Certificate> readCerts(Path path) throws CertificateException, IOException {
      CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
      try(InputStream is = new BufferedInputStream(new FileInputStream(path.toFile()))) {
        var certs = new ArrayList<X509Certificate>();
        while (hasAnotherCertificate(is)) {
          X509Certificate cert = (X509Certificate) certFactory.generateCertificate(is);
          certs.add(cert);
        }
        if (certs.isEmpty())
          throw new IllegalArgumentException("Empty certificate file " + path);
        return certs;
      }
    }

    private boolean hasAnotherCertificate(InputStream is) throws IOException {
      // Read up to 16 characters, in practice, a maximum of two whitespace characters (CRLF) will be present
      is.mark(16);
      int c = is.read();
      while (c >= 0 && Character.isWhitespace(c)) {
        c = (char) is.read();
      }
      is.reset();
      return c >= 0;
    }

  }
  //#rotate-certs

  void rotatingCert() {
    //#rotate-certs

    // Important: defined outside the factory function
    // to be a single shared instance
    final RefreshableSSLContextReader refreshableSSLContextReader = new RefreshableSSLContextReader(
        Paths.get("/some/path/server.crt"),
        List.of(Paths.get("/some/path/serverCA.crt")),
        Paths.get("/some/path/server.key"),
        Duration.ofMinutes(5)
    );
    ConnectionContext.httpsServer(() -> {
      SSLContext context = refreshableSSLContextReader.getContext();
      SSLEngine engine = context.createSSLEngine();
      engine.setUseClientMode(false);
      return engine;
    });
    //#rotate-certs
  }

}
