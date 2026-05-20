/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.minicluster;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ProcessHandle;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.accumulo.miniclusterImpl.MiniAccumuloClusterImpl;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Foreground orchestrator for {@code bin/accumulo quickstart}. Boots an ephemeral
 * {@link MiniAccumuloCluster} with quickstart defaults, waits for it to accept traffic, prints the
 * ready banner, and blocks until SIGTERM or SIGINT - at which point a JVM shutdown hook stops the
 * cluster and removes the temporary data directory.
 *
 * <p>
 * The 30-second-per-child-JVM shutdown timeout required by the spec is enforced by
 * {@code MiniAccumuloClusterControl.stop(...)}, which always uses
 * {@code stopProcessesWithTimeout(..., 30, TimeUnit.SECONDS)} when terminating each service group.
 */
public class Quickstart {

  private static final Logger log = LoggerFactory.getLogger(Quickstart.class);

  private Quickstart() {}

  @SuppressFBWarnings(value = {"PATH_TRAVERSAL_IN", "DM_EXIT", "UNENCRYPTED_SERVER_SOCKET"},
      justification = "quickstart is a user-facing local CLI; preflight ServerSockets are bound "
          + "and immediately closed only to test port availability")
  public static void main(String[] args) throws Exception {
    // MAC's stop() lazily resolves a ServerContext, which triggers the static initializers of two
    // distinct classes that each try to register their own JVM shutdown hook. The JVM rejects late
    // hook registration during an in-progress shutdown ("Shutdown in progress"), and the resulting
    // ExceptionInInitializerError aborts MAC's stop sequence partway through - leaving orphaned
    // child JVMs. Pre-load both classes during normal startup so their hooks are already
    // registered by the time our own shutdown hook fires.
    //
    // - AccumuloVFSClassLoader is reached via ConfigurationTypeHelper.getClassInstance during
    // VolumeManagerImpl init.
    // - org.apache.hadoop.util.ShutdownHookManager is reached via FileSystem$Cache.getInternal
    // when VolumeImpl resolves its Hadoop filesystem.
    Class.forName("org.apache.accumulo.start.classloader.vfs.AccumuloVFSClassLoader");
    Class.forName("org.apache.hadoop.util.ShutdownHookManager");

    QuickstartConfig.ParseResult parseResult = QuickstartConfig.parse(args, System.getenv());
    switch (parseResult.kind()) {
      case HELP:
        System.out.print(parseResult.message());
        System.out.flush();
        System.exit(0);
        return;
      case ERROR:
        System.err.println(parseResult.message());
        System.exit(1);
        return;
      case SUCCESS:
      default:
        // fall through
        break;
    }
    QuickstartConfig config = parseResult.config();

    List<PortToCheck> portsToCheck = new ArrayList<>();
    portsToCheck.add(new PortToCheck("ZooKeeper", config.zooKeeperPort(), "--zk-port"));
    portsToCheck.add(new PortToCheck("monitor", config.monitorPort(), "--monitor-port"));
    portsToCheck.add(new PortToCheck("tablet server", config.tabletServerPort(), "--tserver-port"));
    portsToCheck.add(new PortToCheck("manager", config.managerPort(), "--manager-port"));
    String conflict = firstUnavailablePort(portsToCheck);
    if (conflict != null) {
      System.err.println(conflict);
      System.exit(1);
    }

    Path dataDir = Files.createTempDirectory("accumulo-quickstart-");
    MiniAccumuloConfig macConfig = config.toMiniAccumuloConfig(dataDir.toFile());
    MiniAccumuloCluster cluster = new MiniAccumuloCluster(macConfig);

    // MAC's embedded ZK hardcodes clientPortAddress=127.0.0.1 in the generated zoo.cfg, which is
    // independent of Accumulo's rpc.bind.addr - the Property only controls Accumulo's own services
    // (manager, tserver, monitor, gc, compactor). If the user requested a non-loopback bind via
    // ACCUMULO_BIND_HOST (the Docker entrypoint sets it to 0.0.0.0 so port-forwarded clients can
    // reach ZK), rewrite the zoo.cfg that MAC just wrote, in place, before cluster.start() spawns
    // the ZK subprocess. MAC writes zoo.cfg synchronously in its constructor so the file exists by
    // the time we get here.
    if (!config.bindAddress().isEmpty()) {
      overrideZooKeeperClientPortAddress(macConfig, config.bindAddress());
    }

    Runtime.getRuntime()
        .addShutdownHook(new Thread(() -> shutdown(dataDir), "quickstart-shutdown"));

    cluster.start();

    // MAC.start() registers its own shutdown hook that calls MAC.stop(). MAC.stop() drives the
    // ZooZap-against-dead-ZK slow path whenever the user Ctrl-Cs (SIGINT goes to the whole
    // foreground process group, so children die before MAC's hook even runs - and then MAC tries
    // to talk to a dead ZK at 4s per ZK op across multiple zap calls). MAC.stop()'s very first
    // line is `if (executor == null) return;` - it's both the idempotency guard for repeated stop
    // calls and an easy off-switch. Null it now so MAC's auto-hook fires fast on shutdown and we
    // handle child-process cleanup ourselves via ProcessHandle, which doesn't depend on ZK.
    try {
      neutralizeMacShutdownHook(cluster);
    } catch (ReflectiveOperationException e) {
      log.warn("Could not neutralize MAC's auto-registered shutdown hook; Ctrl-C will be slow", e);
    }

    // MiniAccumuloClusterImpl.start() spins up ZooKeeper, tablet servers, manager, and GC -
    // but not the monitor. The quickstart banner advertises a monitor URL, so we have to start
    // it ourselves. Compactor and scan-server JVMs are not started here because Accumulo 2.1's
    // external compaction requires queue/coordinator wiring; the --compactors / --scan-servers
    // flags are accepted at the CLI but currently no-ops at runtime (tracked in #12).
    try {
      startMonitor(cluster);
    } catch (ReflectiveOperationException | IOException e) {
      log.warn("Could not start Monitor JVM; the URL in the banner will not be reachable", e);
    }

    QuickstartReadinessCheck readinessCheck =
        new QuickstartReadinessCheck(config.readinessTimeout());
    QuickstartReadinessCheck.Result result =
        readinessCheck.awaitReady(cluster, "root", config.rootPassword());
    if (!result.success()) {
      System.err.println("Quickstart failed to become ready: " + result.detail());
      System.exit(1);
    }

    String banner =
        QuickstartBanner.format(new QuickstartBanner.BannerInputs(cluster.getInstanceName(),
            "http://localhost:" + config.monitorPort(), cluster.getZooKeepers(),
            config.rootPassword(), dataDir.toAbsolutePath().toString(), true));
    System.out.print(banner);
    System.out.flush();

    new CountDownLatch(1).await();
  }

  /**
   * @return null if all ports are available, otherwise a human-readable error message naming the
   *         first conflict and the override flag the user can pass to shift it.
   */
  static String firstUnavailablePort(List<PortToCheck> ports) {
    for (PortToCheck p : ports) {
      if (!isPortAvailable(p.port)) {
        return "Port " + p.port + " (" + p.name + ") is already in use. "
            + "Free the port and retry, or override with " + p.overrideFlag + " or --port-base.";
      }
    }
    return null;
  }

  /** A port we will preflight-check, with the override flag we point users at on conflict. */
  static final class PortToCheck {
    final String name;
    final int port;
    final String overrideFlag;

    PortToCheck(String name, int port, String overrideFlag) {
      this.name = Objects.requireNonNull(name, "name");
      this.port = port;
      this.overrideFlag = Objects.requireNonNull(overrideFlag, "overrideFlag");
    }
  }

  @SuppressFBWarnings(value = "UNENCRYPTED_SERVER_SOCKET",
      justification = "socket is bound and immediately closed only to test port availability")
  private static boolean isPortAvailable(int port) {
    // Use the simple ServerSocket(port) constructor (SO_REUSEADDR defaults to true on Linux for
    // server sockets). The previous setReuseAddress(false)+bind() pattern was stricter than what
    // ZK itself uses when binding - producing false-positive "port in use" errors on the common
    // Ctrl-C-then-restart cycle, when the prior ZK socket is still in TIME_WAIT.
    try (ServerSocket socket = new ServerSocket(port)) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN",
      justification = "zoo.cfg lives in MAC's own conf dir under a JVM-created temp directory, "
          + "not a user-supplied path; quickstart is a user-facing local CLI")
  private static void overrideZooKeeperClientPortAddress(MiniAccumuloConfig macConfig,
      String bindAddress) throws IOException {
    File zooCfg = new File(macConfig.getImpl().getConfDir(), "zoo.cfg");
    if (!zooCfg.exists()) {
      log.warn("zoo.cfg not found at {}; skipping clientPortAddress override", zooCfg);
      return;
    }
    Properties props = new Properties();
    try (FileInputStream in = new FileInputStream(zooCfg)) {
      props.load(in);
    }
    props.setProperty("clientPortAddress", bindAddress);
    try (FileOutputStream out = new FileOutputStream(zooCfg)) {
      props.store(out, null);
    }
  }

  @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN",
      justification = "dataDir is a JVM-created temp directory, not a user-supplied path; "
          + "quickstart is a user-facing local CLI")
  private static void shutdown(Path dataDir) {
    // Walk our own child processes (MAC spawns one JVM per service via ProcessBuilder, so they're
    // direct children of this JVM) and SIGKILL anything still alive. In the Ctrl-C case the
    // SIGINT already propagated to them through the foreground process group and they're dying or
    // dead - destroyForcibly is a no-op on a dead process. In the kill -TERM case (signal only to
    // the parent), this is what actually stops them. Either way, no ZK round-trip required.
    killChildJvms();
    try {
      FileUtils.deleteDirectory(new File(dataDir.toString()));
    } catch (IOException e) {
      log.warn("Failed to clean up quickstart data directory {}", dataDir, e);
    }
  }

  private static void killChildJvms() {
    long parentPid = ProcessHandle.current().pid();
    List<ProcessHandle> children = ProcessHandle.allProcesses()
        .filter(p -> p.parent().map(parent -> parent.pid() == parentPid).orElse(false))
        .collect(Collectors.toList());
    for (ProcessHandle child : children) {
      child.destroyForcibly();
    }
    for (ProcessHandle child : children) {
      try {
        child.onExit().get(5, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        log.warn("Child JVM PID {} did not exit within 5 seconds", child.pid());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        log.warn("Error waiting for child JVM PID {} to exit", child.pid(), e);
      }
    }
  }

  @SuppressFBWarnings(value = "REFLF_REFLECTION_MAY_INCREASE_ACCESSIBILITY_OF_FIELD",
      justification = "intentional - MAC.stop()'s first check is `if (executor == null) return;`, "
          + "nulling it neutralizes MAC's auto-registered shutdown hook so our own cleanup runs "
          + "unimpeded by the ZK-dependent stop sequence")
  private static void neutralizeMacShutdownHook(MiniAccumuloCluster cluster)
      throws ReflectiveOperationException {
    java.lang.reflect.Field implField = MiniAccumuloCluster.class.getDeclaredField("impl");
    implField.setAccessible(true);
    Object impl = implField.get(cluster);
    java.lang.reflect.Field executorField = impl.getClass().getDeclaredField("executor");
    executorField.setAccessible(true);
    executorField.set(impl, null);
  }

  /**
   * Reach into the public {@link MiniAccumuloCluster} for its private {@code impl} field, then ask
   * the cluster control to spawn the Monitor JVM. {@code MiniAccumuloClusterImpl.start()} starts ZK
   * / tservers / manager / GC but not the monitor, so the banner's monitor URL would point to a
   * non-running service if we did not do this here.
   *
   * <p>
   * We cast to the impl type and call its methods directly rather than using
   * {@code impl.getClass().getMethod(...)} reflection - the latter triggers
   * {@code getDeclaredMethods0}, which eagerly resolves every method signature on
   * {@link MiniAccumuloClusterImpl} including the {@code getMiniDfs()} return type
   * {@code MiniDFSCluster}. That class is test-scoped (not bundled in the quickstart libs) and the
   * resulting {@code NoClassDefFoundError} would abort startup.
   */
  @SuppressFBWarnings(value = "REFLF_REFLECTION_MAY_INCREASE_ACCESSIBILITY_OF_FIELD",
      justification = "intentional - the public MiniAccumuloCluster wraps a private impl; cast "
          + "after reflection to avoid triggering classloading of test-only HDFS classes")
  private static void startMonitor(MiniAccumuloCluster cluster)
      throws ReflectiveOperationException, IOException {
    java.lang.reflect.Field implField = MiniAccumuloCluster.class.getDeclaredField("impl");
    implField.setAccessible(true);
    MiniAccumuloClusterImpl impl = (MiniAccumuloClusterImpl) implField.get(cluster);
    impl.getClusterControl().start(ServerType.MONITOR, null);
  }
}
