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

import org.apache.accumulo.core.conf.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "paths not set by user input")
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
}
