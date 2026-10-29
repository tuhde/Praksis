// SPDX-License-Identifier: Apache-2.0
/**
 * Reads mail from a local Maildir: the {@code mail.received} event, {@code mail.move}, {@code
 * mail.list}, {@code mail.search}, {@code mail.mark}, {@code mail.delete} and {@code
 * mail.attachment}.
 */
module it.uhde.praksis.module.maildir {
  requires it.uhde.praksis.capability;

  provides it.uhde.praksis.capability.Capability with
      it.uhde.praksis.module.maildir.MailAppend,
      it.uhde.praksis.module.maildir.MailAttachment,
      it.uhde.praksis.module.maildir.MailDelete,
      it.uhde.praksis.module.maildir.MailFolders,
      it.uhde.praksis.module.maildir.MailGet,
      it.uhde.praksis.module.maildir.MailList,
      it.uhde.praksis.module.maildir.MailMark,
      it.uhde.praksis.module.maildir.MailMove,
      it.uhde.praksis.module.maildir.MailSearch;
  provides it.uhde.praksis.capability.EventSource with
      it.uhde.praksis.module.maildir.MaildirSource;
}
