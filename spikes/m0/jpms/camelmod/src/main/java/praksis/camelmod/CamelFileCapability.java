// SPDX-License-Identifier: Apache-2.0
package praksis.camelmod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.camel.ConsumerTemplate;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import praksis.api.Capability;

/** Reads a directory through Camel's file component; Camel types never leave this module. */
public class CamelFileCapability implements Capability {
  @Override
  public String name() {
    return "camel.file.list";
  }

  @Override
  public Object invoke(Map<String, Object> args) throws Exception {
    String dir = (String) args.get("dir");
    boolean tccl = Boolean.TRUE.equals(args.get("setTccl"));
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    if (tccl) Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    try (DefaultCamelContext ctx = new DefaultCamelContext()) {
      ctx.start();
      ConsumerTemplate consumer = ctx.createConsumerTemplate();
      List<String> names = new ArrayList<>();
      String uri = "file:" + dir + "?noop=true&initialDelay=0";
      Exchange ex;
      while ((ex = consumer.receive(uri, 500)) != null) {
        names.add(ex.getIn().getHeader(Exchange.FILE_NAME, String.class));
        consumer.doneUoW(ex);
      }
      return names;
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }
}
