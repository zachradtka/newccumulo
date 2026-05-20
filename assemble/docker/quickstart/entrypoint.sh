#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

set -euo pipefail

# When the caller mounts a host directory at /data we must reset ownership so
# the unprivileged accumulo user can write to it. Skip when /data is unmounted
# or already owned by accumulo - avoids a pointless chown of the declared
# VOLUME on every container start.
if [[ -d /data ]]; then
    current_owner="$(stat -c '%u:%g' /data)"
    if [[ "${current_owner}" != "1000:1000" ]]; then
        chown accumulo:accumulo /data
    fi
fi

# The default CMD is `quickstart`, which we route to bin/accumulo quickstart.
# Anything else (a literal binary path, `shell`, `info`, ...) is exec'd as-is
# so users can `docker run ... bash` for debugging or `docker exec` an
# `accumulo shell` against a running cluster.
if [[ "${1:-}" == "quickstart" ]]; then
    shift
    exec gosu accumulo /opt/accumulo/bin/accumulo quickstart "$@"
fi

exec gosu accumulo "$@"
