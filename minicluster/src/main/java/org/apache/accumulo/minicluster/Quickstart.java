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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

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
    // MAC's stop() lazily resolves a ServerContext, which in turn forces
    // AccumuloVFSClassLoader's static initializer to run. That initializer registers its own JVM
    // shutdown hook - which the JVM rejects with "Shutdown in progress" when triggered from
    // inside our own shutdown hook. The cascading ExceptionInInitializerError aborts MAC's stop
    // sequence before it gets to ZooKeeper, leaving an orphaned ZK child JVM. Pre-trigger the
    // class load now so the shutdown hook is registered while the JVM is still in normal state.
    Class.forName("org.apache.accumulo.start.classloader.vfs.AccumuloVFSClassLoader");

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
        .addShutdownHook(new Thread(() -> shutdown(cluster, dataDir), "quickstart-shutdown"));

    cluster.start();

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
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(false);
      socket.bind(new InetSocketAddress(port));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static void shutdown(MiniAccumuloCluster cluster, Path dataDir) {
    try {
      cluster.stop();
    } catch (IOException | InterruptedException e) {
      log.error("Error stopping Accumulo quickstart cluster", e);
    }
    try {
      FileUtils.deleteDirectory(new File(dataDir.toString()));
    } catch (IOException e) {
      log.warn("Failed to clean up quickstart data directory {}", dataDir, e);
    }
  }
}
