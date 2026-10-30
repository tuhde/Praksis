// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EventSource;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.expression.Expressions;
import it.uhde.praksis.ruleset.ModuleCatalog;
import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ResolutionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads modules from a directory. Each subdirectory is one module: a {@code module.toml}, the
 * module's jar and the jars it needs. Every module gets its own {@link ModuleLayer}, so modules
 * cannot see each other's classes. There is no unloading, so removing a module takes a restart.
 *
 * <p>Modules are trusted code. A module is only loaded if its directory hash matches the lockfile,
 * so a swapped jar is detected; {@link #lock} records the current state.
 */
public final class ModuleLoader {
  private static final String EXPRESSION_MODULE = "it.uhde.praksis.expression.cel";

  private ModuleLoader() {}

  /**
   * Loads every module in the directory.
   *
   * @param modulesDir the directory with one subdirectory per module
   * @param lockfile the file with the accepted module hashes
   * @param settings the local settings of each module, by module name
   * @throws ModuleLoadException if a module is not in the lockfile, has changed since, needs JDK
   *     modules the runtime lacks, or does not provide what its {@code module.toml} declares
   */
  public static LoadedModules load(
      Path modulesDir, Path lockfile, Map<String, Map<String, Object>> settings)
      throws ModuleLoadException {
    return load(modulesDir, lockfile, settings, Set.of());
  }

  /**
   * Loads the enabled modules in the directory.
   *
   * @param enabled the names of the modules to load; empty means all. The others are not read
   *     beyond their name, checked against the lockfile or started: two modules that provide the
   *     same capability, e.g. two mail backends, can be installed side by side and one enabled.
   * @throws ModuleLoadException as the other overload, and if an enabled module is not there
   */
  public static LoadedModules load(
      Path modulesDir,
      Path lockfile,
      Map<String, Map<String, Object>> settings,
      Set<String> enabled)
      throws ModuleLoadException {
    return load(List.of(modulesDir), lockfile, settings, enabled);
  }

  /**
   * Loads the enabled modules of several directories, the first first. A module that is in an
   * earlier directory hides the one of the same name in a later one: what a person installed in the
   * home directory replaces what the runtime image ships.
   */
  public static LoadedModules load(
      List<Path> modulesDirs,
      Path lockfile,
      Map<String, Map<String, Object>> settings,
      Set<String> enabled)
      throws ModuleLoadException {
    Lockfile locked = Lockfile.read(lockfile);
    Set<String> found = new HashSet<>();
    List<ModuleDescriptor> descriptors = new ArrayList<>();
    Map<String, Capability> capabilities = new HashMap<>();
    List<EventSource> sources = new ArrayList<>();

    for (Path dir : moduleDirs(modulesDirs)) {
      ModuleDescriptorReader.Result read = ModuleDescriptorReader.read(dir.resolve("module.toml"));
      ModuleDescriptor descriptor = read.descriptor();
      String name = descriptor.name();
      found.add(name);
      if (!enabled.isEmpty() && !enabled.contains(name)) {
        continue;
      }
      String expected = locked.hashOf(name);
      if (expected == null) {
        throw new ModuleLoadException(
            "module '" + name + "' (" + dir + ") is not in the lockfile; accept it with 'lock'");
      }
      if (!expected.equals(Lockfile.hashDirectory(dir))) {
        throw new ModuleLoadException(
            "module '" + name + "' (" + dir + ") has changed since it was locked");
      }
      for (String jdk : descriptor.jdkModules()) {
        if (ModuleLayer.boot().findModule(jdk).isEmpty()) {
          throw new ModuleLoadException(
              "module '" + name + "' needs the JDK module '" + jdk + "', which the runtime lacks");
        }
      }
      ModuleLayer layer = layer(dir, read.moduleName(), name);
      Map<String, Object> moduleSettings = settings.getOrDefault(name, Map.of());

      Map<String, Capability> mine = new HashMap<>();
      for (Capability c : providers(layer, Capability.class)) {
        configure(name, c::configure, moduleSettings);
        mine.put(c.name(), c);
      }
      Set<String> declared = new HashSet<>();
      for (CapabilityDescriptor c : descriptor.capabilities()) {
        declared.add(c.name());
        if (!mine.containsKey(c.name())) {
          throw new ModuleLoadException(
              "module '"
                  + name
                  + "' declares capability '"
                  + c.name()
                  + "' but does not provide it");
        }
      }
      for (String provided : mine.keySet()) {
        if (!declared.contains(provided)) {
          throw new ModuleLoadException(
              "module '"
                  + name
                  + "' provides capability '"
                  + provided
                  + "' that module.toml does not declare");
        }
      }
      List<EventSource> mySources = providers(layer, EventSource.class);
      Set<String> sourceNames = new HashSet<>();
      for (EventSource s : mySources) {
        configure(name, s::configure, moduleSettings);
        sourceNames.add(s.name());
      }
      if (!sourceNames.equals(new HashSet<>(descriptor.sources()))) {
        throw new ModuleLoadException(
            "module '"
                + name
                + "' provides sources "
                + sourceNames
                + " but module.toml declares "
                + descriptor.sources());
      }

      for (String c : mine.keySet()) {
        if (capabilities.putIfAbsent(c, mine.get(c)) != null) {
          throw new ModuleLoadException("capability '" + c + "' is provided by two modules");
        }
      }
      descriptors.add(descriptor);
      sources.addAll(mySources);
    }
    for (String name : enabled) {
      if (!found.contains(name)) {
        throw new ModuleLoadException("module '" + name + "' is enabled but not in " + modulesDirs);
      }
    }
    try {
      new ModuleCatalog(descriptors);
    } catch (IllegalArgumentException e) {
      throw new ModuleLoadException(e.getMessage(), e);
    }
    return new LoadedModules(descriptors, capabilities, sources);
  }

  /**
   * The names of the modules that would be loaded, read from their {@code module.toml} without
   * loading anything. This is what a rule or view that {@code requires_modules} is checked against.
   *
   * @param enabled the modules to load; empty means every module in the directory
   */
  public static Set<String> moduleNames(Path modulesDir, Set<String> enabled)
      throws ModuleLoadException {
    return moduleNames(List.of(modulesDir), enabled);
  }

  /** As {@link #moduleNames(Path, Set)}, for several directories. */
  public static Set<String> moduleNames(List<Path> modulesDirs, Set<String> enabled)
      throws ModuleLoadException {
    Set<String> names = new java.util.TreeSet<>();
    for (Path dir : moduleDirs(modulesDirs)) {
      String name = ModuleDescriptorReader.read(dir.resolve("module.toml")).descriptor().name();
      if (enabled.isEmpty() || enabled.contains(name)) {
        names.add(name);
      }
    }
    return names;
  }

  /** Records the current hash of every module directory, accepting new and changed modules. */
  public static void lock(Path modulesDir, Path lockfile) throws ModuleLoadException {
    lock(List.of(modulesDir), lockfile);
  }

  /** As {@link #lock(Path, Path)}, for several directories; a hidden module is not recorded. */
  public static void lock(List<Path> modulesDirs, Path lockfile) throws ModuleLoadException {
    Lockfile locked = Lockfile.read(lockfile);
    for (Path dir : moduleDirs(modulesDirs)) {
      String name = ModuleDescriptorReader.read(dir.resolve("module.toml")).descriptor().name();
      locked.put(name, Lockfile.hashDirectory(dir));
    }
    locked.write(lockfile);
  }

  /**
   * Loads the expression implementation from a directory with its jar, in a layer of its own. The
   * implementation is a shaded jar that cannot be part of the runtime image.
   */
  public static ExpressionCompiler loadExpressionCompiler(Path dir) throws ModuleLoadException {
    ModuleLayer layer = layer(dir, EXPRESSION_MODULE, "expression");
    try {
      return Expressions.compiler(layer);
    } catch (IllegalStateException e) {
      throw new ModuleLoadException("no expression compiler in " + dir, e);
    }
  }

  /** Every module directory of the roots; of two with the same module name the first one counts. */
  private static List<Path> moduleDirs(List<Path> roots) throws ModuleLoadException {
    Map<String, Path> byName = new java.util.LinkedHashMap<>();
    for (Path root : roots) {
      for (Path dir : subdirectories(root)) {
        String name = ModuleDescriptorReader.read(dir.resolve("module.toml")).descriptor().name();
        byName.putIfAbsent(name, dir);
      }
    }
    return List.copyOf(byName.values());
  }

  /** The directories directly below a root, sorted; none if the root is not there. */
  static List<Path> subdirectories(Path root) throws ModuleLoadException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> dirs = Files.list(root)) {
      return dirs.filter(Files::isDirectory)
          .filter(d -> !d.getFileName().toString().startsWith("."))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new ModuleLoadException("cannot list " + root + ": " + e.getMessage(), e);
    }
  }

  private static ModuleLayer layer(Path dir, String rootModule, String label)
      throws ModuleLoadException {
    try {
      ModuleFinder finder = ModuleFinder.of(dir);
      ModuleLayer parent = ModuleLayer.boot();
      Configuration configuration =
          parent.configuration().resolveAndBind(finder, ModuleFinder.of(), Set.of(rootModule));
      return ModuleLayer.defineModulesWithOneLoader(
              configuration, List.of(parent), ClassLoader.getPlatformClassLoader())
          .layer();
    } catch (FindException | ResolutionException e) {
      throw new ModuleLoadException(
          "cannot load module '" + label + "' from " + dir + ": " + e.getMessage(), e);
    }
  }

  private static <S> List<S> providers(ModuleLayer layer, Class<S> service) {
    List<S> out = new ArrayList<>();
    for (ServiceLoader.Provider<S> p : ServiceLoader.load(layer, service).stream().toList()) {
      if (p.type().getModule().getLayer() == layer) {
        out.add(p.get());
      }
    }
    return out;
  }

  @FunctionalInterface
  private interface Configure {
    void apply(Map<String, Object> settings) throws CapabilityException;
  }

  private static void configure(String module, Configure c, Map<String, Object> settings)
      throws ModuleLoadException {
    try {
      c.apply(settings);
    } catch (CapabilityException e) {
      throw new ModuleLoadException(
          "module '" + module + "' rejected its settings: " + e.getMessage(), e);
    }
  }
}
