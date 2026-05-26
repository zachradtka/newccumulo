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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.minicluster.MemoryUnit;
import org.apache.accumulo.minicluster.ServerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "paths not set by user input")
public class MiniAccumuloConfigImplTest {

  @TempDir
  private File tempFolder;

  private File subDir(String name) {
    return new File(tempFolder, name);
  }

  @Test
  public void testZookeeperPort() {

    // set specific zookeeper port
    MiniAccumuloConfigImpl config =
        new MiniAccumuloConfigImpl(subDir("port"), "password").setZooKeeperPort(5000).initialize();
    assertEquals(5000, config.getZooKeeperPort());

    // generate zookeeper port
    config = new MiniAccumuloConfigImpl(subDir("port-rand"), "password").initialize();
    assertTrue(config.getZooKeeperPort() > 0);
  }

  @Test
  public void testZooKeeperStartupTime() {

    // set specific zookeeper startup time
    MiniAccumuloConfigImpl config = new MiniAccumuloConfigImpl(subDir("zk-startup"), "password")
        .setZooKeeperStartupTime(5000).initialize();
    assertEquals(5000, config.getZooKeeperStartupTime());
  }

  @Test
  public void testSiteConfig() {

    // constructor site config overrides default props
    Map<String,String> siteConfig = new HashMap<>();
    siteConfig.put(Property.INSTANCE_VOLUMES.getKey(), "hdfs://");
    MiniAccumuloConfigImpl config = new MiniAccumuloConfigImpl(subDir("site"), "password")
        .setSiteConfig(siteConfig).initialize();
    assertEquals("hdfs://", config.getSiteConfig().get(Property.INSTANCE_VOLUMES.getKey()));
  }

  @Test
  public void testMemoryConfig() {

    MiniAccumuloConfigImpl config =
        new MiniAccumuloConfigImpl(subDir("memory"), "password").initialize();
    config.setDefaultMemory(96, MemoryUnit.MEGABYTE);
    assertEquals(96 * 1024 * 1024L, config.getMemory(ServerType.MANAGER));
    assertEquals(96 * 1024 * 1024L, config.getMemory(ServerType.TABLET_SERVER));
    assertEquals(96 * 1024 * 1024L, config.getDefaultMemory());
    config.setMemory(ServerType.MANAGER, 256, MemoryUnit.MEGABYTE);
    assertEquals(256 * 1024 * 1024L, config.getMemory(ServerType.MANAGER));
    assertEquals(96 * 1024 * 1024L, config.getDefaultMemory());
    assertEquals(96 * 1024 * 1024L, config.getMemory(ServerType.TABLET_SERVER));
  }

  @Test
  public void freshInitializeWritesMarkerFile() throws IOException {
    File dir = subDir("fresh-marker");
    MiniAccumuloConfigImpl config =
        new MiniAccumuloConfigImpl(dir, "password").setInstanceName("freshmac").initialize();
    assertFalse(config.isResumeMode());

    File marker = new File(dir, MiniAccumuloConfigImpl.MAC_MARKER_FILENAME);
    assertTrue(marker.exists(), "marker file should exist after fresh initialize()");

    Properties props = new Properties();
    try (FileInputStream fis = new FileInputStream(marker)) {
      props.load(fis);
    }
    assertEquals("1", props.getProperty("mac.marker.version"));
    assertEquals(Constants.VERSION, props.getProperty("accumulo.version"));
    assertEquals("freshmac", props.getProperty("instance.name"));
    assertNotNull(props.getProperty("created.at"));
  }

  @Test
  public void resumeReturnsThisAndSetsFlag() {
    MiniAccumuloConfigImpl config = new MiniAccumuloConfigImpl(subDir("resume-flag"), "password");
    assertFalse(config.isResumeMode());
    MiniAccumuloConfigImpl returned = config.resume();
    assertEquals(config, returned, ".resume() should return this");
    assertTrue(config.isResumeMode());
  }

  @Test
  public void resumeAndUseExistingInstanceAreMutuallyExclusive() throws Exception {
    File freshDir = subDir("mutex-fresh");
    assertTrue(freshDir.mkdirs());
    // build a valid accumulo.properties so useExistingInstance can read it
    File props = new File(freshDir, "accumulo.properties");
    try (FileWriter fw = new FileWriter(props, UTF_8)) {
      fw.write("instance.volumes=file:///tmp/fake\n");
    }

    // resume() after useExistingInstance(...) throws
    MiniAccumuloConfigImpl attach = new MiniAccumuloConfigImpl(subDir("mutex-attach"), "password")
        .useExistingInstance(props, freshDir);
    assertThrows(UnsupportedOperationException.class, attach::resume);

    // useExistingInstance(...) after resume() throws
    MiniAccumuloConfigImpl resume =
        new MiniAccumuloConfigImpl(subDir("mutex-resume"), "password").resume();
    assertThrows(UnsupportedOperationException.class,
        () -> resume.useExistingInstance(props, freshDir));
  }

  @Test
  public void resumeRefusesWhenMarkerAbsent() throws IOException {
    File dir = subDir("no-marker");
    assertTrue(dir.mkdirs());
    // make the dir non-empty so resume mode is the only thing that would let initialize() proceed
    File stale = new File(dir, "stale.txt");
    try (FileWriter fw = new FileWriter(stale, UTF_8)) {
      fw.write("not a MAC data dir\n");
    }

    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> new MiniAccumuloConfigImpl(dir, "password").resume().initialize());
    String expected = "Refusing to use " + dir.getAbsolutePath()
        + " as a data dir: not a MAC quickstart directory "
        + "(missing mac-instance.properties marker). "
        + "If you intend to start fresh here, delete " + dir.getAbsolutePath() + " and run again.";
    assertEquals(expected, ex.getMessage());
  }

  @Test
  public void resumeRefusesWhenMarkerCorruptIsUnparseable() throws IOException {
    File dir = subDir("marker-unparseable");
    // write a fresh marker so the dir starts as a valid MAC quickstart dir
    new MiniAccumuloConfigImpl(dir, "password").initialize();
    File marker = new File(dir, MiniAccumuloConfigImpl.MAC_MARKER_FILENAME);

    // Overwrite with a malformed unicode escape so Properties.load throws
    // IllegalArgumentException. The literal is split so the Java lexer does not try to interpret
    // the source's backslash-u sequence as an actual unicode escape.
    try (FileWriter fw = new FileWriter(marker, UTF_8)) {
      fw.write("garbage=\\" + "uZZZZ\n");
    }

    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> new MiniAccumuloConfigImpl(dir, "password").resume().initialize());
    String expected = "Refusing to resume: " + marker.getAbsolutePath()
        + " is corrupt or missing required fields. Delete the directory to re-initialize.";
    assertEquals(expected, ex.getMessage());
  }

  @Test
  public void resumeRefusesWhenMarkerMissingRequiredField() throws IOException {
    for (String required : MiniAccumuloConfigImpl.REQUIRED_MARKER_FIELDS) {
      File dir = subDir("missing-" + required);
      new MiniAccumuloConfigImpl(dir, "password").setInstanceName("req").initialize();
      File marker = new File(dir, MiniAccumuloConfigImpl.MAC_MARKER_FILENAME);

      Properties props = new Properties();
      try (FileInputStream fis = new FileInputStream(marker)) {
        props.load(fis);
      }
      props.remove(required);
      try (FileWriter fw = new FileWriter(marker, UTF_8)) {
        props.store(fw, "tampered: missing " + required);
      }

      IllegalStateException ex = assertThrows(IllegalStateException.class,
          () -> new MiniAccumuloConfigImpl(dir, "password").resume().initialize(),
          "expected refusal when " + required + " is absent");
      String expected = "Refusing to resume: " + marker.getAbsolutePath()
          + " is corrupt or missing required fields. Delete the directory to re-initialize.";
      assertEquals(expected, ex.getMessage(), "wrong message for missing field " + required);
    }
  }

  @Test
  public void resumeRefusesOnNewerMarkerSchemaVersion() throws IOException {
    File dir = subDir("schema-newer");
    new MiniAccumuloConfigImpl(dir, "password").initialize();
    File marker = new File(dir, MiniAccumuloConfigImpl.MAC_MARKER_FILENAME);

    Properties props = new Properties();
    try (FileInputStream fis = new FileInputStream(marker)) {
      props.load(fis);
    }
    props.setProperty("mac.marker.version", "2");
    try (FileWriter fw = new FileWriter(marker, UTF_8)) {
      props.store(fw, "tampered: newer marker schema");
    }

    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> new MiniAccumuloConfigImpl(dir, "password").resume().initialize());
    String expected = "Refusing to resume: " + dir.getAbsolutePath()
        + " was initialized by a newer MAC " + "(marker version 2, this binary expects 1). "
        + "Upgrade your MAC binary, or delete and re-initialize.";
    assertEquals(expected, ex.getMessage());
  }

  @Test
  public void resumeRefusesOnVersionMismatch() throws IOException {
    File dir = subDir("version-mismatch");
    // write a fresh marker
    new MiniAccumuloConfigImpl(dir, "password").setInstanceName("ver").initialize();
    File marker = new File(dir, MiniAccumuloConfigImpl.MAC_MARKER_FILENAME);
    assertTrue(marker.exists());

    // tamper with the marker's accumulo.version
    Properties props = new Properties();
    try (FileInputStream fis = new FileInputStream(marker)) {
      props.load(fis);
    }
    props.setProperty("accumulo.version", "9.99.99-FAKE");
    try (FileWriter fw = new FileWriter(marker, UTF_8)) {
      props.store(fw, "tampered for test");
    }

    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> new MiniAccumuloConfigImpl(dir, "password").resume().initialize());
    String expected = "Refusing to resume: data dir " + dir.getAbsolutePath()
        + " was initialized with Accumulo 9.99.99-FAKE, current binary is " + Constants.VERSION
        + ".\n" + "\n" + "This quickstart does not migrate data across versions. To proceed:\n"
        + "  - Delete " + dir.getAbsolutePath()
        + " and run again to re-initialize with this binary, OR\n"
        + "  - Re-install Accumulo 9.99.99-FAKE to match the persisted data.";
    assertEquals(expected, ex.getMessage());
  }

  @Test
  public void resumeSkipsEmptyDirGuardAndLoadsLockedFields() throws IOException {
    File dir = subDir("resume-locked");
    // fresh init writes marker + accumulo.properties shell
    new MiniAccumuloConfigImpl(dir, "password").setInstanceName("locked").initialize();

    // simulate the persisted accumulo.properties that start() would have written
    File confDir = new File(dir, "conf");
    assertTrue(confDir.mkdirs() || confDir.isDirectory());
    Properties persisted = new Properties();
    persisted.setProperty(Property.INSTANCE_VOLUMES.getKey(),
        "file://" + new File(dir, "accumulo").getAbsolutePath());
    persisted.setProperty(Property.INSTANCE_SECRET.getKey(), "persistedSecret");
    persisted.setProperty(Property.INSTANCE_ZK_HOST.getKey(), "127.0.0.1:12345");
    try (FileWriter fw = new FileWriter(new File(confDir, "accumulo.properties"), UTF_8)) {
      persisted.store(fw, null);
    }

    // resume: empty-dir guard must NOT fire, locked fields come from persisted, user's
    // attempted override of INSTANCE_SECRET is ignored.
    Map<String,String> userSite = new HashMap<>();
    userSite.put(Property.INSTANCE_SECRET.getKey(), "userOverride");
    MiniAccumuloConfigImpl resumed =
        new MiniAccumuloConfigImpl(dir, "password").resume().setSiteConfig(userSite).initialize();
    assertTrue(resumed.isResumeMode());
    assertEquals("persistedSecret", resumed.getSiteConfig().get(Property.INSTANCE_SECRET.getKey()),
        "locked instance.secret must come from persisted accumulo.properties");
    assertEquals("locked", resumed.getInstanceName(), "instance name should come from marker");
  }

}
