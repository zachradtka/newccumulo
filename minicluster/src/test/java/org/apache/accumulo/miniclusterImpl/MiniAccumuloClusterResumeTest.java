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
package org.apache.accumulo.miniclusterImpl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.Properties;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.minicluster.WithTestNames;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "paths not set by user input")
public class MiniAccumuloClusterResumeTest extends WithTestNames {

  private static final String PASSWORD = "superSecret";

  private File freshTestDir() {
    File baseDir = new File(System.getProperty("user.dir") + "/target/mini-tests/"
        + MiniAccumuloClusterResumeTest.class.getName());
    File testDir = new File(baseDir, testName());
    FileUtils.deleteQuietly(testDir);
    assertTrue(testDir.mkdirs());
    return testDir;
  }

  /**
   * Round-trip: fresh MAC creates a table and writes a flushed row, then a resumed MAC against the
   * same data dir sees the table in its metadata. Scanning the row back is exercised by an
   * end-to-end Quickstart test in a follow-up slice — at the MAC layer, Accumulo's existing WAL
   * recovery owns whether user-table data is readable on restart, and that path is brittle under
   * the tserver's SIGTERM shutdown that {@link MiniAccumuloClusterImpl#stop()} performs (empty WALs
   * left around the metadata tservers can stall the recovery loop). This slice verifies the resume
   * mechanism itself: services come up, the marker is respected, and table metadata survives.
   */
  @Test
  @Timeout(180)
  public void resumeRoundTripPreservesTableMetadata() throws Exception {
    File dir = freshTestDir();
    String tableName = "resumed";

    MiniAccumuloClusterImpl fresh =
        new MiniAccumuloClusterImpl(new MiniAccumuloConfigImpl(dir, PASSWORD));
    fresh.start();
    try (AccumuloClient client = fresh.createAccumuloClient("root", new PasswordToken(PASSWORD))) {
      client.tableOperations().create(tableName);
      try (BatchWriter bw = client.createBatchWriter(tableName)) {
        Mutation m = new Mutation("row-001");
        m.put("fam", "qual", "value-1");
        bw.addMutation(m);
      }
      // Flush minor-compacts the WAL contents into an rfile so the user-table data lives on
      // disk independent of WAL recovery.
      client.tableOperations().flush(tableName, null, null, true);
    } finally {
      fresh.stop();
    }

    File marker = new File(dir, MiniAccumuloConfigImpl.MAC_MARKER_FILENAME);
    assertTrue(marker.exists(), "fresh MAC should have written the resume marker");

    MiniAccumuloClusterImpl resumed =
        new MiniAccumuloClusterImpl(new MiniAccumuloConfigImpl(dir, PASSWORD).resume());
    resumed.start();
    try (
        AccumuloClient client = resumed.createAccumuloClient("root", new PasswordToken(PASSWORD))) {
      assertTrue(client.tableOperations().list().contains(tableName),
          tableName + " should still exist in the resumed instance");
    } finally {
      resumed.stop();
    }
  }

  @Test
  @Timeout(120)
  public void resumeRefusesAfterMarkerVersionTampered() throws Exception {
    File dir = freshTestDir();

    MiniAccumuloClusterImpl fresh =
        new MiniAccumuloClusterImpl(new MiniAccumuloConfigImpl(dir, PASSWORD));
    fresh.start();
    fresh.stop();

    File marker = new File(dir, MiniAccumuloConfigImpl.MAC_MARKER_FILENAME);
    assertTrue(marker.exists(), "fresh run should have written the marker");
    Properties props = new Properties();
    try (FileInputStream fis = new FileInputStream(marker)) {
      props.load(fis);
    }
    props.setProperty("accumulo.version", "9.99.99-FAKE");
    try (FileWriter fw = new FileWriter(marker, UTF_8)) {
      props.store(fw, "tampered for test");
    }

    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> new MiniAccumuloConfigImpl(dir, PASSWORD).resume().initialize());
    assertTrue(ex.getMessage().contains("9.99.99-FAKE"));
  }
}
