// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.expression.cel;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.ListValue;
import com.google.protobuf.Message;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.expression.ExpressionException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts between plain Java values and the protobuf values cel-java works with. */
final class Values {
  private Values() {}

  /**
   * Builds a message of the given type from values. Entries without a matching field are returned
   * as the second element of the result: the free-form rest.
   */
  static Message fill(Descriptor type, List<FieldDescriptor> fields, Map<String, Object> values)
      throws ExpressionException {
    DynamicMessage.Builder message = DynamicMessage.newBuilder(type);
    for (FieldDescriptor f : fields) {
      Object value = values.get(f.name());
      if (value == null) {
        continue;
      }
      var protoField = type.findFieldByName(f.name());
      try {
        message.setField(protoField, toProto(f.type(), value));
      } catch (ClassCastException | IllegalArgumentException e) {
        throw new ExpressionException(
            "value of '" + f.name() + "' is not a " + f.type() + ": " + value, e);
      }
    }
    return message.build();
  }

  /** The entries of values that no field in fields describes. */
  static Map<String, Object> rest(List<FieldDescriptor> fields, Map<String, Object> values) {
    Map<String, Object> rest = new LinkedHashMap<>(values);
    fields.forEach(f -> rest.remove(f.name()));
    return rest;
  }

  private static Object toProto(ValueType type, Object value) throws ExpressionException {
    return switch (type) {
      case STRING -> (String) value;
      case INT -> ((Number) value).longValue();
      case DOUBLE -> ((Number) value).doubleValue();
      case BOOL -> (Boolean) value;
      case BYTES -> ByteString.copyFrom((byte[]) value);
      case TIMESTAMP -> timestamp((Instant) value);
      case LIST -> toValue(value).getListValue();
      case MAP -> toValue(value).getStructValue();
    };
  }

  static Timestamp timestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  /** Converts any plain value to a protobuf {@link Value} (the untyped form). */
  static Value toValue(Object o) throws ExpressionException {
    Value.Builder v = Value.newBuilder();
    switch (o) {
      case null -> v.setNullValue(NullValue.NULL_VALUE);
      case String s -> v.setStringValue(s);
      case Boolean b -> v.setBoolValue(b);
      case Number n -> v.setNumberValue(n.doubleValue());
      case Instant i -> v.setStringValue(i.toString());
      case byte[] b -> v.setStringValue(java.util.Base64.getEncoder().encodeToString(b));
      case Map<?, ?> m -> {
        Struct.Builder s = Struct.newBuilder();
        for (var e : m.entrySet()) {
          s.putFields(String.valueOf(e.getKey()), toValue(e.getValue()));
        }
        v.setStructValue(s);
      }
      case List<?> l -> {
        ListValue.Builder lv = ListValue.newBuilder();
        for (Object item : l) {
          lv.addValues(toValue(item));
        }
        v.setListValue(lv);
      }
      default -> throw new ExpressionException("unsupported value type " + o.getClass().getName());
    }
    return v.build();
  }

  static Struct toStruct(Map<String, Object> values) throws ExpressionException {
    return toValue(values).getStructValue();
  }

  /** Converts a value produced by cel-java to the plain Java types of {@link ValueType}. */
  static Object fromCel(Object o) throws ExpressionException {
    return switch (o) {
      case null -> null;
      case String s -> s;
      case Long l -> l;
      case Double d -> d;
      case Boolean b -> b;
      case Instant i -> i;
      case ByteString b -> b.toByteArray();
      case NullValue n -> null;
      case Timestamp t -> Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
      case Value v -> fromValue(v);
      case Struct s -> fromValue(Value.newBuilder().setStructValue(s).build());
      case ListValue l -> fromValue(Value.newBuilder().setListValue(l).build());
      case Message m -> fromMessage(m);
      case Map<?, ?> m -> {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
          out.put(String.valueOf(e.getKey()), fromCel(e.getValue()));
        }
        yield out;
      }
      case List<?> l -> {
        List<Object> out = new ArrayList<>();
        for (Object item : l) {
          out.add(fromCel(item));
        }
        yield out;
      }
      default -> {
        // cel-java's own types (null, unsigned integers) are not part of our API.
        String name = o.getClass().getName();
        if (name.equals("dev.cel.common.values.NullValue")) {
          yield null;
        }
        if (o instanceof Number n) {
          yield n.longValue();
        }
        throw new ExpressionException("unsupported result type " + name);
      }
    };
  }

  private static Object fromValue(Value v) throws ExpressionException {
    return switch (v.getKindCase()) {
      case NULL_VALUE, KIND_NOT_SET -> null;
      case NUMBER_VALUE -> v.getNumberValue();
      case STRING_VALUE -> v.getStringValue();
      case BOOL_VALUE -> v.getBoolValue();
      case STRUCT_VALUE -> {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : v.getStructValue().getFieldsMap().entrySet()) {
          out.put(e.getKey(), fromValue(e.getValue()));
        }
        yield out;
      }
      case LIST_VALUE -> {
        List<Object> out = new ArrayList<>();
        for (Value item : v.getListValue().getValuesList()) {
          out.add(fromValue(item));
        }
        yield out;
      }
    };
  }

  private static Map<String, Object> fromMessage(Message m) throws ExpressionException {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var f : m.getDescriptorForType().getFields()) {
      Object value = m.getField(f);
      if (f.isRepeated()) {
        List<Object> list = new ArrayList<>();
        for (Object item : (List<?>) value) {
          list.add(fromCel(item));
        }
        out.put(f.getName(), list);
      } else if (f.getJavaType() == JavaType.MESSAGE && !m.hasField(f)) {
        out.put(f.getName(), null);
      } else {
        out.put(f.getName(), fromCel(value));
      }
    }
    return out;
  }
}
