package praksis.camelmod;

import jakarta.mail.Session;
import jakarta.mail.Store;
import java.util.Map;
import java.util.Properties;
import org.apache.camel.Endpoint;
import org.apache.camel.impl.DefaultCamelContext;
import praksis.api.Capability;

/** Resolves the Camel IMAP endpoint and the jakarta.mail IMAP store without connecting anywhere. */
public class MailProbeCapability implements Capability {
  @Override
  public String name() {
    return "camel.mail.probe";
  }

  @Override
  public Object invoke(Map<String, Object> args) throws Exception {
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    try (DefaultCamelContext ctx = new DefaultCamelContext()) {
      ctx.start();
      Endpoint endpoint = ctx.getEndpoint("imaps://imap.example.org?username=u&password=p");
      Store store = Session.getInstance(new Properties()).getStore("imaps");
      return Map.of(
          "endpoint", endpoint.getClass().getName(),
          "store", store.getClass().getName());
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }
}
