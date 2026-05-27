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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Mutation;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * End-to-end resume round-trip exercising the Quickstart-layer CLI surface from {@code parse}
 * through MAC startup, table mutation, MAC shutdown, and MAC restart in resume mode. Banner
 * rendering is invoked on both runs so a regression that lets the {@code (ephemeral)} annotation
 * slip back in is caught.
 *
 * <p>
 * Scoped against the {@link QuickstartConfig#parse(String[], Map)} entry point rather than driving
 * the {@code bin/accumulo quickstart} script: forking a subprocess from a Maven test buys nothing
 * beyond the in-process flow, and {@code Quickstart.main}'s {@code System.exit} / shutdown-hook
 * lifecycle is hostile to test reuse. The pieces this slice introduces - decision tree, resume
 * dispatch, banner suppression of {@code (ephemeral)} - all live below {@code main} and are
 * exercised in full here.
 *
 * <p>
 * Post-resume user-table scan-back is verified manually rather than in this test. The MAC layer's
 * sibling {@code MiniAccumuloClusterResumeTest.resumeRoundTripPreservesTableMetadata} documents the
 * same constraint: Accumulo's WAL recovery loop is brittle under MAC's tserver shutdown (empty WALs
 * around the metadata tservers can stall user-tablet assignment for minutes), so we assert
 * table-metadata survival here and leave end-user scan verification to the manual acceptance step
 * in the Phase 2 / Slice 4 issue.
 */
@SuppressFBWarnings(
    value = {"PATH_TRAVERSAL_IN", "HARD_CODE_PASSWORD", "UNENCRYPTED_SERVER_SOCKET"},
    justification = "test fixtures: hard-coded password is throwaway; paths come from a test "
        + "temp dir; preflight ServerSockets are bound and immediately closed only to find free "
        + "ports for the cluster")
public class QuickstartResumeTest extends WithTestNames {

  private static final String ROOT_PASSWORD = "resumeRoundTrip";
  private static final String TABLE_NAME = "ingested";

  private File freshDataDir() {
    File baseDir = new File(System.getProperty("user.dir") + "/target/mini-tests/"
        + QuickstartResumeTest.class.getName());
    File dir = new File(baseDir, testName());
    FileUtils.deleteQuietly(dir);
    assertTrue(dir.mkdirs() || dir.isDirectory(),
        "test precondition: ensured data dir " + dir + " is fresh and empty");
    return dir;
  }

  @Test
  @Timeout(240)
  public void quickstartCliFlowDispatchesResumeAndPreservesTableMetadata() throws Exception {
    File dataDir = freshDataDir();
    int[] ports = pickFourFreePorts();
    String[] cliArgs = quickstartArgs(dataDir, ports);

    // ---- Run 1: parse, dispatch fresh, ingest, write a marker --------------------------------
    QuickstartConfig.ParseResult parsed1 =
        QuickstartConfig.parse(cliArgs, Map.of(QuickstartConfig.ENV_ROOT_PASSWORD, ROOT_PASSWORD));
    assertEquals(QuickstartConfig.ParseResult.Kind.SUCCESS, parsed1.kind(),
        () -> "parse failure: " + parsed1.message());
    QuickstartConfig config1 = parsed1.config();
    assertEquals(dataDir.getAbsolutePath(), config1.dataDir());

    QuickstartConfig.DataDirPlan plan1 = config1.resolveDataDirPlan();
    assertEquals(QuickstartConfig.Disposition.FRESH_AT_PATH, plan1.disposition(),
        "first run against an empty supplied dir must dispatch fresh");
    assertTrue(!plan1.ephemeral(),
        "the fresh-at-path run must not be marked ephemeral so the dir survives shutdown");

    MiniAccumuloConfig macConfig1 = config1.toMiniAccumuloConfig(plan1.path());
    MiniAccumuloCluster fresh = new MiniAccumuloCluster(macConfig1);
    fresh.start();
    try (AccumuloClient client =
        fresh.createAccumuloClient("root", new PasswordToken(ROOT_PASSWORD))) {
      client.tableOperations().create(TABLE_NAME);
      try (BatchWriter bw = client.createBatchWriter(TABLE_NAME)) {
        Mutation m = new Mutation("row-001");
        m.put("fam", "qual", "value-1");
        bw.addMutation(m);
      }
      // Flush so the user-table data lives on disk in an rfile, independent of WAL recovery.
      client.tableOperations().flush(TABLE_NAME, null, null, true);
    } finally {
      fresh.stop();
    }

    // The banner string is what the user sees on stdout; assert that the data-dir line surfaces
    // the supplied path without the "(ephemeral)" suffix that Phase 1 (and the parent issue)
    // explicitly call out as misleading when --data-dir is in play.
    String banner1 = renderBanner(macConfig1, plan1, config1);
    assertTrue(banner1.contains(dataDir.getAbsolutePath()),
        () -> "banner must include the resolved data-dir path; got:\n" + banner1);
    assertTrue(!banner1.contains("(ephemeral)"),
        () -> "banner must drop the (ephemeral) tag when --data-dir is in use; got:\n" + banner1);

    File marker = new File(dataDir, "mac-instance.properties");
    assertTrue(marker.exists(), "fresh-mode MAC should have written the resume marker");

    // ---- Run 2: parse again, dispatch resume, verify table metadata survived -----------------
    QuickstartConfig.ParseResult parsed2 =
        QuickstartConfig.parse(cliArgs, Map.of(QuickstartConfig.ENV_ROOT_PASSWORD, ROOT_PASSWORD));
    QuickstartConfig config2 = parsed2.config();
    QuickstartConfig.DataDirPlan plan2 = config2.resolveDataDirPlan();
    assertEquals(QuickstartConfig.Disposition.RESUME_AT_PATH, plan2.disposition(),
        "a populated data dir on the second run must dispatch resume");
    assertTrue(plan2.resumeMode());

    MiniAccumuloConfig macConfig2 = config2.toMiniAccumuloConfig(plan2.path());
    macConfig2.getImpl().resume();
    MiniAccumuloCluster resumed = new MiniAccumuloCluster(macConfig2);
    resumed.start();
    try (AccumuloClient client =
        resumed.createAccumuloClient("root", new PasswordToken(ROOT_PASSWORD))) {
      assertTrue(client.tableOperations().list().contains(TABLE_NAME),
          TABLE_NAME + " should still exist after resume; tableOperations().list() proves the "
              + "manager talked to the persisted metadata table on the new boot");
    } finally {
      resumed.stop();
    }

    String banner2 = renderBanner(macConfig2, plan2, config2);
    assertTrue(banner2.contains(dataDir.getAbsolutePath()),
        () -> "resume banner must also surface the supplied path; got:\n" + banner2);
    assertTrue(!banner2.contains("(ephemeral)"),
        () -> "resume-mode banner must also drop (ephemeral); got:\n" + banner2);
  }

  /**
   * Render the ready banner the same way {@code Quickstart.main} does, so tests catch any drift in
   * how {@code dataDirEphemeral} is wired across the boundary.
   */
  private static String renderBanner(MiniAccumuloConfig mac, QuickstartConfig.DataDirPlan plan,
      QuickstartConfig config) {
    return QuickstartBanner.format(
        new QuickstartBanner.BannerInputs("quickstart", "http://localhost:" + config.monitorPort(),
            "127.0.0.1:" + config.zooKeeperPort(), config.rootPassword(),
            mac.getDir().getAbsolutePath(), plan.ephemeral(), config.shouldWarnInsecure()));
  }

  private static String[] quickstartArgs(File dataDir, int[] ports) {
    return new String[] {"--data-dir", dataDir.getAbsolutePath(), "--zk-port",
        Integer.toString(ports[0]), "--manager-port", Integer.toString(ports[1]), "--tserver-port",
        Integer.toString(ports[2]), "--monitor-port", Integer.toString(ports[3])};
  }

  /**
   * Allocate four free TCP ports for the cluster. ServerSocket(0) asks the OS for an unused port
   * and immediately releases it; the small window between close and MAC's bind is the standard
   * "free port" gamble used widely in test infrastructure. We open all four sockets at once before
   * closing any, so the kernel does not hand us the same port four times.
   */
  private static int[] pickFourFreePorts() throws IOException {
    try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0);
        ServerSocket s3 = new ServerSocket(0); ServerSocket s4 = new ServerSocket(0)) {
      return new int[] {s1.getLocalPort(), s2.getLocalPort(), s3.getLocalPort(), s4.getLocalPort()};
    }
  }
}
