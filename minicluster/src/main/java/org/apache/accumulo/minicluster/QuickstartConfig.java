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
import java.time.Duration;
import java.util.Objects;

import org.apache.accumulo.core.conf.Property;

/**
 * Immutable value object holding the user-facing configuration for {@link Quickstart}. Translates
 * to a fully-realized {@link MiniAccumuloConfig} via {@link #toMiniAccumuloConfig(File)}.
 *
 * <p>
 * Phase 1 / Slice 1 exposes default values only via {@link #defaults()}. Slice 2 will add CLI/env
 * parsing that produces alternate instances of this class.
 */
public final class QuickstartConfig {

  public static final String DEFAULT_INSTANCE_NAME = "quickstart";
  public static final String DEFAULT_ROOT_PASSWORD = "secret";
  public static final int DEFAULT_ZOOKEEPER_PORT = 2181;
  public static final int DEFAULT_MONITOR_PORT = 9995;
  public static final int DEFAULT_MANAGER_PORT = 9999;
  public static final int DEFAULT_TABLET_SERVER_PORT = 9997;
  public static final int DEFAULT_NUM_TABLET_SERVERS = 1;
  public static final int DEFAULT_NUM_SCAN_SERVERS = 0;
  public static final int DEFAULT_NUM_COMPACTORS = 1;
  public static final int DEFAULT_HEAP_MB = 256;
  public static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofSeconds(60);

  private final String instanceName;
  private final String rootPassword;
  private final int zooKeeperPort;
  private final int monitorPort;
  private final int managerPort;
  private final int tabletServerPort;
  private final int numTabletServers;
  private final int numScanServers;
  private final int numCompactors;
  private final int heapMb;
  private final Duration readinessTimeout;

  public QuickstartConfig(String instanceName, String rootPassword, int zooKeeperPort,
      int monitorPort, int managerPort, int tabletServerPort, int numTabletServers,
      int numScanServers, int numCompactors, int heapMb, Duration readinessTimeout) {
    Objects.requireNonNull(instanceName, "instanceName");
    Objects.requireNonNull(rootPassword, "rootPassword");
    Objects.requireNonNull(readinessTimeout, "readinessTimeout");
    if (instanceName.isBlank()) {
      throw new IllegalArgumentException("instanceName must not be blank");
    }
    if (rootPassword.isEmpty()) {
      throw new IllegalArgumentException("rootPassword must not be empty");
    }
    requireValidPort(zooKeeperPort, "zooKeeperPort");
    requireValidPort(monitorPort, "monitorPort");
    requireValidPort(managerPort, "managerPort");
    requireValidPort(tabletServerPort, "tabletServerPort");
    if (numTabletServers < 1) {
      throw new IllegalArgumentException("numTabletServers must be >= 1, got " + numTabletServers);
    }
    if (numScanServers < 0) {
      throw new IllegalArgumentException("numScanServers must be >= 0, got " + numScanServers);
    }
    if (numCompactors < 1) {
      throw new IllegalArgumentException("numCompactors must be >= 1, got " + numCompactors);
    }
    if (heapMb < 1) {
      throw new IllegalArgumentException("heapMb must be >= 1, got " + heapMb);
    }
    if (readinessTimeout.isNegative() || readinessTimeout.isZero()) {
      throw new IllegalArgumentException(
          "readinessTimeout must be positive, got " + readinessTimeout);
    }
    this.instanceName = instanceName;
    this.rootPassword = rootPassword;
    this.zooKeeperPort = zooKeeperPort;
    this.monitorPort = monitorPort;
    this.managerPort = managerPort;
    this.tabletServerPort = tabletServerPort;
    this.numTabletServers = numTabletServers;
    this.numScanServers = numScanServers;
    this.numCompactors = numCompactors;
    this.heapMb = heapMb;
    this.readinessTimeout = readinessTimeout;
  }

  private static void requireValidPort(int port, String name) {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(name + " must be in [1, 65535], got " + port);
    }
  }

  /**
   * @return the canonical quickstart configuration: 1 manager / 1 tserver / 1 compactor / 0 scan
   *         servers, ports 2181/9995/9997/9999, 256 MB heap, root password {@code secret}.
   */
  public static QuickstartConfig defaults() {
    return new QuickstartConfig(DEFAULT_INSTANCE_NAME, DEFAULT_ROOT_PASSWORD,
        DEFAULT_ZOOKEEPER_PORT, DEFAULT_MONITOR_PORT, DEFAULT_MANAGER_PORT,
        DEFAULT_TABLET_SERVER_PORT, DEFAULT_NUM_TABLET_SERVERS, DEFAULT_NUM_SCAN_SERVERS,
        DEFAULT_NUM_COMPACTORS, DEFAULT_HEAP_MB, DEFAULT_READINESS_TIMEOUT);
  }

  public String instanceName() {
    return instanceName;
  }

  public String rootPassword() {
    return rootPassword;
  }

  public int zooKeeperPort() {
    return zooKeeperPort;
  }

  public int monitorPort() {
    return monitorPort;
  }

  public int managerPort() {
    return managerPort;
  }

  public int tabletServerPort() {
    return tabletServerPort;
  }

  public int numTabletServers() {
    return numTabletServers;
  }

  public int numScanServers() {
    return numScanServers;
  }

  public int numCompactors() {
    return numCompactors;
  }

  public int heapMb() {
    return heapMb;
  }

  public Duration readinessTimeout() {
    return readinessTimeout;
  }

  /**
   * Translate this configuration into a fully-realized {@link MiniAccumuloConfig} pointed at
   * {@code dir}. The caller is responsible for providing an empty/nonexistent directory and for the
   * lifecycle of the resulting cluster.
   *
   * <p>
   * Most settings flow through the public {@link MiniAccumuloConfig} surface; impl-only operations
   * (per-property overrides, compactor count) drop down via {@code getImpl()}, mirroring the
   * pattern used by {@code MiniAccumuloRunner}.
   */
  public MiniAccumuloConfig toMiniAccumuloConfig(File dir) {
    Objects.requireNonNull(dir, "dir");
    MiniAccumuloConfig cfg = new MiniAccumuloConfig(dir, rootPassword);
    cfg.setInstanceName(instanceName);
    cfg.setZooKeeperPort(zooKeeperPort);
    cfg.setDefaultMemory(heapMb, MemoryUnit.MEGABYTE);
    cfg.setNumTservers(numTabletServers);
    cfg.setNumScanServers(numScanServers);

    cfg.getImpl().setProperty(Property.MANAGER_CLIENTPORT, Integer.toString(managerPort));
    cfg.getImpl().setProperty(Property.TSERV_CLIENTPORT, Integer.toString(tabletServerPort));
    cfg.getImpl().setProperty(Property.MONITOR_PORT, Integer.toString(monitorPort));
    cfg.getImpl().setNumCompactors(numCompactors);

    // Shorten the ZooKeeper session timeout so an interactive Ctrl-C - which sends SIGINT to the
    // whole foreground process group, killing ZK before MAC's shutdown sequence can ZooZap server
    // locks against it - hits its eventual "ZK unreachable" failure in a few seconds rather than
    // the default 60s-per-ZK-op (which leaves the user staring at an apparently-frozen terminal
    // for ~2 minutes). Local-only quickstart cluster, so the production-style 30s timeout buys
    // us nothing here.
    cfg.getImpl().setProperty(Property.INSTANCE_ZK_TIMEOUT, "2s");
    return cfg;
  }
}
