// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeadersTest {
  @Test
  void parsesAndUnfoldsHeadersAndStopsAtTheBody() {
    var h =
        Headers.parse(
            "From: a@example.org\r\nSubject: one\r\n two\r\nSubject: ignored\r\n\r\nTo: not-a-header\r\n");
    assertThat(h).containsEntry("from", "a@example.org").containsEntry("subject", "one two");
    assertThat(h).doesNotContainKey("to");
  }

  @Test
  void decodesEncodedWords() {
    assertThat(Headers.decode("=?UTF-8?B?R3LDvMOfZQ==?=")).isEqualTo("Grüße");
    assertThat(Headers.decode("=?ISO-8859-1?Q?Gr=FC=DFe_aus_K=F6ln?=")).isEqualTo("Grüße aus Köln");
    // whitespace between adjacent encoded words is dropped, other text is kept
    assertThat(Headers.decode("Re: =?UTF-8?Q?a?= =?UTF-8?Q?b?= end")).isEqualTo("Re: ab end");
  }

  @Test
  void leavesMalformedWordsAlone() {
    assertThat(Headers.decode("=?UTF-8?Q?bad=Z?=")).isEqualTo("=?UTF-8?Q?bad=Z?=");
    assertThat(Headers.decode("=?nope-charset?B?AAAA?=")).isEqualTo("=?nope-charset?B?AAAA?=");
  }
}
