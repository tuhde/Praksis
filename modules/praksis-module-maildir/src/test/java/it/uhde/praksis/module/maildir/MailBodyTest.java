// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MailBodyTest {
  private static MailBody.Parsed parse(String mail) {
    return MailBody.parse(mail.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  void aPlainMailHasItsTextAndNothingElse() {
    MailBody.Parsed p = parse("Subject: x\n\nHello\nWorld\n");
    assertThat(p.text()).isEqualTo("Hello\r\nWorld\r\n");
    assertThat(p.html()).isEmpty();
    assertThat(p.attachments()).isEmpty();
  }

  @Test
  void transferEncodingsAndCharsetsAreUndone() {
    String qp =
        "Content-Type: text/plain; charset=utf-8\nContent-Transfer-Encoding: quoted-printable\n\n"
            + "Gr=C3=BC=C3=9Fe aus K=C3=B6ln, a long line that is=\n broken\n";
    assertThat(parse("Subject: x\n" + qp).text())
        .startsWith("Grüße aus Köln")
        .contains("is broken");

    String b64 =
        "Content-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: base64\n\n"
            + Base64.getMimeEncoder().encodeToString("Über uns".getBytes(StandardCharsets.UTF_8))
            + "\n";
    assertThat(parse(b64).text()).isEqualTo("Über uns");

    String latin =
        "Content-Type: text/plain; charset=ISO-8859-1\nContent-Transfer-Encoding: quoted-printable\n\n"
            + "Caf=E9\n";
    assertThat(parse(latin).text()).startsWith("Café");
  }

  @Test
  void aMultipartMailGivesTextHtmlAndAttachmentsInNestedParts() {
    String mail =
        """
        Subject: mixed
        Content-Type: multipart/mixed; boundary="outer"

        preamble
        --outer
        Content-Type: multipart/alternative; boundary=inner

        --inner
        Content-Type: text/plain; charset=utf-8

        the text
        --inner
        Content-Type: text/html; charset=utf-8

        <p>the <b>html</b></p>
        --inner--

        --outer
        Content-Type: application/pdf; name="plan.pdf"
        Content-Disposition: attachment; filename="plan.pdf"
        Content-Transfer-Encoding: base64

        AAECAwQF
        --outer
        Content-Type: image/png
        Content-Disposition: inline; filename="logo.png"
        Content-Transfer-Encoding: base64

        AAEC
        --outer--
        epilogue
        """;
    MailBody.Parsed p = parse(mail);
    assertThat(p.text()).isEqualTo("the text");
    assertThat(p.html()).isEqualTo("<p>the <b>html</b></p>");
    assertThat(p.attachments())
        .containsExactly(
            new MailBody.Attachment("plan.pdf", "application/pdf", 6),
            new MailBody.Attachment("logo.png", "image/png", 3));
  }

  @Test
  void whatIsMalformedIsShownAsFarAsItGoes() {
    assertThat(parse("Content-Type: multipart/mixed\n\nno boundary here\n").text()).isEmpty();
    assertThat(parse("Content-Type: text/plain; charset=nonsense\n\nfine\n").text())
        .startsWith("fine");
    assertThat(parse("").text()).isEmpty();
    assertThat(MailBody.parameter("text/plain; charset=\"utf-8\"; format=flowed", "charset"))
        .isEqualTo("utf-8");
    assertThat(MailBody.parameter("attachment; filename=a.txt", "filename")).isEqualTo("a.txt");
  }
}
