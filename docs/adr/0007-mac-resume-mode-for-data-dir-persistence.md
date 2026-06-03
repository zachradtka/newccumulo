# MAC resume mode for `--data-dir` persistence

Status: accepted

## Context

Phase 1 of the quickstart work (parent: GitHub issue #1) ships a `--data-dir`
flag in `bin/accumulo quickstart` but rejects it at parse time with a "not
yet supported" pointer to GitHub issue #8. Phase 2 (this ADR's scope) makes
the flag actually work: a user who runs the quickstart against the same
`--data-dir` twice should see their data the second time.

Today, `MiniAccumuloConfigImpl.initialize()` unconditionally throws if the
target data directory is non-empty
([MiniAccumuloConfigImpl.java:151-156](../../minicluster/src/main/java/org/apache/accumulo/miniclusterImpl/MiniAccumuloConfigImpl.java#L151-L156)).
This guard exists because MAC was designed as test infrastructure where every
run is fresh; the quickstart product use case inverts that assumption.

MAC also already has an `existingInstance` knob and a public
`useExistingInstance(File accumuloProps, File hadoopConfDir)` method, which
configures MAC to attach to a separately-running Accumulo instance that some
other process spawned. It is tempting to fold persistence-resume into the same
knob, but the two operations have different invariants (see "Decision" below).

This ADR records the six design decisions that resolve the open questions on
GitHub issue #8 before implementation begins.

## Decision

### 1. Resume is a distinct lifecycle mode, not a generalization of attach

MAC operates in exactly one of three modes per run, chosen at config time:
**fresh**, **attach**, or **resume**. These are defined in
[CONTEXT.md](../../CONTEXT.md) under "MAC lifecycle modes."

Resume and attach are not unified. They have different invariants:

- **Attach** assumes ZooKeeper and HDFS are already running, trusts a
  user-supplied `accumulo.properties` and Hadoop conf dir blindly, and does
  not own the data directory.
- **Resume** assumes nothing is currently running, requires the data dir to
  have been previously written by a MAC run (verified via marker file), and
  fully owns the lifecycle including spawning ZooKeeper.

A new `resumeMode` boolean is added to `MiniAccumuloConfigImpl` alongside the
existing `existingInstance` field. The two are mutually exclusive: setting one
when the other is already set throws.

### 2. Resume is opted into via a fluent `.resume()` method

`MiniAccumuloConfigImpl` gets a new method `public MiniAccumuloConfigImpl
resume()` that returns `this`, symmetric with the existing
`useExistingInstance(...)` builder method.

Auto-detection of resume from the data dir's filesystem state was rejected:
every existing MAC caller expects "non-empty dir → throw" as a guard against
accidental test pollution. Resume must be an explicit caller decision.

The **Quickstart layer**, not MAC, owns the runtime decision of when to call
`.resume()`. MAC stays narrow and executes whichever mode it is told. The
Quickstart layer implements this decision tree:

| State of `--data-dir` | Decision | Mode |
|---|---|---|
| Not supplied | Ephemeral temp dir | Fresh |
| Doesn't exist or empty | Create + use | Fresh |
| Non-empty, no `mac-instance.properties` marker | Refuse: "not a MAC data dir" | — |
| Marker present but unparseable or missing required fields | Refuse: "marker corrupt" | — |
| Marker present, `mac.marker.version` ≠ 1 | Refuse: "marker from incompatible MAC build" | — |
| Marker present, `accumulo.version` mismatch | Refuse: Q5 version-mismatch message | — |
| Marker present, all checks pass | Use | Resume |

Every refusal carries a distinct, specific error message — never a generic
"invalid data dir."

`initialize()` in fresh mode unconditionally writes the marker file. There is
no separate opt-in to "make this resumable later" — any MAC-initialized data
dir is potentially resumable. This avoids "I forgot to enable resume-on-first-init"
bugs.

### 3. Recoverability bar: marker matches = safe to resume

MAC does no pre-flight validation beyond the marker check. All crash recovery
is delegated to Accumulo's existing machinery:

- Tablet servers replay the write-ahead log on startup.
- ZooKeeper replays its transaction log on startup.
- Stale ZK ephemeral locks from the previous JVM's dead session are
  auto-cleaned by ZooKeeper.
- Stale tablet-location pointers self-heal as the manager re-elects on
  tserver heartbeat loss.

If Accumulo's own recovery cannot cope with what is on disk, that is a bug
in Accumulo and out of scope for this ADR.

> **Durability prerequisite (issue #43).** Delegating to Accumulo's recovery
> only works if acknowledged writes are actually on disk before the abrupt
> stop. On a single-node `--data-dir` cluster MAC writes to the local
> filesystem, and Hadoop's default `file://` handler (the checksummed
> `LocalFileSystem`) buffers sub-checksum-chunk writes and ignores
> `hsync()`/`hflush()`. An abrupt kill (power loss, `docker kill`, `kill -9`)
> therefore left a 0-byte / header-less WAL; recovery produced an empty log,
> the metadata mutations describing freshly written user tablets were lost, and
> those tablets never came back online (scans hung indefinitely). MAC now
> routes local volumes through `RawLocalFileSystem`, which performs no checksum
> buffering and whose `hsync()` issues a real `fsync`, so acknowledged WAL
> writes survive a dirty shutdown and the delegation above holds.

### 4. Property locking on resume

On resume, MAC reads the persisted `conf/accumulo.properties` and
`conf/client.properties`. Some properties are loaded from the persisted files
and refuse user override; others are taken from the user's current config and
the on-disk files are rewritten.

| Property | Locked (persisted wins; mismatch refuses) | Overridable (current run wins) |
|---|---|---|
| `instance.name` | ✓ | |
| `instance.secret` | ✓ | |
| `instance.volumes` | ✓ | |
| root user / root password | ✓ — mismatch with `--root-password` refuses with a clear message | |
| ZK, monitor, tserver, manager ports | | ✓ |
| advertise host, bind host | | ✓ |
| heap sizes | | ✓ |
| tserver / compactor / scan-server counts | | ✓ |

A `--root-password` value that does not match the persisted root password is
refused at startup with a clear message, not silently overridden.

### 5. Marker file format and contents

The marker file is named `mac-instance.properties`, lives at the data-dir
root (not under `conf/`), and uses Java Properties format. It is written by
`initialize()` in fresh mode and never modified thereafter.

```properties
# MiniAccumuloCluster data directory marker
# Auto-generated. Do not edit.
# Deleting this file makes the data directory unrecognizable to MAC; the only
# recovery is to delete the directory entirely and re-initialize.

mac.marker.version=1
accumulo.version=4.0.0-SNAPSHOT
instance.name=quickstart
created.at=2026-05-26T14:23:01Z
```

`mac.marker.version` tracks the schema of this file format and is independent
of `accumulo.version`. It starts at 1 and is bumped when the marker schema
changes incompatibly. Unknown keys are ignored on read, so additive evolution
within a schema version does not require a bump.

Explicitly **not** in the marker:
- Root password or hash (lives in persisted `client.properties`)
- Instance secret (lives in persisted `accumulo.properties`)
- Checksums of the data dir (not our job — see decision 3)
- "Last clean shutdown" flag (not our job — see decision 3)

### 6. Version-pin policy: exact-string equality of `Constants.VERSION`

The marker's `accumulo.version` field is compared character-for-character
against the current binary's `org.apache.accumulo.core.Constants.VERSION` on
every resume. Any mismatch refuses with the message:

```
Refusing to resume: data dir /path was initialized with Accumulo X, current
binary is Y.

This quickstart does not migrate data across versions. To proceed:
  - Delete /path and run again to re-initialize with this binary, OR
  - Re-install Accumulo X to match the persisted data.
```

No semver-prefix tolerance. No `AccumuloDataVersion` comparison. No
`--force-version-mismatch` escape hatch. Forks, branch builds, and
SNAPSHOT/release pairings are all governed by the same rule: identical
strings or no resume.

This fork **commits to keeping its `Constants.VERSION` distinguishable from
upstream Apache Accumulo's**. If newccumulo ever mints version strings that
collide with upstream's, the marker schema must be bumped to
`mac.marker.version=2` and a `mac.product` field must be added to disambiguate
fork-from-upstream. We do not add `mac.product` today; the constraint on
fork versioning is the cheaper path.

## Considered Options

- **Unify resume into the existing `existingInstance` knob** — rejected.
  Different invariants (running vs. quiesced, attach vs. own-lifecycle) would
  force one branch to handle two semantically distinct operations. The
  upstream `// TODO Nuke existingInstance` comment at
  [MiniAccumuloConfigImpl.java:114](../../minicluster/src/main/java/org/apache/accumulo/miniclusterImpl/MiniAccumuloConfigImpl.java#L114)
  also signals attach mode may be deleted; conflating resume into it makes
  that future deletion harder.

- **Auto-detect resume from filesystem state** (no API change, MAC inspects
  the dir and resumes silently if it looks resumable) — rejected.
  Silently resuming on a "looks-like-MAC" dir would mask real bugs in test
  setup, where existing MAC callers rely on the non-empty-dir guard.

- **Static factory `MiniAccumuloConfigImpl.resume(dir)`** — rejected. Implies
  resume is a different *type* of config object. It is not; it is the same
  config with a different lifecycle disposition. Also breaks symmetry with
  the existing fluent builder API.

- **Match on `AccumuloDataVersion` instead of `Constants.VERSION`** —
  rejected for Phase 2. That integer is the actual on-disk compatibility
  boundary and would allow patch-version resume, but reusing Accumulo's
  upgrade machinery inside MAC is meaningfully more work, and the failure
  mode of getting it subtly wrong is data corruption. Revisit if patch-version
  friction becomes a real user complaint.

- **`--force-version-mismatch` / `--force-foreign-dir` escape hatches** —
  rejected. The cost of a false-positive refuse is the user typing `rm -rf`
  on their data dir, which they can do in their own shell with full awareness.
  The cost of a false-negative permit is MAC writing into a directory it did
  not initialize. Asymmetric: refusal is recoverable, permission is not.

- **Add `mac.product=newccumulo` to the marker today** — rejected as
  premature. The fork's current `Constants.VERSION` is already
  distinguishable from upstream's in practice; the marker schema is forward-
  compatible (bump `mac.marker.version` to 2) if that ever changes.

- **Heuristic detection of "looks like a real Accumulo cluster snapshot"** —
  rejected. The uniform "no marker = refuse" already refuses the right thing
  for the right reason; sniffing for HDFS-based `instance.volumes` etc. adds
  heuristic logic for marginal UX gain.

## Consequences

- Users running the quickstart against the same `--data-dir` twice see their
  data preserved across runs.
- Any Accumulo version bump (including patch and SNAPSHOT/release transitions)
  forces users to delete the data dir before resuming. Acceptable cost for a
  local-dev quickstart; revisit if it becomes painful.
- The newccumulo fork takes on a release-tooling constraint: `Constants.VERSION`
  must remain distinguishable from upstream Apache Accumulo's value. If this
  is ever violated, the marker schema must be bumped to v2 with a `mac.product`
  field before any newccumulo binary writes a marker.
- Quickstart's CLI layer owns the policy decision of when to enter resume
  mode; MAC stays a narrow primitive. The Quickstart decision tree (decision 2)
  is the testable surface for the persistence UX.
- Existing MAC test callers are unaffected: the empty-dir guard is only lifted
  when `.resume()` has been explicitly called. The current contract — "fresh
  config + non-empty dir = throw" — is preserved by default.
- Attach mode (`useExistingInstance(...)`) is untouched. It remains available
  for any caller that needs it and can be deleted independently of resume in
  the future.
