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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;

/**
 * Polls a started {@link MiniAccumuloCluster} until it is accepting client traffic, or until the
 * configured timeout elapses. Success means we successfully opened a client and listed tables -
 * proof that the manager and tablet server are registered in ZooKeeper and serving the metadata
 * table.
 */
public final class QuickstartReadinessCheck {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

  private final Duration timeout;

  public QuickstartReadinessCheck(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive, got " + timeout);
    }
    this.timeout = timeout;
  }

  /** Outcome of one readiness probe. */
  public static final class Result {

    private final boolean success;
    private final Duration elapsed;
    private final String detail;

    public Result(boolean success, Duration elapsed, String detail) {
      this.success = success;
      this.elapsed = Objects.requireNonNull(elapsed, "elapsed");
      this.detail = Objects.requireNonNull(detail, "detail");
    }

    public boolean success() {
      return success;
    }

    public Duration elapsed() {
      return elapsed;
    }

    public String detail() {
      return detail;
    }

    @Override
    public String toString() {
      return "Result{success=" + success + ", elapsed=" + elapsed + ", detail=" + detail + "}";
    }
  }

  /**
   * Probe {@code cluster} repeatedly until {@link AccumuloClient#tableOperations()} succeeds or the
   * timeout elapses. Always returns - never throws on a probe failure.
   */
  public Result awaitReady(MiniAccumuloCluster cluster, String user, String password) {
    Objects.requireNonNull(cluster, "cluster");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(password, "password");

    long startNanos = System.nanoTime();
    long deadlineNanos = startNanos + timeout.toNanos();
    Throwable lastFailure = null;

    while (System.nanoTime() < deadlineNanos) {
      try (
          AccumuloClient client = cluster.createAccumuloClient(user, new PasswordToken(password))) {
        client.tableOperations().list();
        return new Result(true, elapsedSince(startNanos), "cluster accepting traffic");
      } catch (Exception e) {
        lastFailure = e;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL.toMillis()));
    }

    String detail =
        "timed out after " + timeout + (lastFailure == null ? "" : "; last error: " + lastFailure);
    return new Result(false, elapsedSince(startNanos), detail);
  }

  private static Duration elapsedSince(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
  }
}
