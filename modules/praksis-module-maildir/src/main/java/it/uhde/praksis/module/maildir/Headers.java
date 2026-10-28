// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the header block of a mail file, with RFC 2047 encoded words decoded. */
final class Headers {
  private static final int MAX_HEADER_BYTES = 64 * 1024;
  private static final Pattern ENCODED_WORD =
      Pattern.compile("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?\\s]*)\\?=");
  private static final Pattern BETWEEN_WORDS = Pattern.compile("(\\?=)\\s+(=\\?)");

  private Headers() {}

  /** Header values by lower-case name; the first occurrence of a header wins. */
  static Map<String, String> read(Path file) throws IOException {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(file)) {
      bytes = in.readNBytes(MAX_HEADER_BYTES);
    }
    return parse(new String(bytes, StandardCharsets.UTF_8));
  }

  static Map<String, String> parse(String text) {
    Map<String, String> headers = new HashMap<>();
    String name = null;
    StringBuilder value = new StringBuilder();
    for (String line : text.split("\r?\n", -1)) {
      if (line.isEmpty()) {
        break;
      }
      if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && name != null) {
        value.append(' ').append(line.strip());
        continue;
      }
      if (name != null) {
        headers.putIfAbsent(name, decode(value.toString()));
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        name = null;
        continue;
      }
      name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
      value.setLength(0);
      value.append(line.substring(colon + 1).strip());
    }
    if (name != null) {
      headers.putIfAbsent(name, decode(value.toString()));
    }
    return headers;
  }

  /** Decodes {@code =?charset?B|Q?text?=} words; anything malformed is left as it is. */
  static String decode(String value) {
    String joined = BETWEEN_WORDS.matcher(value).replaceAll("$1$2");
    Matcher m = ENCODED_WORD.matcher(joined);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String decoded;
      try {
        Charset charset = Charset.forName(m.group(1));
        byte[] raw =
            m.group(2).equalsIgnoreCase("B")
                ? Base64.getMimeDecoder().decode(m.group(3))
                : decodeQ(m.group(3));
        decoded = new String(raw, charset);
      } catch (IllegalArgumentException e) {
        decoded = m.group();
      }
      m.appendReplacement(out, Matcher.quoteReplacement(decoded));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static byte[] decodeQ(String text) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '_') {
        out.write(' ');
      } else if (c == '=') {
        if (i + 3 > text.length()) {
          throw new IllegalArgumentException("truncated escape");
        }
        out.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
        i += 2;
      } else {
        out.write(c);
      }
    }
    return out.toByteArray();
  }
}
