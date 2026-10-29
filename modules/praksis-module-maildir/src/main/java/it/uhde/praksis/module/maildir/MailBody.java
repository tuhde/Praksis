// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads what a person wants of the body of a mail: the plain text, the HTML, and the list of
 * attachments. It understands multipart mails (also nested), the transfer encodings base64 and
 * quoted-printable, and charsets; it never executes or opens anything, and the content of an
 * attachment is not read, only its name, type and size.
 */
final class MailBody {
  /** An attachment: what a list shows, not its content. */
  record Attachment(String name, String type, long size) {}

  /** The parts of a mail that a reader shows. */
  record Parsed(String text, String html, List<Attachment> attachments) {}

  private static final int MAX_DEPTH = 8;

  private MailBody() {}

  /** Parses a whole mail as it is in the file. */
  static Parsed parse(byte[] mail) {
    String raw = new String(mail, StandardCharsets.ISO_8859_1);
    Collector out = new Collector();
    part(raw, 0, out);
    return new Parsed(out.text, out.html, out.attachments);
  }

  private static final class Collector {
    String text = "";
    String html = "";
    final List<Attachment> attachments = new ArrayList<>();
  }

  private static void part(String raw, int depth, Collector out) {
    int split = headerEnd(raw);
    Map<String, String> headers = Headers.parse(raw.substring(0, split));
    String body = bodyAfterHeaders(raw, split);
    String contentType = headers.getOrDefault("content-type", "text/plain");
    String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    String disposition = headers.getOrDefault("content-disposition", "").toLowerCase(Locale.ROOT);
    String name = parameter(headers.getOrDefault("content-disposition", ""), "filename");
    if (name.isEmpty()) {
      name = parameter(contentType, "name");
    }

    if (type.startsWith("multipart/") && depth < MAX_DEPTH) {
      String boundary = parameter(contentType, "boundary");
      if (!boundary.isEmpty()) {
        for (String child : split(body, boundary)) {
          part(child, depth + 1, out);
        }
        return;
      }
    }
    boolean attached = disposition.startsWith("attachment") || !name.isEmpty();
    byte[] content = decode(body, headers.getOrDefault("content-transfer-encoding", ""));
    if (attached || (!type.equals("text/plain") && !type.equals("text/html"))) {
      if (!type.startsWith("multipart/")) {
        out.attachments.add(
            new Attachment(name.isEmpty() ? "(no name)" : name, type, content.length));
      }
      return;
    }
    String text = new String(content, charset(parameter(contentType, "charset")));
    if (type.equals("text/html")) {
      if (out.html.isEmpty()) {
        out.html = text;
      }
    } else if (out.text.isEmpty()) {
      out.text = text;
    }
  }

  /** The index where the blank line after the headers begins, or the end if there is none. */
  private static int headerEnd(String raw) {
    int lf = raw.indexOf("\n\n");
    int crlf = raw.indexOf("\r\n\r\n");
    if (lf < 0 && crlf < 0) {
      return raw.length();
    }
    if (lf < 0) {
      return crlf;
    }
    if (crlf < 0) {
      return lf;
    }
    return Math.min(lf, crlf);
  }

  private static String bodyAfterHeaders(String raw, int split) {
    if (split >= raw.length()) {
      return "";
    }
    int start = raw.startsWith("\r\n\r\n", split) ? split + 4 : split + 2;
    return raw.substring(Math.min(start, raw.length()));
  }

  /** The parts between the boundary lines, without the preamble and the epilogue. */
  private static List<String> split(String body, String boundary) {
    List<String> parts = new ArrayList<>();
    String delimiter = "--" + boundary;
    int at = 0;
    int start = -1;
    while (at <= body.length()) {
      int line = body.indexOf(delimiter, at);
      if (line < 0 || (line > 0 && body.charAt(line - 1) != '\n')) {
        if (line >= 0) {
          at = line + delimiter.length();
          continue;
        }
        break;
      }
      if (start >= 0) {
        parts.add(trimEnd(body.substring(start, line)));
      }
      int after = line + delimiter.length();
      if (body.startsWith("--", after)) {
        return parts; // the closing delimiter
      }
      int eol = body.indexOf('\n', after);
      start = eol < 0 ? body.length() : eol + 1;
      at = start;
    }
    if (start >= 0 && start < body.length()) {
      parts.add(trimEnd(body.substring(start)));
    }
    return parts;
  }

  /** The line break before a delimiter belongs to the delimiter. */
  private static String trimEnd(String part) {
    if (part.endsWith("\r\n")) {
      return part.substring(0, part.length() - 2);
    }
    return part.endsWith("\n") ? part.substring(0, part.length() - 1) : part;
  }

  private static byte[] decode(String body, String encoding) {
    switch (encoding.strip().toLowerCase(Locale.ROOT)) {
      case "base64" -> {
        try {
          return Base64.getMimeDecoder().decode(body.getBytes(StandardCharsets.ISO_8859_1));
        } catch (IllegalArgumentException e) {
          return body.getBytes(StandardCharsets.ISO_8859_1);
        }
      }
      case "quoted-printable" -> {
        return quotedPrintable(body);
      }
      default -> {
        return body.getBytes(StandardCharsets.ISO_8859_1);
      }
    }
  }

  private static byte[] quotedPrintable(String body) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '='
          && i + 2 < body.length() + 0
          && hex(body.charAt(i + 1)) >= 0
          && hex(body.charAt(i + 2)) >= 0) {
        out.write(hex(body.charAt(i + 1)) * 16 + hex(body.charAt(i + 2)));
        i += 2;
      } else if (c == '=' && body.startsWith("\r\n", i + 1)) {
        i += 2; // a soft line break
      } else if (c == '=' && body.startsWith("\n", i + 1)) {
        i += 1;
      } else {
        out.write(c);
      }
    }
    return out.toByteArray();
  }

  private static int hex(char c) {
    return Character.digit(c, 16);
  }

  private static Charset charset(String name) {
    if (name.isEmpty()) {
      return StandardCharsets.UTF_8;
    }
    try {
      return Charset.forName(name);
    } catch (IllegalArgumentException e) {
      return StandardCharsets.UTF_8;
    }
  }

  /** The value of {@code name=value} or {@code name="value"} in a header value. */
  static String parameter(String header, String name) {
    String lower = header.toLowerCase(Locale.ROOT);
    int at = 0;
    while ((at = lower.indexOf(name + "=", at)) >= 0) {
      boolean start = at == 0 || lower.charAt(at - 1) == ';' || lower.charAt(at - 1) == ' ';
      if (start) {
        int from = at + name.length() + 1;
        if (from < header.length() && header.charAt(from) == '"') {
          int end = header.indexOf('"', from + 1);
          return header.substring(from + 1, end < 0 ? header.length() : end);
        }
        int end = header.indexOf(';', from);
        return header.substring(from, end < 0 ? header.length() : end).strip();
      }
      at += name.length();
    }
    return "";
  }
}
