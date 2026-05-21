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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.minicluster.QuickstartConfig.ParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = {"PATH_TRAVERSAL_IN", "HARD_CODE_PASSWORD"},
    justification = "paths not set by user input; password literals are throwaway test fixtures, "
        + "not real credentials")
public class QuickstartConfigTest {

  @TempDir
  private Path tmp;

  @Test
  public void defaultsHoldTheCanonicalQuickstartShape() {
    QuickstartConfig c = QuickstartConfig.defaults();
    assertEquals("quickstart", c.instanceName());
    assertEquals("secret", c.rootPassword());
    assertEquals(2181, c.zooKeeperPort());
    assertEquals(9995, c.monitorPort());
    assertEquals(9999, c.managerPort());
    assertEquals(9997, c.tabletServerPort());
    assertEquals(1, c.numTabletServers());
    assertEquals(0, c.numScanServers());
    assertEquals(1, c.numCompactors());
    assertEquals(256, c.heapMb());
    assertEquals(Duration.ofSeconds(60), c.readinessTimeout());
  }

  @Test
  public void translatesToMiniAccumuloConfigWithDefaults() {
    File dir = tmp.resolve("mac").toFile();
    QuickstartConfig c = QuickstartConfig.defaults();
    MiniAccumuloConfig cfg = c.toMiniAccumuloConfig(dir);

    assertEquals("quickstart", cfg.getInstanceName());
    assertEquals("secret", cfg.getRootPassword());
    assertEquals(dir, cfg.getDir());
    assertEquals(2181, cfg.getZooKeeperPort());

    // public getSiteConfig() only exposes user-supplied bulk site config; per-property setProperty
    // calls land in the impl-level site map, so drop to getImpl() to inspect them
    var siteConfig = cfg.getImpl().getSiteConfig();
    assertEquals("9999", siteConfig.get(Property.MANAGER_CLIENTPORT.getKey()));
    assertEquals("9997", siteConfig.get(Property.TSERV_CLIENTPORT.getKey()));
    assertEquals("9995", siteConfig.get(Property.MONITOR_PORT.getKey()));

    assertEquals(256L * 1024L * 1024L, cfg.getDefaultMemory());

    assertEquals(1, cfg.getNumTservers());
    // getNumScanServers / getNumCompactors are impl-only on 2.1
    assertEquals(0, cfg.getImpl().getNumScanServers());
    assertEquals(1, cfg.getImpl().getNumCompactors());
  }

  @Test
  public void rejectsBlankInstanceName() {
    assertThrows(IllegalArgumentException.class, () -> new QuickstartConfig("", "secret", 2181,
        9995, 9999, 9997, 1, 0, 1, 256, Duration.ofSeconds(60)));
  }

  @Test
  public void rejectsEmptyRootPassword() {
    assertThrows(IllegalArgumentException.class, () -> new QuickstartConfig("quickstart", "", 2181,
        9995, 9999, 9997, 1, 0, 1, 256, Duration.ofSeconds(60)));
  }

  @Test
  public void rejectsZeroTabletServers() {
    assertThrows(IllegalArgumentException.class, () -> new QuickstartConfig("quickstart", "secret",
        2181, 9995, 9999, 9997, 0, 0, 1, 256, Duration.ofSeconds(60)));
  }

  @Test
  public void rejectsNegativeScanServers() {
    assertThrows(IllegalArgumentException.class, () -> new QuickstartConfig("quickstart", "secret",
        2181, 9995, 9999, 9997, 1, -1, 1, 256, Duration.ofSeconds(60)));
  }

  @Test
  public void rejectsOutOfRangePort() {
    assertThrows(IllegalArgumentException.class, () -> new QuickstartConfig("quickstart", "secret",
        70000, 9995, 9999, 9997, 1, 0, 1, 256, Duration.ofSeconds(60)));
  }

  @Test
  public void rejectsZeroReadinessTimeout() {
    assertThrows(IllegalArgumentException.class, () -> new QuickstartConfig("quickstart", "secret",
        2181, 9995, 9999, 9997, 1, 0, 1, 256, Duration.ZERO));
  }

  @Test
  public void translationProducesADirectoryThatMacWouldAccept() {
    // Sanity check that we did not pre-create the dir or otherwise violate MAC's empty-dir guard
    File dir = tmp.resolve("nested-empty").toFile();
    MiniAccumuloConfig cfg = QuickstartConfig.defaults().toMiniAccumuloConfig(dir);
    assertTrue(!cfg.getDir().exists() || cfg.getDir().list().length == 0);
  }

  // ---------------------------------------------------------------------------
  // Slice 2: CLI / env parsing via QuickstartConfig.parse(args, env)
  // ---------------------------------------------------------------------------

  private static QuickstartConfig parseOk(String... args) {
    ParseResult r = QuickstartConfig.parse(args, Collections.emptyMap());
    assertEquals(ParseResult.Kind.SUCCESS, r.kind(),
        () -> "expected success, got " + r.kind() + " message=" + r.message());
    return r.config();
  }

  private static ParseResult parseRaw(Map<String,String> env, String... args) {
    return QuickstartConfig.parse(args, env);
  }

  @Test
  public void parseNoArgsYieldsDefaults() {
    QuickstartConfig c = parseOk();
    QuickstartConfig d = QuickstartConfig.defaults();
    assertEquals(d.instanceName(), c.instanceName());
    assertEquals(d.rootPassword(), c.rootPassword());
    assertEquals(d.zooKeeperPort(), c.zooKeeperPort());
    assertEquals(d.monitorPort(), c.monitorPort());
    assertEquals(d.managerPort(), c.managerPort());
    assertEquals(d.tabletServerPort(), c.tabletServerPort());
    assertEquals(d.numTabletServers(), c.numTabletServers());
    assertEquals(d.numScanServers(), c.numScanServers());
    assertEquals(d.numCompactors(), c.numCompactors());
    assertEquals(d.heapMb(), c.heapMb());
  }

  @Test
  public void parsePortBaseShiftsAllFourPortsContiguously() {
    QuickstartConfig c = parseOk("--port-base", "20000");
    assertEquals(20000, c.zooKeeperPort());
    assertEquals(20001, c.managerPort());
    assertEquals(20002, c.tabletServerPort());
    assertEquals(20003, c.monitorPort());
  }

  @Test
  public void parsePerServicePortFlagsOverrideIndividually() {
    QuickstartConfig c = parseOk("--zk-port", "12181", "--monitor-port", "19995", "--tserver-port",
        "19997", "--manager-port", "19999");
    assertEquals(12181, c.zooKeeperPort());
    assertEquals(19995, c.monitorPort());
    assertEquals(19997, c.tabletServerPort());
    assertEquals(19999, c.managerPort());
  }

  @Test
  public void parsePortBaseAndPerServiceTogetherIsAnError() {
    ParseResult r = parseRaw(Collections.emptyMap(), "--port-base", "20000", "--zk-port", "12181");
    assertEquals(ParseResult.Kind.ERROR, r.kind());
    assertTrue(r.message().contains("--port-base"),
        () -> "error should mention --port-base; got: " + r.message());
    assertTrue(r.message().contains("--zk-port") || r.message().contains("per-service port"),
        () -> "error should mention conflicting per-service flag; got: " + r.message());
  }

  @Test
  public void parsePortBaseOutOfRangeIsAnError() {
    ParseResult r = parseRaw(Collections.emptyMap(), "--port-base", "65534");
    assertEquals(ParseResult.Kind.ERROR, r.kind());
    assertTrue(r.message().contains("--port-base"),
        () -> "error should mention --port-base; got: " + r.message());
  }

  @Test
  public void parseClusterShapeFlags() {
    QuickstartConfig c = parseOk("--tservers", "2", "--scan-servers", "1", "--compactors", "3");
    assertEquals(2, c.numTabletServers());
    assertEquals(1, c.numScanServers());
    assertEquals(3, c.numCompactors());
  }

  @Test
  public void parseHeapMbFlag() {
    QuickstartConfig c = parseOk("--heap-mb", "512");
    assertEquals(512, c.heapMb());
  }

  @Test
  public void parseRootPasswordFlagWins() {
    Map<String,String> env = new HashMap<>();
    env.put(QuickstartConfig.ENV_ROOT_PASSWORD, "from-env");
    ParseResult r = parseRaw(env, "--root-password", "from-flag");
    assertEquals(ParseResult.Kind.SUCCESS, r.kind());
    assertEquals("from-flag", r.config().rootPassword());
  }

  @Test
  public void parseRootPasswordFallsBackToEnvVar() {
    Map<String,String> env = new HashMap<>();
    env.put(QuickstartConfig.ENV_ROOT_PASSWORD, "from-env");
    ParseResult r = parseRaw(env);
    assertEquals(ParseResult.Kind.SUCCESS, r.kind());
    assertEquals("from-env", r.config().rootPassword());
  }

  @Test
  public void parseRootPasswordFallsBackToDefaultWhenNeitherSet() {
    Map<String,String> env = new HashMap<>();
    // ENV_ROOT_PASSWORD intentionally absent
    ParseResult r = parseRaw(env);
    assertEquals(ParseResult.Kind.SUCCESS, r.kind());
    assertEquals(QuickstartConfig.DEFAULT_ROOT_PASSWORD, r.config().rootPassword());
  }

  @Test
  public void parseEmptyEnvRootPasswordIsIgnored() {
    Map<String,String> env = new HashMap<>();
    env.put(QuickstartConfig.ENV_ROOT_PASSWORD, "");
    ParseResult r = parseRaw(env);
    assertEquals(ParseResult.Kind.SUCCESS, r.kind(),
        () -> "empty env should not cause an error; got " + r.message());
    assertEquals(QuickstartConfig.DEFAULT_ROOT_PASSWORD, r.config().rootPassword());
  }

  @Test
  public void parseNullEnvTreatedAsEmpty() {
    ParseResult r = QuickstartConfig.parse(new String[0], null);
    assertEquals(ParseResult.Kind.SUCCESS, r.kind());
    assertEquals(QuickstartConfig.DEFAULT_ROOT_PASSWORD, r.config().rootPassword());
  }

  @Test
  public void parseHelpReturnsHelpResult() {
    ParseResult r = parseRaw(Collections.emptyMap(), "--help");
    assertEquals(ParseResult.Kind.HELP, r.kind());
    assertTrue(r.message().contains("--port-base"),
        () -> "help should mention --port-base; got:\n" + r.message());
    assertTrue(r.message().contains("--root-password"),
        () -> "help should mention --root-password; got:\n" + r.message());
    assertTrue(r.message().contains("docs/quickstart.md"),
        () -> "help footer should link to docs/quickstart.md; got:\n" + r.message());
  }

  @Test
  public void parseShortHelpFlag() {
    ParseResult r = parseRaw(Collections.emptyMap(), "-h");
    assertEquals(ParseResult.Kind.HELP, r.kind());
  }

  @Test
  public void parseUnknownFlagYieldsError() {
    ParseResult r = parseRaw(Collections.emptyMap(), "--no-such-flag");
    assertEquals(ParseResult.Kind.ERROR, r.kind());
    assertTrue(r.message().contains("Unknown option: --no-such-flag"),
        () -> "error should call out the unknown option; got: " + r.message());
    assertTrue(r.message().contains("--help"),
        () -> "error should hint at --help; got: " + r.message());
  }

  @Test
  public void parseNegativeTserverCountYieldsError() {
    ParseResult r = parseRaw(Collections.emptyMap(), "--tservers", "0");
    assertEquals(ParseResult.Kind.ERROR, r.kind());
    assertTrue(r.message().contains("numTabletServers") || r.message().contains("tservers"),
        () -> "error should reference the bad value; got: " + r.message());
  }

  @Test
  public void parseNegativeScanServerCountYieldsError() {
    ParseResult r = parseRaw(Collections.emptyMap(), "--scan-servers", "-1");
    assertEquals(ParseResult.Kind.ERROR, r.kind());
  }

  @Test
  public void parseNegativeHeapYieldsError() {
    ParseResult r = parseRaw(Collections.emptyMap(), "--heap-mb", "0");
    assertEquals(ParseResult.Kind.ERROR, r.kind());
  }

  @Test
  public void parseOutOfRangePerServicePortYieldsError() {
    ParseResult r = parseRaw(Collections.emptyMap(), "--zk-port", "70000");
    assertEquals(ParseResult.Kind.ERROR, r.kind());
  }

  @Test
  public void parsedConfigTranslatesToMacWithOverriddenValues() {
    QuickstartConfig c = parseOk("--port-base", "20000", "--tservers", "2", "--heap-mb", "512",
        "--root-password", "mysecret");
    File dir = tmp.resolve("mac").toFile();
    MiniAccumuloConfig cfg = c.toMiniAccumuloConfig(dir);

    assertEquals("mysecret", cfg.getRootPassword());
    assertEquals(20000, cfg.getZooKeeperPort());
    var siteConfig = cfg.getImpl().getSiteConfig();
    assertEquals("20001", siteConfig.get(Property.MANAGER_CLIENTPORT.getKey()));
    assertEquals("20002", siteConfig.get(Property.TSERV_CLIENTPORT.getKey()));
    assertEquals("20003", siteConfig.get(Property.MONITOR_PORT.getKey()));
    assertEquals(512L * 1024L * 1024L, cfg.getDefaultMemory());
    assertEquals(2, cfg.getNumTservers());
  }

  @Test
  public void parseResultConfigOnNonSuccessThrows() {
    ParseResult r = parseRaw(Collections.emptyMap(), "--help");
    assertEquals(ParseResult.Kind.HELP, r.kind());
    assertThrows(IllegalStateException.class, r::config);
  }

  @Test
  public void parseRejectsNullArgs() {
    assertThrows(NullPointerException.class,
        () -> QuickstartConfig.parse(null, Collections.emptyMap()));
  }

  // ---------------------------------------------------------------------------
  // Slice 3: bind/advertise env vars consumed by the Docker entrypoint
  // ---------------------------------------------------------------------------

  @Test
  public void defaultsHaveNoBindOrAdvertiseOverride() {
    QuickstartConfig c = QuickstartConfig.defaults();
    assertEquals("", c.bindAddress());
    assertEquals("", c.advertiseAddress());
  }

  @Test
  public void parseReadsBindAndAdvertiseFromEnv() {
    Map<String,String> env = new HashMap<>();
    env.put(QuickstartConfig.ENV_BIND_HOST, "127.0.0.1");
    env.put(QuickstartConfig.ENV_ADVERTISE_HOST, "localhost");
    ParseResult r = parseRaw(env);
    assertEquals(ParseResult.Kind.SUCCESS, r.kind());
    assertEquals("127.0.0.1", r.config().bindAddress());
    assertEquals("localhost", r.config().advertiseAddress());
  }

  @Test
  public void parseTreatsEmptyBindAndAdvertiseEnvAsUnset() {
    Map<String,String> env = new HashMap<>();
    env.put(QuickstartConfig.ENV_BIND_HOST, "");
    env.put(QuickstartConfig.ENV_ADVERTISE_HOST, "");
    ParseResult r = parseRaw(env);
    assertEquals(ParseResult.Kind.SUCCESS, r.kind());
    assertEquals("", r.config().bindAddress());
    assertEquals("", r.config().advertiseAddress());
  }

  @Test
  public void translatesBindAndAdvertiseToMacProperties() {
    File dir = tmp.resolve("mac-bind").toFile();
    QuickstartConfig c = new QuickstartConfig("quickstart", "secret", 2181, 9995, 9999, 9997, 1, 0,
        1, 256, Duration.ofSeconds(60), "127.0.0.1", "localhost");
    MiniAccumuloConfig cfg = c.toMiniAccumuloConfig(dir);
    var siteConfig = cfg.getImpl().getSiteConfig();
    assertEquals("127.0.0.1", siteConfig.get(Property.RPC_PROCESS_BIND_ADDRESS.getKey()));
    assertEquals("localhost", siteConfig.get(Property.RPC_PROCESS_ADVERTISE_ADDRESS.getKey()));
  }

  @Test
  public void translationOmitsBindAndAdvertisePropsWhenDefaulted() {
    File dir = tmp.resolve("mac-default-bind").toFile();
    MiniAccumuloConfig cfg = QuickstartConfig.defaults().toMiniAccumuloConfig(dir);
    var siteConfig = cfg.getImpl().getSiteConfig();
    // No override means the keys are absent - Accumulo falls back to its own defaults.
    assertTrue(!siteConfig.containsKey(Property.RPC_PROCESS_BIND_ADDRESS.getKey()),
        () -> "rpc.bind.addr should be absent by default; got: " + siteConfig);
    assertTrue(!siteConfig.containsKey(Property.RPC_PROCESS_ADVERTISE_ADDRESS.getKey()),
        () -> "rpc.advertise.addr should be absent by default; got: " + siteConfig);
  }

  // ---------------------------------------------------------------------------
  // Slice 4: shouldWarnInsecure() - non-loopback advertise + default password
  // ---------------------------------------------------------------------------

  private static QuickstartConfig withAdvertiseAndPassword(String advertise, String password) {
    return new QuickstartConfig("quickstart", password, 2181, 9995, 9999, 9997, 1, 0, 1, 256,
        Duration.ofSeconds(60), "", advertise);
  }

  @Test
  public void warnsWhenNonLoopbackAdvertiseAndDefaultPassword() {
    assertTrue(withAdvertiseAndPassword("accumulo", "secret").shouldWarnInsecure(),
        "a compose service name with the default password must trip the warning");
    assertTrue(withAdvertiseAndPassword("my-lan-host", "secret").shouldWarnInsecure(),
        "a LAN hostname with the default password must trip the warning");
    assertTrue(withAdvertiseAndPassword("10.0.0.5", "secret").shouldWarnInsecure(),
        "a non-loopback IP with the default password must trip the warning");
  }

  @Test
  public void noWarnWhenPasswordOverridden() {
    assertTrue(!withAdvertiseAndPassword("accumulo", "changed-it").shouldWarnInsecure(),
        "overriding the root password must suppress the warning regardless of advertise host");
  }

  @Test
  public void noWarnWhenAdvertiseIsLoopbackOrUnset() {
    assertTrue(!withAdvertiseAndPassword("", "secret").shouldWarnInsecure(),
        "an unset advertise host must not trip the warning");
    assertTrue(!withAdvertiseAndPassword("localhost", "secret").shouldWarnInsecure(),
        "advertise=localhost must not trip the warning");
    assertTrue(!withAdvertiseAndPassword("127.0.0.1", "secret").shouldWarnInsecure(),
        "advertise=127.0.0.1 must not trip the warning");
    assertTrue(!withAdvertiseAndPassword("::1", "secret").shouldWarnInsecure(),
        "advertise=::1 must not trip the warning");
    assertTrue(!withAdvertiseAndPassword("LOCALHOST", "secret").shouldWarnInsecure(),
        "loopback literal matching must be case-insensitive");
  }

  @Test
  public void defaultsDoNotWarn() {
    assertTrue(!QuickstartConfig.defaults().shouldWarnInsecure(),
        "the canonical defaults (no advertise override) must not trip the warning");
  }

  @Test
  public void parseSliceOneNoFlagsStillWorksEndToEnd() {
    // Regression guard for the acceptance criterion: running with no flags must keep working.
    QuickstartConfig c = parseOk();
    File dir = tmp.resolve("slice1-regression").toFile();
    MiniAccumuloConfig cfg = c.toMiniAccumuloConfig(dir);
    assertEquals("quickstart", cfg.getInstanceName());
    assertEquals("secret", cfg.getRootPassword());
    assertEquals(2181, cfg.getZooKeeperPort());
    assertTrue(!cfg.getDir().exists() || cfg.getDir().list().length == 0,
        "translate must produce an empty/nonexistent dir MAC will accept");
  }
}
