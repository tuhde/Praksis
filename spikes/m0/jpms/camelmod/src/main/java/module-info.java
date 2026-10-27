module praksis.camelmod {
  requires praksis.api;
  requires camel.api;
  requires camel.core.engine;
  requires jakarta.mail;
  requires org.slf4j;
  provides praksis.api.Capability with
      praksis.camelmod.CamelFileCapability,
      praksis.camelmod.MailProbeCapability;
}
