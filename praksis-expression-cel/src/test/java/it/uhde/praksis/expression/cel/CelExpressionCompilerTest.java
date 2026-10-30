// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.expression.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.expression.Bindings;
import it.uhde.praksis.expression.Expression;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.expression.ExpressionException;
import it.uhde.praksis.expression.Scope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CelExpressionCompilerTest {
  private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

  private final ExpressionCompiler compiler = new CelExpressionCompiler();

  private static final EventTypeDescriptor MAIL =
      new EventTypeDescriptor(
          "mail.received",
          List.of(
              new FieldDescriptor("message", ValueType.STRING, true, false),
              new FieldDescriptor("subject", ValueType.STRING, true, false),
              new FieldDescriptor("size", ValueType.INT, false, false),
              new FieldDescriptor("headers", ValueType.MAP, false, false)));

  private static final Scope MAIL_SCOPE =
      new Scope(
          Optional.of(MAIL),
          List.of(
              new FieldDescriptor("newsletter_folder", ValueType.STRING, true, false),
              new FieldDescriptor("senders", ValueType.LIST, true, false)),
          Map.of(
              "find",
              List.of(
                  new FieldDescriptor("folder", ValueType.STRING, true, false),
                  new FieldDescriptor("count", ValueType.INT, true, false))));

  private static Bindings mailBindings() {
    Event event =
        new Event(
            "e1",
            "mail.received",
            "maildir",
            NOW,
            "mailbox:work",
            Optional.empty(),
            Optional.of("e0"),
            2,
            Map.of(
                "message", "msg-1",
                "subject", "[news] hello",
                "size", 1234L,
                "headers", Map.of("List-Id", "news.example.org"),
                "priority", "low"));
    return new Bindings(
        Optional.of(event),
        Map.of("newsletter_folder", "News", "senders", List.of("hello", "zzz")),
        Map.of("find", Map.of("folder", "Inbox", "count", 3L)),
        NOW);
  }

  private Object eval(String source) throws ExpressionException {
    return compiler.compile(source, MAIL_SCOPE).evaluate(mailBindings());
  }

  @Test
  void evaluatesTypedEventFields() throws Exception {
    assertThat(eval("event.data.subject.startsWith('[') && event.type == 'mail.received'"))
        .isEqualTo(true);
    assertThat(eval("event.data.headers['List-Id'] != ''")).isEqualTo(true);
    assertThat(eval("event.data.size + 1")).isEqualTo(1235L);
    assertThat(eval("event.depth")).isEqualTo(2L);
    assertThat(eval("event.parent")).isEqualTo("e0");
    assertThat(eval("event.key")).isEqualTo("");
  }

  @Test
  void freeFormDataIsInExtras() throws Exception {
    assertThat(eval("event.extras.priority")).isEqualTo("low");
  }

  @Test
  void paramsResultsAndNow() throws Exception {
    assertThat(eval("params.newsletter_folder")).isEqualTo("News");
    assertThat(eval("results.find.count > 2")).isEqualTo(true);
    assertThat(eval("now")).isEqualTo(NOW);
    assertThat(eval("event.time == now")).isEqualTo(true);
  }

  @Test
  void theStandardMacrosWork() throws Exception {
    assertThat(eval("params.senders.exists(s, event.data.subject.contains(s))")).isEqualTo(true);
    assertThat(eval("params.senders.all(s, event.data.subject.contains(s))")).isEqualTo(false);
    assertThat(eval("params.senders.filter(s, s.startsWith('h'))")).isEqualTo(List.of("hello"));
    assertThat(eval("params.senders.map(s, s.size())")).isEqualTo(List.of(5L, 3L));
    assertThat(eval("has(event.data.headers.x)")).isEqualTo(false);
  }

  @Test
  void theTextFunctionsWork() throws Exception {
    assertThat(eval("params.senders.join(', ')")).isEqualTo("hello, zzz");
    assertThat(eval("'hello'.substring(1, 3)")).isEqualTo("el");
    assertThat(eval("'  a b '.trim().split(' ')")).isEqualTo(List.of("a", "b"));
    assertThat(eval("'ABC'.lowerAscii()")).isEqualTo("abc");
  }

  @Test
  void returnsStructuredValues() throws Exception {
    assertThat(eval("event.data.headers")).isEqualTo(Map.of("List-Id", "news.example.org"));
    assertThat(eval("[1, 2, 3]")).isEqualTo(List.of(1L, 2L, 3L));
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) eval("event.data");
    assertThat(data).containsEntry("subject", "[news] hello").containsEntry("size", 1234L);
  }

  @Test
  void rejectsUnknownFieldsAtCompileTime() {
    assertThatThrownBy(() -> compiler.compile("event.data.subjekt.startsWith('[')", MAIL_SCOPE))
        .isInstanceOf(ExpressionException.class);
    assertThatThrownBy(() -> compiler.compile("event.nonexistent == 1", MAIL_SCOPE))
        .isInstanceOf(ExpressionException.class);
    assertThatThrownBy(() -> compiler.compile("results.other.x", MAIL_SCOPE))
        .isInstanceOf(ExpressionException.class);
    assertThatThrownBy(() -> compiler.compile("params.nope", MAIL_SCOPE))
        .isInstanceOf(ExpressionException.class);
  }

  @Test
  void rejectsTypeErrorsAtCompileTime() {
    assertThatThrownBy(() -> compiler.compile("event.data.subject + 1 == 2", MAIL_SCOPE))
        .isInstanceOf(ExpressionException.class);
  }

  @Test
  void checksTheExpectedResultType() throws Exception {
    Expression ok = compiler.compile("event.data.size > 10", MAIL_SCOPE, ValueType.BOOL);
    assertThat(ok.evaluate(mailBindings())).isEqualTo(true);
    assertThatThrownBy(() -> compiler.compile("event.data.subject", MAIL_SCOPE, ValueType.BOOL))
        .isInstanceOf(ExpressionException.class);
  }

  @Test
  void eventIsUnavailableWithoutAnEventType() {
    assertThatThrownBy(() -> compiler.compile("event.id", Scope.empty()))
        .isInstanceOf(ExpressionException.class);
  }

  @Test
  void evaluatesWithoutEvent() throws Exception {
    Expression e = compiler.compile("now.getFullYear()", Scope.empty());
    assertThat(e.evaluate(Bindings.of(NOW))).isEqualTo(2026L);
  }

  @Test
  void missingMapKeyFailsAtEvaluation() throws Exception {
    Expression e = compiler.compile("event.data.headers['X-Missing'] != ''", MAIL_SCOPE);
    assertThatThrownBy(() -> e.evaluate(mailBindings())).isInstanceOf(ExpressionException.class);
  }

  @Test
  void wrongValueTypeFailsAtEvaluation() throws Exception {
    Expression e = compiler.compile("event.data.size", MAIL_SCOPE);
    Event bad =
        new Event(
            "e2",
            "mail.received",
            "m",
            NOW,
            "t",
            Optional.empty(),
            Optional.empty(),
            0,
            Map.of("message", "m", "subject", "s", "size", "not a number"));
    assertThatThrownBy(
            () ->
                e.evaluate(
                    new Bindings(
                        Optional.of(bad), Map.of("newsletter_folder", "x"), Map.of(), NOW)))
        .isInstanceOf(ExpressionException.class);
  }

  @Test
  void identifiersAreChecked() {
    Scope bad = new Scope(Optional.empty(), List.of(), Map.of("my-action", List.of()));
    assertThatThrownBy(() -> compiler.compile("true", bad)).isInstanceOf(ExpressionException.class);
  }

  @Test
  void dynamicVariablesHoldPlainValuesOfNoDeclaredType() throws Exception {
    Scope scope =
        new Scope(
            Optional.empty(), List.of(), Map.of(), java.util.Set.of("view", "source", "item"));
    Map<String, Object> dynamic =
        Map.of(
            "view", Map.of("folder", "Inbox", "limit", 5L),
            "source",
                Map.of(
                    "messages",
                    Map.of(
                        "items",
                        List.of(
                            Map.of("id", "m1", "when", NOW, "size", 10L),
                            Map.of("id", "m2", "when", NOW, "size", 20L)))),
            "item", Map.of("subject", "Hello", "tags", List.of("a", "b")));
    Bindings bindings = new Bindings(Optional.empty(), Map.of(), Map.of(), NOW, dynamic);

    assertThat(compiler.compile("view.folder + '/' + item.subject", scope).evaluate(bindings))
        .isEqualTo("Inbox/Hello");
    assertThat(
            compiler
                .compile("source.messages.items.map(m, m.size).filter(n, n > view.limit)", scope)
                .evaluate(bindings))
        .isEqualTo(List.of(10L, 20L));
    assertThat(
            compiler
                .compile("size(source.messages.items)", scope, ValueType.INT)
                .evaluate(bindings))
        .isEqualTo(2L);
    assertThat(compiler.compile("source.messages.items[0].when", scope).evaluate(bindings))
        .isEqualTo(NOW);
    assertThat(
            compiler
                .compile("item.tags.exists(t, t == 'b')", scope, ValueType.BOOL)
                .evaluate(bindings))
        .isEqualTo(true);

    // a variable that the scope does not name does not exist, and one that is not bound is an error
    assertThatThrownBy(() -> compiler.compile("other.x", scope))
        .isInstanceOf(ExpressionException.class);
    assertThatThrownBy(
            () ->
                compiler
                    .compile("view.folder", scope)
                    .evaluate(new Bindings(Optional.empty(), Map.of(), Map.of(), NOW)))
        .isInstanceOf(ExpressionException.class);
  }
}
