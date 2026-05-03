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

import org.apache.accumulo.minicluster.Quickstart;
import org.apache.accumulo.start.spi.KeywordExecutable;

import com.google.auto.service.AutoService;

/**
 * Registers {@code accumulo quickstart} with the start-module command dispatcher. Listed in the
 * CORE usage group so it surfaces under "Core Commands" in {@code accumulo --help} - reflecting its
 * first-class status as the recommended local-evaluation entry point.
 */
@AutoService(KeywordExecutable.class)
public class QuickstartExecutable implements KeywordExecutable {

  @Override
  public String keyword() {
    return "quickstart";
  }

  @Override
  public UsageGroup usageGroup() {
    return UsageGroup.CORE;
  }

  @Override
  public String description() {
    return "Starts an ephemeral local Accumulo cluster with quickstart defaults";
  }

  @Override
  public void execute(String[] args) throws Exception {
    Quickstart.main(args);
  }
}
