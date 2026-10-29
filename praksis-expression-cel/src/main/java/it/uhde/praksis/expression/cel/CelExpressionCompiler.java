// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.expression.cel;

import com.google.protobuf.Descriptors.Descriptor;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelBuilder;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.expression.Expression;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.expression.ExpressionException;
import it.uhde.praksis.expression.Scope;

/** Compiles expressions with cel-java, type-checked against the scope. */
public final class CelExpressionCompiler implements ExpressionCompiler {
  @Override
  public Expression compile(String source, Scope scope) throws ExpressionException {
    return compile(source, scope, null);
  }

  @Override
  public Expression compile(String source, Scope scope, ValueType expected)
      throws ExpressionException {
    Schema schema = Schema.build(scope);
    CelBuilder builder =
        CelFactory.plannerCelBuilder()
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addMessageTypes(schema.all)
            .addVar("now", SimpleType.TIMESTAMP)
            .addVar("params", struct(schema.params))
            .addVar("results", struct(schema.results));
    if (scope.event().isPresent()) {
      builder.addVar("event", struct(schema.event));
    }
    for (String name : scope.dynamic()) {
      builder.addVar(name, SimpleType.DYN);
    }
    if (expected != null) {
      builder.setResultType(celType(expected));
    }
    Cel cel = builder.build();
    try {
      var ast = cel.compile(source).getAst();
      return new CelExpression(cel.createProgram(ast), scope, schema);
    } catch (CelValidationException | CelEvaluationException e) {
      throw new ExpressionException(e.getMessage(), e);
    }
  }

  private static CelType struct(Descriptor type) {
    return StructTypeReference.create(type.getFullName());
  }

  private static CelType celType(ValueType type) {
    return switch (type) {
      case STRING -> SimpleType.STRING;
      case INT -> SimpleType.INT;
      case DOUBLE -> SimpleType.DOUBLE;
      case BOOL -> SimpleType.BOOL;
      case BYTES -> SimpleType.BYTES;
      case TIMESTAMP -> SimpleType.TIMESTAMP;
      case LIST -> ListType.create(SimpleType.DYN);
      case MAP -> MapType.create(SimpleType.STRING, SimpleType.DYN);
    };
  }
}
