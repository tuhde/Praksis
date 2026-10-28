// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.capability.CapabilityException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * One connection to the server, opened when needed and again after it broke. The password is
 * fetched from its command every time a connection is opened, so a changed secret is picked up. Not
 * thread-safe: whoever uses it serializes the calls.
 */
final class Connection implements AutoCloseable {
  private final ImapSettings settings;
  private IMAPStore store;

  Connection(ImapSettings settings) {
    this.settings = settings;
  }

  /** The connected store; connects first if there is no usable connection. */
  IMAPStore store() throws MessagingException, CapabilityException {
    if (store != null && store.isConnected()) {
      return store;
    }
    close();
    String password = Secrets.read(settings.passwordCommand());
    Session session = Session.getInstance(properties());
    IMAPStore fresh = (IMAPStore) session.getStore("imap");
    try {
      fresh.connect(settings.host(), settings.port(), settings.user(), password);
    } catch (MessagingException e) {
      throw new CapabilityException(
          "cannot log in to " + settings.account() + ": " + e.getMessage(), true, e);
    }
    store = fresh;
    return fresh;
  }

  private Properties properties() throws CapabilityException {
    Properties p = new Properties();
    String timeout = String.valueOf(settings.timeout().toMillis());
    p.put("mail.imap.connectiontimeout", timeout);
    p.put("mail.imap.timeout", timeout);
    p.put("mail.imap.writetimeout", timeout);
    p.put("mail.imap.peek", "true");
    p.put("mail.imap.ssl.checkserveridentity", "true");
    p.put("mail.imap.ssl.protocols", "TLSv1.2 TLSv1.3");
    switch (settings.security()) {
      case SSL -> p.put("mail.imap.ssl.enable", "true");
      case STARTTLS -> {
        p.put("mail.imap.starttls.enable", "true");
        p.put("mail.imap.starttls.required", "true");
      }
      case NONE -> {}
    }
    if (settings.caFile().isPresent() && settings.security() != ImapSettings.Security.NONE) {
      p.put("mail.imap.ssl.socketFactory", socketFactory(settings.caFile().get()));
    }
    return p;
  }

  /** A socket factory that trusts the system's authorities and those in the PEM file. */
  private static SSLSocketFactory socketFactory(java.nio.file.Path pem) throws CapabilityException {
    try {
      KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
      keys.load(null, null);
      int n = 0;
      TrustManagerFactory system =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      system.init((KeyStore) null);
      for (TrustManager tm : system.getTrustManagers()) {
        if (tm instanceof X509TrustManager x509) {
          for (var cert : x509.getAcceptedIssuers()) {
            keys.setCertificateEntry("system" + n++, cert);
          }
        }
      }
      try (InputStream in = Files.newInputStream(pem)) {
        for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
          keys.setCertificateEntry("ca" + n++, cert);
        }
      }
      TrustManagerFactory trust =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(keys);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      return context.getSocketFactory();
    } catch (GeneralSecurityException | IOException e) {
      throw new CapabilityException("cannot use ca_file " + pem + ": " + e.getMessage(), false, e);
    }
  }

  @Override
  public void close() {
    if (store != null) {
      try {
        store.close();
      } catch (MessagingException e) {
        // the connection was broken anyway
      }
      store = null;
    }
  }
}
