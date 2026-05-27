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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.accumulo.core.conf.Property;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

/**
 * Immutable value object holding the user-facing configuration for {@link Quickstart}. Translates
 * to a fully-realized {@link MiniAccumuloConfig} via {@link #toMiniAccumuloConfig(File)}.
 *
 * <p>
 * Slice 1 exposed only {@link #defaults()}. Slice 2 adds CLI/env parsing via
 * {@link #parse(String[], Map)}, which returns a {@link ParseResult} discriminating success, help,
 * and error so callers can drive exit codes without the parser calling {@code System.exit} itself.
 */
public final class QuickstartConfig {

  /** Env var consulted for the root password when no CLI flag is supplied. */
  public static final String ENV_ROOT_PASSWORD = "ACCUMULO_ROOT_PASSWORD";

  /**
   * Env var consulted for the RPC bind address (Accumulo property {@code rpc.bind.addr}). Unset or
   * empty means "no override" - the native CLI keeps its current binding behavior, while the Docker
   * image's entrypoint sets this to {@code 127.0.0.1} to constrain the cluster to loopback inside
   * the container.
   */
  public static final String ENV_BIND_HOST = "ACCUMULO_BIND_HOST";

  /**
   * Env var consulted for the RPC advertise address (Accumulo property {@code rpc.advertise.addr}).
   * Unset or empty means "no override". The Docker image's entrypoint sets this to
   * {@code localhost} so external clients connecting through 1:1 port mappings to the host can
   * reach the cluster.
   */
  public static final String ENV_ADVERTISE_HOST = "ACCUMULO_ADVERTISE_HOST";

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
  /** Empty default means "do not override Accumulo's built-in bind behavior". */
  public static final String DEFAULT_BIND_ADDRESS = "";
  /** Empty default means "do not override Accumulo's built-in advertise behavior". */
  public static final String DEFAULT_ADVERTISE_ADDRESS = "";

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
  private final String bindAddress;
  private final String advertiseAddress;
  private final String dataDir;

  public QuickstartConfig(String instanceName, String rootPassword, int zooKeeperPort,
      int monitorPort, int managerPort, int tabletServerPort, int numTabletServers,
      int numScanServers, int numCompactors, int heapMb, Duration readinessTimeout) {
    this(instanceName, rootPassword, zooKeeperPort, monitorPort, managerPort, tabletServerPort,
        numTabletServers, numScanServers, numCompactors, heapMb, readinessTimeout,
        DEFAULT_BIND_ADDRESS, DEFAULT_ADVERTISE_ADDRESS, null);
  }

  public QuickstartConfig(String instanceName, String rootPassword, int zooKeeperPort,
      int monitorPort, int managerPort, int tabletServerPort, int numTabletServers,
      int numScanServers, int numCompactors, int heapMb, Duration readinessTimeout,
      String bindAddress, String advertiseAddress) {
    this(instanceName, rootPassword, zooKeeperPort, monitorPort, managerPort, tabletServerPort,
        numTabletServers, numScanServers, numCompactors, heapMb, readinessTimeout, bindAddress,
        advertiseAddress, null);
  }

  public QuickstartConfig(String instanceName, String rootPassword, int zooKeeperPort,
      int monitorPort, int managerPort, int tabletServerPort, int numTabletServers,
      int numScanServers, int numCompactors, int heapMb, Duration readinessTimeout,
      String bindAddress, String advertiseAddress, String dataDir) {
    Objects.requireNonNull(instanceName, "instanceName");
    Objects.requireNonNull(rootPassword, "rootPassword");
    Objects.requireNonNull(readinessTimeout, "readinessTimeout");
    Objects.requireNonNull(bindAddress, "bindAddress");
    Objects.requireNonNull(advertiseAddress, "advertiseAddress");
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
    this.bindAddress = bindAddress;
    this.advertiseAddress = advertiseAddress;
    this.dataDir = dataDir;
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
   * @return the RPC bind address to apply via Accumulo's {@code rpc.bind.addr} property, or empty
   *         string if no override should be applied.
   */
  public String bindAddress() {
    return bindAddress;
  }

  /**
   * @return the RPC advertise address to apply via Accumulo's {@code rpc.advertise.addr} property,
   *         or empty string if no override should be applied.
   */
  public String advertiseAddress() {
    return advertiseAddress;
  }

  /**
   * @return the user-supplied {@code --data-dir} path verbatim, or {@code null} if the flag was not
   *         passed. Callers should normally consult {@link #resolveDataDirPlan()} to decide
   *         lifecycle mode rather than inspecting this raw value themselves.
   */
  public String dataDir() {
    return dataDir;
  }

  /**
   * Quickstart-layer plan for what MAC should do with the {@code --data-dir} flag. This is the
   * decision tree from ADR-0007 &sect;Decision-2 (Quickstart, not MAC, owns the runtime choice of
   * when to call {@code .resume()}). Each {@link Disposition} maps one row of that table.
   */
  public enum Disposition {
    /** No {@code --data-dir} supplied; caller mints and owns an ephemeral temp dir. Fresh mode. */
    EPHEMERAL_TEMP,
    /**
     * {@code --data-dir} supplied and the path is empty or does not exist. MAC initializes fresh
     * into the path; the caller is responsible for any required {@code mkdir -p}.
     */
    FRESH_AT_PATH,
    /**
     * {@code --data-dir} supplied and the path is a non-empty directory (or a regular file). MAC
     * resumes against the path; refusals from marker validation propagate from MAC to stderr.
     */
    RESUME_AT_PATH
  }

  /**
   * Immutable result of {@link #resolveDataDirPlan()}. The path is {@code null} only for
   * {@link Disposition#EPHEMERAL_TEMP} (the caller has not minted a temp dir yet); it is the
   * absolute form of the user-supplied path for the other two dispositions.
   */
  public static final class DataDirPlan {

    private final Disposition disposition;
    private final File path;

    DataDirPlan(Disposition disposition, File path) {
      this.disposition = Objects.requireNonNull(disposition, "disposition");
      this.path = path;
    }

    public Disposition disposition() {
      return disposition;
    }

    /**
     * @return the resolved (absolute) path the caller should hand to MAC, or {@code null} for
     *         {@link Disposition#EPHEMERAL_TEMP} where the caller mints its own temp dir.
     */
    public File path() {
      return path;
    }

    /** @return true when the data dir is an ephemeral temp dir that should be deleted on stop. */
    public boolean ephemeral() {
      return disposition == Disposition.EPHEMERAL_TEMP;
    }

    /** @return true when MAC should be configured with {@code resume()} before start. */
    public boolean resumeMode() {
      return disposition == Disposition.RESUME_AT_PATH;
    }
  }

  /**
   * Resolve the {@link DataDirPlan} for this configuration by inspecting the current filesystem
   * state of {@link #dataDir()}.
   *
   * <p>
   * Decision tree (ADR-0007 &sect;Decision-2): no flag &rarr; {@link Disposition#EPHEMERAL_TEMP};
   * flag supplied + nonexistent or empty path &rarr; {@link Disposition#FRESH_AT_PATH}; otherwise
   * &rarr; {@link Disposition#RESUME_AT_PATH}. The Quickstart layer never validates the marker
   * itself; that is MAC's job, and any refusal message from MAC propagates unchanged.
   */
  public DataDirPlan resolveDataDirPlan() {
    if (dataDir == null) {
      return new DataDirPlan(Disposition.EPHEMERAL_TEMP, null);
    }
    File dir = new File(dataDir).getAbsoluteFile();
    if (!dir.exists()) {
      return new DataDirPlan(Disposition.FRESH_AT_PATH, dir);
    }
    if (dir.isDirectory()) {
      String[] children = dir.list();
      if (children == null || children.length == 0) {
        return new DataDirPlan(Disposition.FRESH_AT_PATH, dir);
      }
    }
    // Either a non-empty directory or a regular file at this path. Hand it to MAC in resume mode;
    // MAC's marker check (or its "directory, not file" guard) will produce the specific refusal.
    return new DataDirPlan(Disposition.RESUME_AT_PATH, dir);
  }

  /**
   * @return {@code true} when this cluster is reachable beyond the host's own loopback - i.e.
   *         {@link #advertiseAddress()} is set to a non-loopback value - while still using the
   *         default root password. Such a cluster is dangerously open: any other machine or
   *         container that can resolve the advertised address has full administrative control. The
   *         Docker image defaults the advertise host to {@code localhost}, so a plain
   *         {@code docker run} does not trip this; a compose service name or LAN hostname does.
   */
  public boolean shouldWarnInsecure() {
    return isNonLoopback(advertiseAddress) && rootPassword.equals(DEFAULT_ROOT_PASSWORD);
  }

  /**
   * @return {@code true} when {@code address} is non-empty and is not one of the loopback literals
   *         ({@code localhost}, {@code 127.0.0.1}, {@code ::1}). A bare loopback literal means the
   *         cluster is only reachable from the host itself; anything else means the user has
   *         deliberately made it reachable by other machines or containers.
   */
  private static boolean isNonLoopback(String address) {
    if (address.isEmpty()) {
      return false;
    }
    switch (address.toLowerCase(Locale.ROOT)) {
      case "localhost":
      case "127.0.0.1":
      case "::1":
        return false;
      default:
        return true;
    }
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

    // Empty defaults mean "do not override Accumulo's built-in behavior" - the native CLI keeps
    // working unchanged. The Docker entrypoint sets these env vars so services bind to loopback
    // and advertise a host-reachable address through 1:1 port mappings.
    if (!bindAddress.isEmpty()) {
      cfg.getImpl().setProperty(Property.RPC_PROCESS_BIND_ADDRESS, bindAddress);
    }
    if (!advertiseAddress.isEmpty()) {
      cfg.getImpl().setProperty(Property.RPC_PROCESS_ADVERTISE_ADDRESS, advertiseAddress);
    }

    // Shorten the ZooKeeper session timeout so an interactive Ctrl-C - which sends SIGINT to the
    // whole foreground process group, killing ZK before MAC's shutdown sequence can ZooZap server
    // locks against it - hits its eventual "ZK unreachable" failure in a few seconds rather than
    // the default 60s-per-ZK-op (which leaves the user staring at an apparently-frozen terminal
    // for ~2 minutes). Local-only quickstart cluster, so the production-style 30s timeout buys
    // us nothing here.
    cfg.getImpl().setProperty(Property.INSTANCE_ZK_TIMEOUT, "2s");
    return cfg;
  }

  /**
   * JCommander-annotated argument holder. Package-private (not exposed in the public API) so its
   * field layout can evolve as we add or rename flags.
   *
   * <p>
   * All numeric and string fields are nullable so we can tell whether the user supplied a value or
   * accepted the default - distinction matters for {@link #ENV_ROOT_PASSWORD} precedence and for
   * the {@code --port-base} versus per-service-port mutual-exclusion rule.
   */
  static final class Args {

    @Parameter(names = {"-h", "--help", "-?"}, help = true, description = "Print this help message")
    boolean help;

    @Parameter(names = "--port-base",
        description = "Base port; assigns ZooKeeper=base, manager=base+1, tserver=base+2, "
            + "monitor=base+3. Mutually exclusive with per-service port flags.")
    Integer portBase;

    @Parameter(names = "--zk-port", description = "ZooKeeper client port (default 2181)")
    Integer zkPort;

    @Parameter(names = "--monitor-port", description = "Monitor HTTP port (default 9995)")
    Integer monitorPort;

    @Parameter(names = "--tserver-port", description = "Tablet server client port (default 9997)")
    Integer tserverPort;

    @Parameter(names = "--manager-port", description = "Manager client port (default 9999)")
    Integer managerPort;

    @Parameter(names = "--tservers", description = "Number of tablet servers (default 1)")
    Integer numTservers;

    @Parameter(names = "--scan-servers", description = "Number of scan servers (default 0)")
    Integer numScanServers;

    @Parameter(names = "--compactors", description = "Number of compactors (default 1)")
    Integer numCompactors;

    @Parameter(names = "--heap-mb", description = "Per-service JVM heap in megabytes (default 256)")
    Integer heapMb;

    @Parameter(names = "--root-password",
        description = "Root user password. Overrides ACCUMULO_ROOT_PASSWORD env var. "
            + "Falls back to 'secret' if neither is set.",
        password = false)
    String rootPassword;

    @Parameter(names = "--data-dir",
        description = "Persistent data directory. If supplied, the cluster persists its state at "
            + "this path and a subsequent run against the same path resumes from it. If omitted, "
            + "an ephemeral temp dir is used and deleted on shutdown.")
    String dataDir;
  }

  /**
   * Outcome of {@link #parse(String[], Map)}. Modeled as a discriminated union (rather than the
   * parser calling {@code System.exit}) so the parsing logic stays testable.
   */
  public static final class ParseResult {

    /** What kind of outcome this is. */
    public enum Kind {
      SUCCESS, HELP, ERROR
    }

    private final Kind kind;
    private final QuickstartConfig config;
    private final String message;

    private ParseResult(Kind kind, QuickstartConfig config, String message) {
      this.kind = kind;
      this.config = config;
      this.message = message;
    }

    static ParseResult success(QuickstartConfig config) {
      return new ParseResult(Kind.SUCCESS, Objects.requireNonNull(config, "config"), null);
    }

    static ParseResult helpRequested(String message) {
      return new ParseResult(Kind.HELP, null, Objects.requireNonNull(message, "message"));
    }

    static ParseResult error(String message) {
      return new ParseResult(Kind.ERROR, null, Objects.requireNonNull(message, "message"));
    }

    public Kind kind() {
      return kind;
    }

    /** Resolved configuration. Only valid when {@link #kind()} is {@link Kind#SUCCESS}. */
    public QuickstartConfig config() {
      if (kind != Kind.SUCCESS) {
        throw new IllegalStateException("config() called on non-success result: " + kind);
      }
      return config;
    }

    /**
     * Human-readable message: full usage text for {@link Kind#HELP}, error description for
     * {@link Kind#ERROR}. Empty / undefined for {@link Kind#SUCCESS}.
     */
    public String message() {
      return message;
    }
  }

  /**
   * Parse the given CLI args and environment into a {@link ParseResult}. Precedence for the root
   * password is: {@code --root-password} flag, then {@link #ENV_ROOT_PASSWORD} env var, then the
   * baked-in default {@code secret}.
   *
   * <p>
   * {@code --port-base N} and per-service port flags ({@code --zk-port}, {@code --monitor-port},
   * {@code --tserver-port}, {@code --manager-port}) are mutually exclusive; supplying both forms
   * yields a {@link ParseResult.Kind#ERROR} naming the conflict.
   */
  public static ParseResult parse(String[] args, Map<String,String> env) {
    Objects.requireNonNull(args, "args");
    Args parsed = new Args();
    JCommander jc = JCommander.newBuilder().addObject(parsed).programName("accumulo quickstart")
        .acceptUnknownOptions(false).build();
    try {
      jc.parse(args);
    } catch (ParameterException ex) {
      return ParseResult.error(prettifyParserError(ex.getMessage()) + System.lineSeparator()
          + "Run 'accumulo quickstart --help' for usage.");
    }

    if (parsed.help) {
      return ParseResult.helpRequested(formatUsage(jc));
    }

    boolean anyPerServicePort = parsed.zkPort != null || parsed.monitorPort != null
        || parsed.tserverPort != null || parsed.managerPort != null;
    if (parsed.portBase != null && anyPerServicePort) {
      return ParseResult.error("--port-base cannot be combined with per-service port flags "
          + "(--zk-port, --monitor-port, --tserver-port, --manager-port). Use one or the other.");
    }

    int zkPort;
    int monitorPort;
    int tserverPort;
    int managerPort;
    if (parsed.portBase != null) {
      int base = parsed.portBase;
      if (base < 1 || base + 3 > 65535) {
        return ParseResult.error("--port-base must leave room for four contiguous ports in "
            + "[1, 65535]; got " + base + " (would assign " + (base + 3) + " to monitor).");
      }
      zkPort = base;
      managerPort = base + 1;
      tserverPort = base + 2;
      monitorPort = base + 3;
    } else {
      zkPort = parsed.zkPort != null ? parsed.zkPort : DEFAULT_ZOOKEEPER_PORT;
      monitorPort = parsed.monitorPort != null ? parsed.monitorPort : DEFAULT_MONITOR_PORT;
      tserverPort = parsed.tserverPort != null ? parsed.tserverPort : DEFAULT_TABLET_SERVER_PORT;
      managerPort = parsed.managerPort != null ? parsed.managerPort : DEFAULT_MANAGER_PORT;
    }

    String rootPassword = resolveRootPassword(parsed.rootPassword, env);
    int numTservers = parsed.numTservers != null ? parsed.numTservers : DEFAULT_NUM_TABLET_SERVERS;
    int numScanServers =
        parsed.numScanServers != null ? parsed.numScanServers : DEFAULT_NUM_SCAN_SERVERS;
    int numCompactors =
        parsed.numCompactors != null ? parsed.numCompactors : DEFAULT_NUM_COMPACTORS;
    int heapMb = parsed.heapMb != null ? parsed.heapMb : DEFAULT_HEAP_MB;
    String bindAddress = resolveEnvString(env, ENV_BIND_HOST, DEFAULT_BIND_ADDRESS);
    String advertiseAddress = resolveEnvString(env, ENV_ADVERTISE_HOST, DEFAULT_ADVERTISE_ADDRESS);

    try {
      return ParseResult.success(new QuickstartConfig(DEFAULT_INSTANCE_NAME, rootPassword, zkPort,
          monitorPort, managerPort, tserverPort, numTservers, numScanServers, numCompactors, heapMb,
          DEFAULT_READINESS_TIMEOUT, bindAddress, advertiseAddress, parsed.dataDir));
    } catch (IllegalArgumentException ex) {
      return ParseResult.error(ex.getMessage());
    }
  }

  private static String resolveEnvString(Map<String,String> env, String key, String fallback) {
    if (env != null) {
      String fromEnv = env.get(key);
      if (fromEnv != null && !fromEnv.isEmpty()) {
        return fromEnv;
      }
    }
    return fallback;
  }

  private static String resolveRootPassword(String cliValue, Map<String,String> env) {
    if (cliValue != null) {
      return cliValue;
    }
    if (env != null) {
      String fromEnv = env.get(ENV_ROOT_PASSWORD);
      if (fromEnv != null && !fromEnv.isEmpty()) {
        return fromEnv;
      }
    }
    return DEFAULT_ROOT_PASSWORD;
  }

  /**
   * Normalize JCommander's stock error messages so the user sees something more direct. JCommander
   * treats any unknown {@code --flag} token as a positional ("main parameter") because we never
   * declared one; the resulting message ("Was passed main parameter '--foo' but no main parameter
   * was defined...") is jargon. Rewrite it to "Unknown option: --foo" when the token looks like a
   * flag.
   */
  static String prettifyParserError(String raw) {
    if (raw == null) {
      return "";
    }
    final String prefix = "Was passed main parameter '";
    if (raw.startsWith(prefix)) {
      int closing = raw.indexOf('\'', prefix.length());
      if (closing > 0) {
        String token = raw.substring(prefix.length(), closing);
        if (token.startsWith("-")) {
          return "Unknown option: " + token;
        }
      }
    }
    return raw;
  }

  private static String formatUsage(JCommander jc) {
    StringBuilder sb = new StringBuilder();
    jc.getUsageFormatter().usage(sb);
    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
      sb.append(System.lineSeparator());
    }
    sb.append(System.lineSeparator());
    sb.append("See docs/quickstart.md for full documentation.").append(System.lineSeparator());
    return sb.toString();
  }
}
