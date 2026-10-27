package praksis.dummy;

import java.util.Map;
import praksis.api.Capability;

public class EchoCapability implements Capability {
  @Override
  public String name() {
    return "dummy.echo";
  }

  @Override
  public Object invoke(Map<String, Object> args) {
    return "echo:" + args;
  }
}
