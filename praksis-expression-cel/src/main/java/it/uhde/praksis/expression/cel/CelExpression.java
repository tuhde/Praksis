// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.expression.cel;

import com.google.protobuf.DynamicMessage;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.expression.Bindings;
import it.uhde.praksis.expression.Expression;
import it.uhde.praksis.expression.ExpressionException;
import it.uhde.praksis.expression.Scope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An expression compiled by cel-java together with what it needs to turn bindings into input. */
final class CelExpression implements Expression {
  private final CelRuntime.Program program;
  private final Scope scope;
  private final Schema schema;

  CelExpression(CelRuntime.Program program, Scope scope, Schema schema) {
    this.program = program;
    this.scope = scope;
    this.schema = schema;
  }

  @Override
  public Object evaluate(Bindings bindings) throws ExpressionException {
    Map<String, Object> input = new HashMap<>();
    input.put("now", bindings.now());
    input.put("params", Values.fill(schema.params, scope.params(), bindings.params()));
    input.put("results", results(bindings));
    if (scope.event().isPresent()) {
      Event event =
          bindings
              .event()
              .orElseThrow(() -> new ExpressionException("the expression needs an event"));
      input.put("event", event(scope.event().get(), event));
    }
    for (String name : scope.dynamic()) {
      if (!bindings.dynamic().containsKey(name)) {
        throw new ExpressionException("the expression needs the variable '" + name + "'");
      }
      input.put(name, bindings.dynamic().get(name));
    }
    try {
      return Values.fromCel(program.eval(input));
    } catch (CelEvaluationException e) {
      throw new ExpressionException(e.getMessage(), e);
    }
  }

  private DynamicMessage results(Bindings bindings) throws ExpressionException {
    DynamicMessage.Builder message = DynamicMessage.newBuilder(schema.results);
    for (var entry : scope.results().entrySet()) {
      Map<String, Object> values = bindings.results().get(entry.getKey());
      if (values == null) {
        continue;
      }
      var type = schema.resultByAction.get(entry.getKey());
      message.setField(
          schema.results.findFieldByName(entry.getKey()),
          Values.fill(type, entry.getValue(), values));
    }
    return message.build();
  }

  private DynamicMessage event(EventTypeDescriptor type, Event event) throws ExpressionException {
    List<FieldDescriptor> fields = type.fields();
    var d = schema.event;
    return DynamicMessage.newBuilder(d)
        .setField(d.findFieldByName("id"), event.id())
        .setField(d.findFieldByName("type"), event.type())
        .setField(d.findFieldByName("source"), event.source())
        .setField(d.findFieldByName("time"), Values.timestamp(event.time()))
        .setField(d.findFieldByName("target"), event.target())
        .setField(d.findFieldByName("key"), event.key().orElse(""))
        .setField(d.findFieldByName("parent"), event.parent().orElse(""))
        .setField(d.findFieldByName("depth"), (long) event.depth())
        .setField(d.findFieldByName("data"), Values.fill(schema.eventData, fields, event.data()))
        .setField(d.findFieldByName("extras"), Values.toStruct(Values.rest(fields, event.data())))
        .build();
  }
}
