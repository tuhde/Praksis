// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EventSink;
import it.uhde.praksis.capability.EventSource;
import java.util.Map;

/**
 * How the engine calls into a module. A module lives in a layer of its own with its own class
 * loader, but libraries such as Jakarta Activation find their plug-ins (the handlers that turn a
 * multipart mail into bytes) through the context class loader of the calling thread, which in the
 * daemon is not the module's. Without this, mail with attachments cannot be built or read in the
 * runtime image, though it works in a test JVM where everything is on one class path. So every call
 * into a module, and the start of an event source (whose threads inherit it), runs with the
 * module's class loader as the context class loader.
 */
public final class ModuleCalls {
  private ModuleCalls() {}

  /** Calls the capability with the class loader of its module as the context class loader. */
  public static Map<String, Object> invoke(
      Capability capability, CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    Thread thread = Thread.currentThread();
    ClassLoader before = thread.getContextClassLoader();
    thread.setContextClassLoader(capability.getClass().getClassLoader());
    try {
      return capability.invoke(context, args);
    } finally {
      thread.setContextClassLoader(before);
    }
  }

  /** Starts the source with the class loader of its module as the context class loader. */
  static void start(EventSource source, EventSink sink) throws CapabilityException {
    Thread thread = Thread.currentThread();
    ClassLoader before = thread.getContextClassLoader();
    thread.setContextClassLoader(source.getClass().getClassLoader());
    try {
      source.start(sink);
    } finally {
      thread.setContextClassLoader(before);
    }
  }
}
