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
package org.apache.accumulo.test.functional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.accumulo.harness.AccumuloITBase.MINI_CLUSTER_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.ProcessHandle;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Verifies the load-bearing claim in ADR-0007 (decision 3): after a dirty shutdown of the
 * quickstart process, a subsequent {@code bin/accumulo quickstart --data-dir <same-dir>} resume
 * preserves the data the user previously wrote, because Accumulo's existing crash-recovery
 * machinery owns WAL replay.
 *
 * <p>
 * This test failed deterministically before #43 was fixed: after SIGKILL of the quickstart and
 * resume on the same data dir, the manager re-hosted the root and metadata tablets within seconds
 * but never assigned the user-table tablet (observed over 15+ minutes of polling), so a scan hung
 * forever. The root cause was not the manager tablet-watcher loop but WAL durability on the local
 * filesystem: MAC ran {@code file://} through Hadoop's checksummed {@code LocalFileSystem}, whose
 * output stream buffers sub-checksum-chunk writes and ignores {@code hsync()}/{@code hflush()}, so
 * an abrupt kill left a 0-byte / header-less WAL. Recovery then produced an empty log, the metadata
 * mutations describing the freshly written user tablet were lost, and that tablet vanished from the
 * recovered metadata. The fix routes local volumes through {@code RawLocalFileSystem} (no checksum
 * buffering, real {@code hsync}); see {@code MiniAccumuloClusterImpl}.
 *
 * <p>
 * Subprocess, not in-process MAC: a forked {@code bin/accumulo quickstart} mirrors what a real user
 * runs, and SIGKILL on that process is the real "dirty shutdown" the ADR commits to surviving. The
 * in-process {@code QuickstartResumeTest} cannot exercise this path because MAC.stop() drives a
 * graceful SIGTERM-then-SIGKILL sequence and never an unconditional SIGKILL on the parent.
 */
@Tag(MINI_CLUSTER_ONLY)
public class QuickstartDirtyShutdownRecoveryIT {

  private static final Logger log =
      LoggerFactory.getLogger(QuickstartDirtyShutdownRecoveryIT.class);

  private static final String ROOT_PASSWORD = "secret";
  private static final String TABLE_NAME = "qsresume";
  private static final List<String[]> ROWS_TO_WRITE = List.of(new String[] {"r1", "cf", "cq", "v1"},
      new String[] {"r2", "cf", "cq", "v2"}, new String[] {"r3", "cf", "cq", "v3"});

  private static final String BANNER_READY_MARKER = "Apache Accumulo Quickstart - ready";
  private static final Pattern ZK_LINE = Pattern.compile("ZooKeeper:\\s+(\\S+)");
  private static final Pattern INSTANCE_LINE = Pattern.compile("Instance:\\s+(\\S+)");

  private static final Duration FRESH_BANNER_TIMEOUT = Duration.ofMinutes(2);
  // Resume traverses crash recovery (WAL replay, tablet reassignment) and can take noticeably
  // longer
  // than a fresh boot; allow generous headroom before declaring failure.
  private static final Duration RESUME_BANNER_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration SHELL_TIMEOUT = Duration.ofMinutes(2);

  @TempDir
  private Path workDir;

  private Path accumuloHome;
  private Path dataDir;

  // Track every Process we spawn so @AfterEach can guarantee teardown even when assertions fail
  // mid-test - otherwise an unhandled assert would leak a still-running quickstart JVM (and its
  // child JVMs) and the next test would collide on ports or marker locks.
  private final List<Process> spawnedProcesses = new ArrayList<>();

  @BeforeEach
  @SuppressFBWarnings(value = "COMMAND_INJECTION",
      justification = "tar command and tarball path are produced by the build, not user input")
  void unpackBinary() throws IOException, InterruptedException {
    Path tarball = locateAssembleTarball();
    Path unpackRoot = workDir.resolve("dist");
    Files.createDirectories(unpackRoot);
    log.info("Unpacking {} into {}", tarball, unpackRoot);
    Process tar = new ProcessBuilder("tar", "-xzf", tarball.toString(), "-C", unpackRoot.toString())
        .redirectErrorStream(true).start();
    if (!tar.waitFor(60, TimeUnit.SECONDS)) {
      tar.destroyForcibly();
      throw new IOException("tar extraction timed out");
    }
    if (tar.exitValue() != 0) {
      String out = new String(tar.getInputStream().readAllBytes(), UTF_8);
      throw new IOException("tar extraction failed (exit " + tar.exitValue() + "): " + out);
    }
    try (var children = Files.list(unpackRoot)) {
      accumuloHome =
          children.filter(Files::isDirectory).findFirst().orElseThrow(() -> new IOException(
              "unpacked tarball produced no directory under " + unpackRoot + " - layout changed?"));
    }
    Path accumuloBin = accumuloHome.resolve("bin/accumulo");
    if (!Files.isExecutable(accumuloBin)) {
      throw new IOException("expected executable at " + accumuloBin + " after unpack");
    }
    dataDir = workDir.resolve("data");
    log.info("accumuloHome={}, dataDir={}", accumuloHome, dataDir);
  }

  @AfterEach
  void killAllSpawnedProcesses() {
    for (Process p : spawnedProcesses) {
      if (p.isAlive()) {
        // Capture descendants before the parent dies; once the parent is reaped its descendants
        // get reparented to PID 1 (or a subreaper) and ProcessHandle.descendants() returns empty.
        List<ProcessHandle> descendants = p.toHandle().descendants().collect(Collectors.toList());
        p.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
        try {
          p.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        for (ProcessHandle d : descendants) {
          try {
            d.onExit().get(10, TimeUnit.SECONDS);
          } catch (Exception ignored) {
            // best effort; if a child won't die in 10s we surface it via a log and move on so we
            // don't block the test suite indefinitely on an orphan
            log.warn("descendant PID {} did not exit after SIGKILL", d.pid());
          }
        }
      }
    }
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void dirtyShutdownThenResumePreservesUserData() throws Exception {
    int portBase = pickFreePortBase();

    QuickstartHandle first = launchQuickstart("fresh", portBase, FRESH_BANNER_TIMEOUT);
    String zk = first.zooKeepers();
    String instance = first.instanceName();
    log.info("fresh quickstart ready: instance={}, zk={}, pid={}", instance, zk, first.pid());

    ShellResult create = runShell(zk, instance, String.format("createtable %s%n", TABLE_NAME)
        + writeRowsScript() + String.format("flush -t %s -w%n", TABLE_NAME));
    assertEquals(0, create.exitCode, "table create/insert/flush via shell failed (exit "
        + create.exitCode + "):\n" + create.combinedOutput);

    sigkillEntireProcessTree(first.process);

    QuickstartHandle resumed = launchQuickstart("resume", portBase, RESUME_BANNER_TIMEOUT);
    String zk2 = resumed.zooKeepers();
    String instance2 = resumed.instanceName();
    log.info("resumed quickstart ready: instance={}, zk={}, pid={}", instance2, zk2, resumed.pid());

    ShellResult scan = runShell(zk2, instance2, String.format("scan -t %s%n", TABLE_NAME));
    assertEquals(0, scan.exitCode,
        "scan via shell failed (exit " + scan.exitCode + "):\n" + scan.combinedOutput);
    for (String[] row : ROWS_TO_WRITE) {
      String expected = expectedScanLine(row);
      assertTrue(scan.combinedOutput.contains(expected), "post-resume scan missing row " + expected
          + ".\nFull scan output:\n" + scan.combinedOutput);
    }
  }

  // ----- helpers ----------------------------------------------------------------------------

  /**
   * Walks {@code user.dir} upward looking for the assemble tarball produced by {@code mvn package}.
   */
  private Path locateAssembleTarball() throws IOException {
    Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int hops = 0; hops < 6 && cursor != null; hops++) {
      Path candidateDir = cursor.resolve("assemble/target");
      if (Files.isDirectory(candidateDir)) {
        try (var stream = Files.list(candidateDir)) {
          Path match = stream.filter(p -> {
            String name = p.getFileName().toString();
            return name.startsWith("accumulo-") && name.endsWith("-bin.tar.gz");
          }).findFirst().orElse(null);
          if (match != null) {
            return match;
          }
        }
      }
      cursor = cursor.getParent();
    }
    throw new IOException("could not locate assemble/target/accumulo-*-bin.tar.gz from "
        + System.getProperty("user.dir") + " - did `mvn package` run for the assemble module?");
  }

  /**
   * Pick four contiguous unprivileged ports that all bind successfully right now. The first port is
   * returned as {@code --port-base}. Re-tries because ports we briefly opened can race with anyone
   * else on the box; in CI with failsafe forkCount=1 we are the only test running but a developer
   * box can have arbitrary other servers.
   */
  @SuppressFBWarnings(value = {"PREDICTABLE_RANDOM", "UNENCRYPTED_SERVER_SOCKET"},
      justification = "non-crypto random is fine for picking a test port; the socket is only a"
          + " short-lived probe to confirm the port is free")
  private int pickFreePortBase() throws IOException {
    for (int attempt = 0; attempt < 50; attempt++) {
      int base = ThreadLocalRandom.current().nextInt(20000, 60000 - 4);
      boolean allFree = true;
      List<ServerSocket> opened = new ArrayList<>();
      try {
        for (int i = 0; i < 4; i++) {
          try {
            opened.add(new ServerSocket(base + i));
          } catch (IOException e) {
            allFree = false;
            break;
          }
        }
        if (allFree) {
          return base;
        }
      } finally {
        for (ServerSocket s : opened) {
          try {
            s.close();
          } catch (IOException ignored) {
            // closing the probe socket is best-effort; the worst case is OS reclaims it later
          }
        }
      }
    }
    throw new IOException("could not find four contiguous free ports after 50 attempts");
  }

  @SuppressFBWarnings(value = {"COMMAND_INJECTION", "HARD_CODE_PASSWORD"},
      justification = "accumulo binary path and quickstart args are produced by the test, not user"
          + " input; the root password is a throwaway test credential")
  private QuickstartHandle launchQuickstart(String label, int portBase, Duration bannerTimeout)
      throws IOException, InterruptedException, TimeoutException {
    ProcessBuilder pb = new ProcessBuilder(accumuloHome.resolve("bin/accumulo").toString(),
        "quickstart", "--data-dir", dataDir.toString(), "--port-base", String.valueOf(portBase),
        "--root-password", ROOT_PASSWORD);
    pb.environment().put("ACCUMULO_ROOT_PASSWORD", ROOT_PASSWORD);
    // accumulo-env.sh's CLASSPATH macro reads $basedir/lib/quickstart/* relative to the unpacked
    // bin/, so an inherited CLASSPATH from the test JVM would corrupt the quickstart classpath.
    pb.environment().remove("CLASSPATH");
    pb.redirectErrorStream(true);
    log.info("launching ({}): {}", label, pb.command());
    Process process = pb.start();
    spawnedProcesses.add(process);

    StringBuilder collected = new StringBuilder();
    AtomicReference<String> bannerHolder = new AtomicReference<>();
    Thread reader = new Thread(() -> {
      try (BufferedReader br =
          new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
        String line;
        StringBuilder bannerBuf = null;
        while ((line = br.readLine()) != null) {
          synchronized (collected) {
            collected.append(line).append('\n');
          }
          if (line.contains(BANNER_READY_MARKER)) {
            bannerBuf = new StringBuilder();
            bannerBuf.append(line).append('\n');
          } else if (bannerBuf != null) {
            bannerBuf.append(line).append('\n');
            // The banner closes with a divider of '=' characters; once we have ZooKeeper and
            // Instance lines we can publish a complete enough snapshot for parsing.
            if (line.contains("Press Ctrl-C to stop.")) {
              bannerHolder.compareAndSet(null, bannerBuf.toString());
              bannerBuf = null;
            }
          }
        }
      } catch (IOException e) {
        log.debug("quickstart stdout reader for {} stopped: {}", label, e.toString());
      }
    }, "quickstart-stdout-" + label);
    reader.setDaemon(true);
    reader.start();

    Instant deadline = Instant.now().plus(bannerTimeout);
    while (Instant.now().isBefore(deadline)) {
      if (bannerHolder.get() != null) {
        break;
      }
      if (!process.isAlive()) {
        synchronized (collected) {
          fail("quickstart (" + label + ") exited with code " + process.exitValue()
              + " before banner appeared. Output:\n" + collected);
        }
      }
      Thread.sleep(250);
    }
    String banner = bannerHolder.get();
    if (banner == null) {
      synchronized (collected) {
        fail("quickstart (" + label + ") did not print readiness banner within " + bannerTimeout
            + ". Output so far:\n" + collected);
      }
    }

    String zk = parseLine(ZK_LINE, banner,
        "ZooKeeper line missing from banner (" + label + "):\n" + banner);
    String instance = parseLine(INSTANCE_LINE, banner,
        "Instance line missing from banner (" + label + "):\n" + banner);
    return new QuickstartHandle(process, instance, zk);
  }

  private static String parseLine(Pattern pattern, String banner, String failMessage) {
    Matcher m = pattern.matcher(banner);
    if (!m.find()) {
      fail(failMessage);
    }
    return m.group(1);
  }

  private String writeRowsScript() {
    StringBuilder sb = new StringBuilder();
    for (String[] row : ROWS_TO_WRITE) {
      sb.append("table ").append(TABLE_NAME).append('\n');
      sb.append("insert ").append(row[0]).append(' ').append(row[1]).append(' ').append(row[2])
          .append(' ').append(row[3]).append('\n');
    }
    return sb.toString();
  }

  /**
   * Expected substring on a scan line, format produced by the shell: {@code row cf:cq []    value}.
   * Authorization label is empty for our default-auths writes.
   */
  private static String expectedScanLine(String[] row) {
    return row[0] + " " + row[1] + ":" + row[2] + " []    " + row[3];
  }

  @SuppressFBWarnings(value = "COMMAND_INJECTION",
      justification = "accumulo binary path and shell args are produced by the test, not user input")
  private ShellResult runShell(String zooKeepers, String instanceName, String command)
      throws IOException, InterruptedException, TimeoutException {
    ProcessBuilder pb = new ProcessBuilder(accumuloHome.resolve("bin/accumulo").toString(), "shell",
        "-zh", zooKeepers, "-zi", instanceName, "-u", "root", "-p", ROOT_PASSWORD, "-e", command);
    pb.environment().remove("CLASSPATH");
    pb.redirectErrorStream(true);
    log.info("shell command: {}", command.replace("\n", " ; "));
    Process shell = pb.start();
    spawnedProcesses.add(shell);
    StringBuilder out = new StringBuilder();
    Thread reader = drainStdoutAsync(shell.getInputStream(), out, "shell-stdout");
    if (!shell.waitFor(SHELL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      shell.destroyForcibly();
      throw new TimeoutException("shell command timed out after " + SHELL_TIMEOUT);
    }
    reader.join(2000);
    return new ShellResult(shell.exitValue(), out.toString());
  }

  private static Thread drainStdoutAsync(InputStream in, StringBuilder sink, String name) {
    Thread t = new Thread(() -> {
      try (BufferedReader br = new BufferedReader(new InputStreamReader(in, UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
          synchronized (sink) {
            sink.append(line).append('\n');
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }, name);
    t.setDaemon(true);
    t.start();
    return t;
  }

  /**
   * Simulates power loss: SIGKILL the quickstart parent and every JVM it spawned, in one shot, with
   * no opportunity for graceful shutdown. Descendants are captured up-front because reaping the
   * parent reparents them and {@code parent.toHandle().descendants()} would return empty after.
   */
  private void sigkillEntireProcessTree(Process parent) throws InterruptedException {
    assertNotNull(parent);
    assertTrue(parent.isAlive(), "parent quickstart already exited before SIGKILL");
    List<ProcessHandle> descendants = parent.toHandle().descendants().collect(Collectors.toList());
    log.info("SIGKILLing pid={} and {} descendants", parent.pid(), descendants.size());
    parent.destroyForcibly();
    descendants.forEach(ProcessHandle::destroyForcibly);
    if (!parent.waitFor(30, TimeUnit.SECONDS)) {
      fail("parent quickstart pid=" + parent.pid() + " did not exit within 30s of SIGKILL");
    }
    for (ProcessHandle d : descendants) {
      try {
        d.onExit().get(30, TimeUnit.SECONDS);
      } catch (Exception e) {
        fail("descendant pid=" + d.pid() + " did not exit within 30s of SIGKILL: " + e);
      }
    }
  }

  private static final class QuickstartHandle {
    final Process process;
    private final String instanceName;
    private final String zooKeepers;

    QuickstartHandle(Process process, String instanceName, String zooKeepers) {
      this.process = process;
      this.instanceName = instanceName;
      this.zooKeepers = zooKeepers;
    }

    long pid() {
      return process.pid();
    }

    String instanceName() {
      return instanceName;
    }

    String zooKeepers() {
      return zooKeepers;
    }
  }

  private static final class ShellResult {
    final int exitCode;
    final String combinedOutput;

    ShellResult(int exitCode, String combinedOutput) {
      this.exitCode = exitCode;
      this.combinedOutput = combinedOutput;
    }
  }
}
