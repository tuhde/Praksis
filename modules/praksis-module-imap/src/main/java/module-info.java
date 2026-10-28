// SPDX-License-Identifier: Apache-2.0
/** Reads mail from an IMAP server: the {@code mail.received} event and {@code mail.move}. */
module it.uhde.praksis.module.imap {
  requires it.uhde.praksis.capability;
  requires jakarta.mail;
  requires org.eclipse.angus.mail;
  requires java.logging;

  provides it.uhde.praksis.capability.Capability with
      it.uhde.praksis.module.imap.ImapMove;
  provides it.uhde.praksis.capability.EventSource with
      it.uhde.praksis.module.imap.ImapSource;
}
