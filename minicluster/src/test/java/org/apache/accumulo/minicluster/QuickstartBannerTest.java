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

import org.apache.accumulo.minicluster.QuickstartBanner.BannerInputs;
import org.junit.jupiter.api.Test;

public class QuickstartBannerTest {

  @Test
  public void formatEphemeralBannerExactString() {
    BannerInputs in = new BannerInputs("quickstart", "http://localhost:9995", "localhost:2181",
        "secret", "/tmp/accumulo-quickstart-12345", true, false);

    String expected = String.join("\n",
        "==========================================================================",
        " Apache Accumulo Quickstart - ready", "", "   Instance:       quickstart",
        "   Monitor URL:    http://localhost:9995", "   ZooKeeper:      localhost:2181",
        "   Data dir:       /tmp/accumulo-quickstart-12345 (ephemeral)", "",
        "   Connect with the shell:",
        "     accumulo shell -zh localhost:2181 -zi quickstart -u root -p secret", "",
        "   Press Ctrl-C to stop.",
        "==========================================================================", "");

    assertEquals(expected, QuickstartBanner.format(in));
  }

  @Test
  public void persistentBannerOmitsEphemeralAnnotation() {
    BannerInputs in = new BannerInputs("quickstart", "http://localhost:9995", "localhost:2181",
        "secret", "/var/lib/accumulo/quickstart", false, false);

    String banner = QuickstartBanner.format(in);
    assertTrue(banner.contains("Data dir:       /var/lib/accumulo/quickstart\n"),
        "persistent banner should show plain data dir without (ephemeral) tag, got:\n" + banner);
    assertTrue(!banner.contains("(ephemeral)"),
        "persistent banner must not contain (ephemeral), got:\n" + banner);
  }

  @Test
  public void rejectsNullInputs() {
    assertThrows(NullPointerException.class, () -> QuickstartBanner.format(null));
  }

  @Test
  public void noSecurityWarningWhenFlagIsClear() {
    BannerInputs in = new BannerInputs("quickstart", "http://localhost:9995", "localhost:2181",
        "secret", "/tmp/accumulo-quickstart-12345", true, false);

    String banner = QuickstartBanner.format(in);
    assertTrue(!banner.contains("SECURITY WARNING"),
        "banner must not contain a security warning when the flag is clear, got:\n" + banner);
    assertTrue(!banner.contains("!!"),
        "banner must not contain the warning rule when the flag is clear, got:\n" + banner);
  }

  @Test
  public void securityWarningBlockExactStringWhenFlagIsSet() {
    BannerInputs in = new BannerInputs("quickstart", "http://accumulo:9995", "accumulo:2181",
        "secret", "/tmp/accumulo-quickstart-12345", true, true);

    String expected = String.join("\n",
        "==========================================================================",
        " Apache Accumulo Quickstart - ready", "", "   Instance:       quickstart",
        "   Monitor URL:    http://accumulo:9995", "   ZooKeeper:      accumulo:2181",
        "   Data dir:       /tmp/accumulo-quickstart-12345 (ephemeral)", "",
        "   Connect with the shell:",
        "     accumulo shell -zh accumulo:2181 -zi quickstart -u root -p secret", "",
        "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!",
        " !! SECURITY WARNING", " !!",
        " !! This cluster advertises a non-loopback address and is still using",
        " !! the default root password. Any machine or container that can reach",
        " !! it has full administrative control of the cluster and its data.", " !!",
        " !! Set ACCUMULO_ROOT_PASSWORD (or pass --root-password) to a strong",
        " !! value before exposing this cluster.",
        "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!", "",
        "   Press Ctrl-C to stop.",
        "==========================================================================", "");

    assertEquals(expected, QuickstartBanner.format(in));
  }
}
