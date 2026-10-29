// SPDX-License-Identifier: Apache-2.0
/**
 * Reads mail from a local Maildir: the {@code mail.received} event, {@code mail.move} and {@code
 * mail.list}.
 */
module it.uhde.praksis.module.maildir {
  requires it.uhde.praksis.capability;

  provides it.uhde.praksis.capability.Capability with
      it.uhde.praksis.module.maildir.MailFolders,
      it.uhde.praksis.module.maildir.MailGet,
      it.uhde.praksis.module.maildir.MailList,
      it.uhde.praksis.module.maildir.MailMove;
  provides it.uhde.praksis.capability.EventSource with
      it.uhde.praksis.module.maildir.MaildirSource;
}
