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

import java.util.Objects;

/**
 * Pure formatting function that turns a {@link BannerInputs} value into the user-facing ready
 * banner. No I/O, no global state - the same input always produces the same output, which makes the
 * banner trivially testable via string equality.
 */
public final class QuickstartBanner {

  private static final String DIVIDER =
      "==========================================================================";

  private QuickstartBanner() {}

  /**
   * Inputs to the banner formatter. {@code dataDirEphemeral} controls whether the data dir line is
   * tagged {@code (ephemeral)} (Phase 1) or shown as a plain path (Phase 2 persistent mode).
   */
  public static final class BannerInputs {

    private final String instanceName;
    private final String monitorUrl;
    private final String zooKeepers;
    private final String rootPassword;
    private final String dataDir;
    private final boolean dataDirEphemeral;

    public BannerInputs(String instanceName, String monitorUrl, String zooKeepers,
        String rootPassword, String dataDir, boolean dataDirEphemeral) {
      this.instanceName = Objects.requireNonNull(instanceName, "instanceName");
      this.monitorUrl = Objects.requireNonNull(monitorUrl, "monitorUrl");
      this.zooKeepers = Objects.requireNonNull(zooKeepers, "zooKeepers");
      this.rootPassword = Objects.requireNonNull(rootPassword, "rootPassword");
      this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
      this.dataDirEphemeral = dataDirEphemeral;
    }

    public String instanceName() {
      return instanceName;
    }

    public String monitorUrl() {
      return monitorUrl;
    }

    public String zooKeepers() {
      return zooKeepers;
    }

    public String rootPassword() {
      return rootPassword;
    }

    public String dataDir() {
      return dataDir;
    }

    public boolean dataDirEphemeral() {
      return dataDirEphemeral;
    }
  }

  /**
   * Format the ready banner. Output ends with a trailing newline so callers can write it directly
   * to {@link System#out}.
   */
  public static String format(BannerInputs in) {
    Objects.requireNonNull(in, "in");
    String dataDirLine = in.dataDirEphemeral() ? in.dataDir() + " (ephemeral)" : in.dataDir();
    StringBuilder sb = new StringBuilder();
    sb.append(DIVIDER).append('\n');
    sb.append(" Apache Accumulo Quickstart - ready").append('\n');
    sb.append('\n');
    sb.append("   Instance:       ").append(in.instanceName()).append('\n');
    sb.append("   Monitor URL:    ").append(in.monitorUrl()).append('\n');
    sb.append("   ZooKeeper:      ").append(in.zooKeepers()).append('\n');
    sb.append("   Data dir:       ").append(dataDirLine).append('\n');
    sb.append('\n');
    sb.append("   Connect with the shell:").append('\n');
    sb.append("     accumulo shell -zh ").append(in.zooKeepers()).append(" -zi ")
        .append(in.instanceName()).append(" -u root -p ").append(in.rootPassword()).append('\n');
    sb.append('\n');
    sb.append("   Press Ctrl-C to stop.").append('\n');
    sb.append(DIVIDER).append('\n');
    return sb.toString();
  }
}
