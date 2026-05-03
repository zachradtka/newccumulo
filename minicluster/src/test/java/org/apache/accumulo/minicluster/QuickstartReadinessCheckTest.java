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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "paths not set by user input")
public class QuickstartReadinessCheckTest extends WithTestNames {

  private static final Logger log = LoggerFactory.getLogger(QuickstartReadinessCheckTest.class);

  @TempDir
  private static Path tempDir;

  private MiniAccumuloCluster cluster;

  @BeforeEach
  public void setupTestCluster() throws IOException {
    final Path perTestSubDir = tempDir.resolve(testName());
    Files.deleteIfExists(perTestSubDir);
    Files.createDirectories(perTestSubDir);
    // Use random ports - this test exercises the readiness check, not the canonical-ports
    // configuration. Production quickstart binds to fixed ports; tests must not, or they will
    // collide with whatever else is on the machine.
    cluster = new MiniAccumuloCluster(perTestSubDir.toFile(), "secret");
  }

  @AfterEach
  public void teardownTestCluster() {
    if (cluster != null) {
      try {
        cluster.stop();
      } catch (IOException | InterruptedException e) {
        log.warn("Failure during tear down", e);
      }
    }
  }

  @Test
  public void readinessCheckSucceedsOnHealthyCluster() throws Exception {
    cluster.start();
    QuickstartReadinessCheck check = new QuickstartReadinessCheck(Duration.ofSeconds(60));
    QuickstartReadinessCheck.Result result = check.awaitReady(cluster, "root", "secret");
    assertTrue(result.success(), "expected success, got: " + result);
    assertTrue(result.elapsed().toMillis() >= 0);
  }

  @Test
  public void readinessCheckTimesOutOnStoppedCluster() throws Exception {
    // Start and immediately stop so client connections will fail on the timeout path
    cluster.start();
    cluster.stop();

    QuickstartReadinessCheck check = new QuickstartReadinessCheck(Duration.ofSeconds(2));
    QuickstartReadinessCheck.Result result = check.awaitReady(cluster, "root", "secret");
    assertFalse(result.success(), "expected timeout, got success");
    assertTrue(result.detail().contains("timed out"),
        "expected detail to mention timeout, got: " + result.detail());
  }
}
