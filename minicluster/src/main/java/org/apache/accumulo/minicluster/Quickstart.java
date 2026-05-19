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
import java.io.IOException;
import java.lang.ProcessHandle;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

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

    QuickstartConfig config = QuickstartConfig.defaults();

    Map<String,Integer> portsToCheck = new LinkedHashMap<>();
    portsToCheck.put("ZooKeeper", config.zooKeeperPort());
    portsToCheck.put("monitor", config.monitorPort());
    portsToCheck.put("tablet server", config.tabletServerPort());
    portsToCheck.put("manager", config.managerPort());
    String conflict = firstUnavailablePort(portsToCheck);
    if (conflict != null) {
      System.err.println(conflict);
      System.exit(1);
    }

    Path dataDir = Files.createTempDirectory("accumulo-quickstart-");
    MiniAccumuloConfig macConfig = config.toMiniAccumuloConfig(dataDir.toFile());
    MiniAccumuloCluster cluster = new MiniAccumuloCluster(macConfig);

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
      log.warn("Could not neutralize MAC's auto-registered shutdown hook; " + "Ctrl-C will be slow",
          e);
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
   *         first conflict.
   */
  static String firstUnavailablePort(Map<String,Integer> ports) {
    for (Map.Entry<String,Integer> e : ports.entrySet()) {
      String name = e.getKey();
      int port = e.getValue();
      if (!isPortAvailable(port)) {
        return "Port " + port + " (" + name + ") is already in use. "
            + "Free the port and retry, or use a port-override flag (coming in a future release).";
      }
    }
    return null;
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
}
