package spike;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.List;
import java.util.Map;

/** M0 spike: does cel-java type-check events whose types are built at runtime? */
public class CelSpike {

  static Descriptor eventDescriptor;
  static Descriptor mailDescriptor;

  public static void main(String[] args) throws Exception {
    System.out.println("== A: untyped event (map<string, dyn>)");
    untyped();
    System.out.println();
    System.out.println("== B: typed event, descriptors built at runtime");
    buildDescriptors();
    typed();
    System.out.println();
    if (args.length > 0 && args[0].equals("--no-timing")) return;
    System.out.println("== C: timing");
    timing();
  }

  // --- A: untyped ----------------------------------------------------------

  static void untyped() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("event", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .setResultType(SimpleType.BOOL)
            .build();
    CelRuntime runtime = CelRuntimeFactory.standardCelRuntimeBuilder().build();

    Map<String, Object> event =
        Map.of("data", Map.of("subject", "[news] hello", "headers", Map.of("List-Id", "x")));

    tryUntyped(compiler, runtime, event, "event.data.headers['List-Id'] != ''");
    tryUntyped(compiler, runtime, event, "event.data.subjekt.startsWith('[')"); // typo
    tryUntyped(compiler, runtime, event, "event.data.subject + 1 == 2"); // type error
  }

  static void tryUntyped(CelCompiler c, CelRuntime r, Map<String, Object> event, String expr) {
    try {
      CelAbstractSyntaxTree ast = c.compile(expr).getAst();
      Object result = r.createProgram(ast).eval(Map.of("event", event));
      System.out.println("  OK        " + expr + "  ->  " + result);
    } catch (CelValidationException e) {
      System.out.println("  COMPILE   " + expr + "  ->  " + firstLine(e.getMessage()));
    } catch (Exception e) {
      System.out.println("  RUNTIME   " + expr + "  ->  " + firstLine(e.getMessage()));
    }
  }

  // --- B: typed ------------------------------------------------------------

  /** Builds praksis.MailReceived and praksis.Event the way an engine would from a module descriptor. */
  static void buildDescriptors() throws Exception {
    DescriptorProto.Builder headersEntry =
        DescriptorProto.newBuilder()
            .setName("HeadersEntry")
            .setOptions(MessageOptions.newBuilder().setMapEntry(true))
            .addField(field("key", 1, Type.TYPE_STRING))
            .addField(field("value", 2, Type.TYPE_STRING));

    DescriptorProto mail =
        DescriptorProto.newBuilder()
            .setName("MailReceived")
            .addNestedType(headersEntry)
            .addField(field("message", 1, Type.TYPE_STRING))
            .addField(field("from", 2, Type.TYPE_STRING))
            .addField(field("subject", 3, Type.TYPE_STRING))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("headers")
                    .setNumber(4)
                    .setLabel(Label.LABEL_REPEATED)
                    .setType(Type.TYPE_MESSAGE)
                    .setTypeName(".praksis.MailReceived.HeadersEntry"))
            .build();

    DescriptorProto event =
        DescriptorProto.newBuilder()
            .setName("Event")
            .addField(field("id", 1, Type.TYPE_STRING))
            .addField(field("type", 2, Type.TYPE_STRING))
            .addField(field("target", 3, Type.TYPE_STRING))
            .addField(messageField("data", 4, ".praksis.MailReceived"))
            .addField(messageField("extras", 5, ".google.protobuf.Struct"))
            .build();

    FileDescriptorProto file =
        FileDescriptorProto.newBuilder()
            .setName("praksis_mail.proto")
            .setPackage("praksis")
            .setSyntax("proto3")
            .addDependency("google/protobuf/struct.proto")
            .addMessageType(mail)
            .addMessageType(event)
            .build();

    FileDescriptor fd =
        FileDescriptor.buildFrom(file, new FileDescriptor[] {Struct.getDescriptor().getFile()});
    mailDescriptor = fd.findMessageTypeByName("MailReceived");
    eventDescriptor = fd.findMessageTypeByName("Event");
    System.out.println("  built " + eventDescriptor.getFullName() + " and " + mailDescriptor.getFullName());
  }

  static FieldDescriptorProto.Builder field(String name, int number, Type type) {
    return FieldDescriptorProto.newBuilder()
        .setName(name)
        .setNumber(number)
        .setLabel(Label.LABEL_OPTIONAL)
        .setType(type);
  }

  static FieldDescriptorProto.Builder messageField(String name, int number, String typeName) {
    return field(name, number, Type.TYPE_MESSAGE).setTypeName(typeName);
  }

  static DynamicMessage sampleEvent() {
    DynamicMessage mail =
        DynamicMessage.newBuilder(mailDescriptor)
            .setField(mailDescriptor.findFieldByName("message"), "msg-1")
            .setField(mailDescriptor.findFieldByName("from"), "news@example.org")
            .setField(mailDescriptor.findFieldByName("subject"), "[news] hello")
            .addRepeatedField(
                mailDescriptor.findFieldByName("headers"),
                headerEntry("List-Id", "news.example.org"))
            .build();
    Struct extras =
        Struct.newBuilder()
            .putFields("priority", Value.newBuilder().setStringValue("low").build())
            .build();
    return DynamicMessage.newBuilder(eventDescriptor)
        .setField(eventDescriptor.findFieldByName("id"), "e1")
        .setField(eventDescriptor.findFieldByName("type"), "mail.received")
        .setField(eventDescriptor.findFieldByName("target"), "mailbox:work")
        .setField(eventDescriptor.findFieldByName("data"), mail)
        .setField(eventDescriptor.findFieldByName("extras"), extras)
        .build();
  }

  static DynamicMessage headerEntry(String k, String v) {
    Descriptor entry = mailDescriptor.findNestedTypeByName("HeadersEntry");
    return DynamicMessage.newBuilder(entry)
        .setField(entry.findFieldByName("key"), k)
        .setField(entry.findFieldByName("value"), v)
        .build();
  }

  static CelCompiler typedCompiler() {
    return CelCompilerFactory.standardCelCompilerBuilder()
        .addMessageTypes(eventDescriptor, mailDescriptor)
        .addVar("event", StructTypeReference.create("praksis.Event"))
        .setResultType(SimpleType.BOOL)
        .build();
  }

  static CelRuntime typedRuntime() {
    return CelRuntimeFactory.standardCelRuntimeBuilder()
        .addMessageTypes(eventDescriptor, mailDescriptor)
        .build();
  }

  static void typed() {
    CelCompiler compiler = typedCompiler();
    CelRuntime runtime = typedRuntime();
    DynamicMessage event = sampleEvent();

    List<String> exprs =
        List.of(
            "event.data.headers['List-Id'] != ''",
            "event.data.subject.startsWith('[') && event.type == 'mail.received'",
            "event.extras.priority == 'low'", // free-form part
            "event.data.subjekt.startsWith('[')", // typo: expect COMPILE error
            "event.data.subject + 1 == 2", // type error: expect COMPILE error
            "event.data.headers.size() > 0",
            "event.nonexistent == 1"); // unknown field: expect COMPILE error
    for (String expr : exprs) {
      try {
        CelAbstractSyntaxTree ast = compiler.compile(expr).getAst();
        Object result = runtime.createProgram(ast).eval(Map.of("event", event));
        System.out.println("  OK        " + expr + "  ->  " + result);
      } catch (CelValidationException e) {
        System.out.println("  COMPILE   " + expr + "  ->  " + firstLine(e.getMessage()));
      } catch (Exception e) {
        System.out.println("  RUNTIME   " + expr + "  ->  " + firstLine(e.getMessage()));
      }
    }
  }

  // --- C: timing -------------------------------------------------------------

  static void timing() throws Exception {
    CelCompiler compiler = typedCompiler();
    CelRuntime runtime = typedRuntime();
    DynamicMessage event = sampleEvent();
    String expr = "event.data.subject.startsWith('[') && event.data.headers['List-Id'] != ''";

    long t0 = System.nanoTime();
    CelAbstractSyntaxTree ast = compiler.compile(expr).getAst();
    CelRuntime.Program program = runtime.createProgram(ast);
    long t1 = System.nanoTime();
    System.out.printf("  first compile+plan: %.1f ms%n", (t1 - t0) / 1e6);

    for (int i = 0; i < 20000; i++) program.eval(Map.of("event", event)); // warm up
    int n = 200000;
    long t2 = System.nanoTime();
    for (int i = 0; i < n; i++) program.eval(Map.of("event", event));
    long t3 = System.nanoTime();
    System.out.printf("  eval: %.2f us/op over %d ops%n", (t3 - t2) / 1e3 / n, n);
  }

  static String firstLine(String s) {
    if (s == null) return "(no message)";
    int i = s.indexOf('\n');
    return i < 0 ? s : s.substring(0, i);
  }
}
