// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * The opaque {@code message} id of a mail on the server: the folder, the UID validity and the UID
 * (which together name a mail for as long as the server keeps them) and the Message-ID header,
 * which finds the mail again after it moved to another folder.
 *
 * @param folder the folder the mail was in
 * @param validity the UID validity of that folder
 * @param uid the UID of the mail in it, or 0 if unknown
 * @param messageId the Message-ID header, or empty
 */
record MessageId(String folder, long validity, long uid, String messageId) {
  private static final String PREFIX = "imap:";

  String encode() {
    return PREFIX + b64(folder) + ":" + validity + ":" + uid + ":" + b64(messageId);
  }

  static Optional<MessageId> parse(String text) {
    if (text == null || !text.startsWith(PREFIX)) {
      return Optional.empty();
    }
    String[] parts = text.substring(PREFIX.length()).split(":", -1);
    if (parts.length != 4) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new MessageId(
              unb64(parts[0]),
              Long.parseLong(parts[1]),
              Long.parseLong(parts[2]),
              unb64(parts[3])));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static String b64(String s) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String unb64(String s) {
    return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
  }
}
