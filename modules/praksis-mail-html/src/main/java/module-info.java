// SPDX-License-Identifier: Apache-2.0
/**
 * Turns the HTML of a mail into a small, safe description: blocks of text with a few styles and
 * links, and nothing that could load, run or style anything.
 */
module it.uhde.praksis.mail.html {
  requires org.jsoup;

  exports it.uhde.praksis.mail.html;
}
