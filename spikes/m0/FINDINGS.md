# M0 spike: findings

Date: 2026-10-27. Environment: Linux, 16 cores, 62 GB RAM, OpenJDK 25.0.4 (Debian), Maven 3.9.9, cel-java 0.15.0, Camel 4.23.0.

Verdict: **go**, with the conditions listed at the end. Nothing here blocks the design. Several points change details of the module metadata and the packaging.

## 1. CEL (cel-java) with typed events

Code: `cel/src/main/java/spike/CelSpike.java`.

| Variant | Typo in a field name | Type error (`string + int`) |
|---|---|---|
| Untyped `map<string, dyn>` | fails only at **runtime** | fails only at **runtime** |
| Typed (protobuf descriptors built at runtime) | **compile-time** error (`undefined field 'subjekt'`) | **compile-time** error (no matching overload) |

- The typed variant builds `praksis.Event` and `praksis.MailReceived` with `FileDescriptorProto` in code, the way the engine would from a module's metadata, and registers them with `addMessageTypes(...)`. Unknown fields and wrong types are rejected before a rule is ever activated, which is what the verifier needs.
- A free-form part works too: a `google.protobuf.Struct` field (`event.extras.priority == 'low'`) is accepted and unchecked, matching the planned hybrid data model. A `map<string, string>` field (`event.data.headers['List-Id']`) is also checked.
- Cost: first compile and plan of an expression took about 3.3 ms. Evaluation took 3.4 to 4.2 microseconds per call, including building the activation map.
- cel-java is **0.x** (0.15.0). The API may still change, so pin the version and keep it behind our own expression interface.

Not tested: custom functions, timestamps and durations in rules, expression cost limits, thread safety, error messages for end users.

## 2. JPMS layers

Code: `jpms/`. Modules: `api` (the stand-in capability API), `dummy`, `camelmod` (Camel wrapper) and `host` (the loader).

- Loading a directory of jars into its own `ModuleLayer` works: `ModuleFinder.of(dir)`, `Configuration.resolveAndBind(...)`, `ModuleLayer.defineModulesWithOneLoader(...)`, then `ServiceLoader.load(layer, Capability.class)` finds the providers.
- The wrapper module `praksis.camelmod` uses Camel's file component and the IMAP endpoint internally. The host sees only the `Capability` interface, and no Camel type leaves the module.
- `camel.file.list` listed a directory through Camel. `camel.mail.probe` resolved `MailEndpoint` and the jakarta.mail `IMAPSSLStore` inside the layer.

Friction found, in the order it appeared:
1. **Camel jars have no `module-info` and no `Automatic-Module-Name`.** Their module names are derived from the jar file names (`camel.api`, `camel.core.engine`, ...). That works with Maven's naming but is fragile if jars are renamed or repackaged. The wrapper's `requires` of automatic modules also produces compiler warnings.
2. **Named dependencies of automatic modules are not resolved on their own.** `slf4j-api` was missing at runtime (`NoClassDefFoundError`) until the wrapper declared `requires org.slf4j`. Automatic modules cannot declare `requires`, so every named dependency has to be required by the wrapper or added as a root.
3. **Service providers need `resolveAndBind`.** With plain `resolve` the angus-mail provider was not included.
4. **JDK modules are not pulled in either.** The Camel layer failed with `NoClassDefFoundError: java/sql/Timestamp`. `jdeps` reports that the Camel jars need `java.desktop`, `java.management`, `java.naming`, `java.rmi`, `java.security.sasl`, `java.sql` and `jdk.jfr` in addition to `java.base`. The boot layer must contain them, either through `--add-modules` at launch or baked into the image with `jlink "--add-options=--add-modules=..."`, which worked.

## 3. Footprint (jlink image, measured with `/usr/bin/time`)

| Case | Peak RSS | Wall time |
|---|---|---|
| Host plus the dummy layer | 58 MB | 0.06 s |
| Host plus Camel layer (two Camel contexts started, work done, closed), default G1 | 112 MB | 0.83 s |
| Same, SerialGC, `-Xmx128m`, C1 only | 96 MB | 0.79 s |
| CEL spike process alone (full JDK, no benchmark loop), SerialGC, `-Xmx128m`, C1 only | 97 MB | 0.4 s |

- The jlink image with 15 JDK modules (including `java.desktop` and `jdk.jfr`) is **87 MB** on disk, with a CDS archive.
- These numbers are **not additive**, because the JVM and the JDK classes are shared. An engine with CEL, Camel, SQLite and JGit loaded will be larger than any single row. A rough estimate is 150 to 250 MB resident, but that is **not measured**. Measure the real engine at M1, including idle memory over time.
- Not measured: long-running Camel consumers, SQLite, JGit, and whether dropping `java.desktop` shrinks the image.

## 4. Not covered by this spike
- A real IMAP connection (login, IDLE, TLS). Only class loading and endpoint creation were checked.
- Unloading a layer, and several Camel-based modules in separate layers (duplicate Camel copies in memory).
- Whether Camel needs the thread context class loader for other components. The file component worked without setting it, and the mail probe set it.
- Windows and macOS.

## Conditions and consequences for the design
1. **Typed events:** generate protobuf descriptors from the module metadata and register them with cel-java. This is confirmed to work.
2. **Module metadata needs one more field:** the JDK modules a module requires (for example `jdk-modules = ["java.sql", "java.naming"]`), so the runtime image or launcher can include them.
3. **Camel wrappers:** the friction is manageable. Pin Camel versions and jar names. If it becomes a problem, the fallback already decided is to shade Camel into our own wrapper jar with a proper `module-info`.
4. **cel-java:** pin the version and hide it behind our own expression interface.
5. **Footprint:** acceptable for a desktop daemon at this stage. Re-measure with the real engine at M1.

## Reproducing
```
# CEL
cd cel && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt && mvn -q compile
java -cp target/classes:$(cat cp.txt) spike.CelSpike

# JPMS and Camel
cd jpms && mvn -q -N install && mvn -q install
(cd camelmod && mvn -q dependency:copy-dependencies -DoutputDirectory=target/deps -DincludeScope=runtime)
mkdir -p run/dummy run/camel run/data && touch run/data/a.eml run/data/b.eml
cp dummy/target/m0-dummy-0.jar run/dummy/
cp camelmod/target/deps/*.jar run/camel/ && rm run/camel/m0-api-0.jar && cp camelmod/target/m0-camelmod-0.jar run/camel/
java --add-modules ALL-SYSTEM --module-path host/target/m0-host-0.jar:api/target/m0-api-0.jar \
  -m praksis.host/praksis.host.Host run/camel:praksis.camelmod -- dir=$PWD/run/data
```
