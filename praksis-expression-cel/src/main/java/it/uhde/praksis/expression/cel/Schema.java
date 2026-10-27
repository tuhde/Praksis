// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.expression.cel;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.expression.ExpressionException;
import it.uhde.praksis.expression.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The protobuf types for a {@link Scope}, built at runtime. cel-java type-checks expressions
 * against protobuf messages, so each variable of a scope becomes a message type.
 */
final class Schema {
  private static final String PACKAGE = "praksis";
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** The message types, or null where the scope has no such variable. */
  final Descriptor event;

  final Descriptor eventData;
  final Descriptor params;
  final Descriptor results;
  final Map<String, Descriptor> resultByAction;
  final List<Descriptor> all;

  private Schema(
      Descriptor event,
      Descriptor eventData,
      Descriptor params,
      Descriptor results,
      Map<String, Descriptor> resultByAction,
      List<Descriptor> all) {
    this.event = event;
    this.eventData = eventData;
    this.params = params;
    this.results = results;
    this.resultByAction = resultByAction;
    this.all = all;
  }

  static Schema build(Scope scope) throws ExpressionException {
    List<DescriptorProto> messages = new ArrayList<>();

    Optional<String> eventDataName = Optional.empty();
    if (scope.event().isPresent()) {
      messages.add(message("EventData", scope.event().get().fields(), List.of()));
      eventDataName = Optional.of("EventData");
      messages.add(
          DescriptorProto.newBuilder()
              .setName("Event")
              .addField(field("id", 1, Type.TYPE_STRING))
              .addField(field("type", 2, Type.TYPE_STRING))
              .addField(field("source", 3, Type.TYPE_STRING))
              .addField(messageField("time", 4, ".google.protobuf.Timestamp"))
              .addField(field("target", 5, Type.TYPE_STRING))
              .addField(field("key", 6, Type.TYPE_STRING))
              .addField(field("parent", 7, Type.TYPE_STRING))
              .addField(field("depth", 8, Type.TYPE_INT64))
              .addField(messageField("data", 9, "." + PACKAGE + ".EventData"))
              .addField(messageField("extras", 10, ".google.protobuf.Struct"))
              .build());
    }
    messages.add(message("Params", scope.params(), List.of()));

    List<DescriptorProto> resultMessages = new ArrayList<>();
    DescriptorProto.Builder results = DescriptorProto.newBuilder().setName("Results");
    int number = 1;
    for (var entry : scope.results().entrySet()) {
      String action = checkIdentifier(entry.getKey());
      resultMessages.add(message("Result_" + action, entry.getValue(), List.of()));
      results.addField(messageField(action, number++, "." + PACKAGE + ".Result_" + action));
    }
    messages.addAll(resultMessages);
    messages.add(results.build());

    FileDescriptorProto.Builder file =
        FileDescriptorProto.newBuilder()
            .setName("praksis_scope.proto")
            .setPackage(PACKAGE)
            .setSyntax("proto3")
            .addDependency("google/protobuf/struct.proto")
            .addDependency("google/protobuf/timestamp.proto");
    messages.forEach(file::addMessageType);

    FileDescriptor fd;
    try {
      fd =
          FileDescriptor.buildFrom(
              file.build(),
              new FileDescriptor[] {
                Struct.getDescriptor().getFile(), Timestamp.getDescriptor().getFile()
              });
    } catch (DescriptorValidationException e) {
      throw new ExpressionException("cannot build the expression types: " + e.getMessage(), e);
    }

    Map<String, Descriptor> byAction = new java.util.LinkedHashMap<>();
    for (String action : scope.results().keySet()) {
      byAction.put(action, fd.findMessageTypeByName("Result_" + action));
    }
    List<Descriptor> all = new ArrayList<>(fd.getMessageTypes());
    return new Schema(
        scope.event().isPresent() ? fd.findMessageTypeByName("Event") : null,
        eventDataName.isPresent() ? fd.findMessageTypeByName("EventData") : null,
        fd.findMessageTypeByName("Params"),
        fd.findMessageTypeByName("Results"),
        byAction,
        all);
  }

  private static DescriptorProto message(
      String name, List<FieldDescriptor> fields, List<DescriptorProto> nested)
      throws ExpressionException {
    DescriptorProto.Builder message = DescriptorProto.newBuilder().setName(name);
    int number = 1;
    for (FieldDescriptor f : fields) {
      checkIdentifier(f.name());
      message.addField(valueField(f.name(), number++, f.type()));
    }
    nested.forEach(message::addNestedType);
    return message.build();
  }

  private static String checkIdentifier(String name) throws ExpressionException {
    if (!IDENTIFIER.matcher(name).matches()) {
      throw new ExpressionException(
          "'" + name + "' cannot be used in expressions: not an identifier");
    }
    return name;
  }

  private static FieldDescriptorProto.Builder valueField(String name, int number, ValueType type) {
    return switch (type) {
      case STRING -> field(name, number, Type.TYPE_STRING);
      case INT -> field(name, number, Type.TYPE_INT64);
      case DOUBLE -> field(name, number, Type.TYPE_DOUBLE);
      case BOOL -> field(name, number, Type.TYPE_BOOL);
      case BYTES -> field(name, number, Type.TYPE_BYTES);
      case TIMESTAMP -> messageField(name, number, "." + Timestamp.getDescriptor().getFullName());
      case LIST -> messageField(name, number, "." + ListValue.getDescriptor().getFullName());
      case MAP -> messageField(name, number, "." + Struct.getDescriptor().getFullName());
    };
  }

  private static FieldDescriptorProto.Builder field(String name, int number, Type type) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_OPTIONAL)
        .setType(type);
  }

  private static FieldDescriptorProto.Builder messageField(
      String name, int number, String typeName) {
    return field(name, number, Type.TYPE_MESSAGE).setTypeName(typeName);
  }
}
