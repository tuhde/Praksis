// SPDX-License-Identifier: Apache-2.0
/** Reads mail from a local Maildir: the {@code mail.received} event and {@code mail.move}. */
module it.uhde.praksis.module.maildir {
  requires it.uhde.praksis.capability;

  provides it.uhde.praksis.capability.Capability with
      it.uhde.praksis.module.maildir.MailMove;
  provides it.uhde.praksis.capability.EventSource with
      it.uhde.praksis.module.maildir.MaildirSource;
}
