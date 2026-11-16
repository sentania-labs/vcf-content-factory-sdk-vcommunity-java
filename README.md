# VCF Content Factory vCommunity

A Java port of the vCommunity management pack for VCF Operations. The pak reads
a vCenter over vim25 and pushes the `vCommunity|` keys onto the vCenter
adapter's own objects through the VCF Operations Suite API, and adds three
object kinds of its own (content libraries, library items and VM templates).
It is a clean reimplementation: the key tree, the account fields and the
check-list files follow the original pack, the code is new. The content
(views, dashboards and the rest) is converted from the original pack's content files; the whole pak, code and content, is MIT licensed (`NOTICE` has the provenance).

Adapter kind `vcfcf_vcommunity`; distinct from the original Python pack's kind,
so the two are never an upgrade of each other. Minimum product version: VCF Operations 8.18.x (tested on 8.18.7, 9.0.2 and 9.1.1).

## Status

**3.0.0.1, a preview release.** This is the first public build of the 3.x line, released to collect feedback; expect
rough edges. It needs **VCF Operations 8.18 or later** and was tested on **8.18.7, 9.0.2 and 9.1.1**. The release notes
are in `CHANGELOG.md` ("3.0.0.1 (preview)"), including where it deliberately differs from the Python pack it descends from.

**Send feedback as GitHub issues on this repository.**

What is in it: the account (the original pack's fields and credential, with the vCenter TLS setting replaced by Allow Insecure SSL: the vCenter certificate is verified by default), the vim25
session, Test Connection and the collection cycle with its Collection Health keys and self metrics; the property keys of
clusters, hosts, virtual machines, datastores and the vCenter; three object kinds of its own (content libraries, library
items and VM templates); the derived metrics (the cluster, host and VM scores, availability, network speed, shares, port
groups, resource pools, the periodic cluster check, the vSphere World roll-ups and the Business Application figures);
the guest checks and Windows events (off by default); and the content: 170 views, 52 dashboards, 16 reports,
3 super metrics, 10 symptoms, 4 alerts with 1 recommendation, and the six check-list files (`docs/installing.md`,
"Content"). `docs/p2-key-coverage.tsv`, `docs/p3-key-coverage.tsv`, `docs/p4-key-coverage.tsv` and
`docs/p5-key-coverage.tsv` list every key of the key map with whether this build collects it and the test that proves it.
The design is in `docs/design/` (start with `vcommunity-java-port-design.md`).

**Live verification.** Network Speed Degradation was verified live on 9.0.2 (build 22). The memory tiering divisor and
the tiering latency unit were confirmed live on 9.1.1. The migration from a Python pack version (uninstall, restore the
built-in views from a clean export) was run on 9.1.1. Build 0.0.0.26 (the code of this release) was installed over earlier builds on 8.18.7, 9.0.2 and 9.1.1 on 2026-11-16 and
collected with every collector OK and no warnings, failed reads or withheld values. Seen live: host availability on 8.18.7
back at 100 (was 75); on 9.1.1 the memory rows and the Cluster Consumption score equal to what 9.0.2 publishes for the same
cluster (consumed 60.40 against 60.38), Cluster Risk within about one point, and tiering latency in microseconds; VMware
Tools properties as real values; no placeholder value pushed after the upgrade; the license symptom form matching a real
license instance (proved with a temporary symptom); and "vSphere Clusters not Green" counting 1 of the scored clusters.
Not seen: the five package views with data (no lab host runs those packages), and the widgets of "vSphere Cluster
Performance 2.0" filling after a cluster is picked (the scripted selection did not register; not checked by hand).

### Known gaps

- **Guest monitoring** (the Windows service and event collection, and the guest operating system keys) has been built and
  tested against a fake vCenter and ESXi host only; it has not been run against a real Windows guest. It is off by default.
- **Cluster Memory Tiering Consumption** publishes nothing: its input statistic does not exist in VCF Operations. The real
  source is a vCenter performance counter this adapter does not read yet.
- **Network Speed Degradation** needs the host NIC property `net|configuredSpeed` enabled in the active policy, and works
  only for NICs with a fixed speed in vCenter (a NIC on auto-negotiate gets no configured speed). See
  `docs/installing.md`, "Network Speed Degradation".
- **Alerts ship switched off.** The four alert definitions (ESXi Host NIC Disconnected, ESXi Host License Expiring,
  Windows Service Down, vCommunity Collection Warnings) are not enabled in any policy by the install, as in the original
  pack; enable the ones you want in your policy.
- **Super metrics** ("CPU Usable Capacity Utilization", "Number of VM", "vSphere Clusters not Green") compute only when
  the operator enables them in the policy.
- **CPU Over-Commitment** (a DRS cluster setting) reads a DRS option name that has not been confirmed against a cluster
  with the setting ticked; it may show nothing where the setting is in use (UB-6 in `docs/reviews/upstream-bug-audit-2026-11-16.md`).
- **Restoring the built-in views** after uninstalling an affected Python pack version needs a clean export of the
  built-in views that you provide (`docs/installing.md`, "Moving from the Python pack").

The content is converted from the original pack's content files (see `NOTICE`); the
conversion report and the list of text carried as written are in `docs/design/inputs/`.

## Moving from the Python pack

**Uninstall the Python pack first (Onur's 1.x "VCF Operations vCommunity" or Ewan's 2.x
"vCommunity" adapter), then install this one.** Running both side by side is unsupported: both
write the same `vCommunity|...` keys onto the same vSphere objects, and both ship content with
the same names. This pak's content is under ids of its own (namespace `vcfcf_vcommunity`), so
it does not overwrite the Python pack's content, and uninstalling one does not delete the other's.

- Carries over: the metric and property keys are the same names on the same objects, so their
  history continues (measured on 8.18.7: one continuous series per key across the move).
- Replaced: dashboards, views, reports, super metrics, symptoms, alerts and the recommendation
  are this pak's copies under new ids and the same names. Anything you cloned or customized from
  the old content is yours to keep or re-point; the alert history of the old definitions stays with them.
- Check-list files: the six default-named files are replaced on every pak upgrade, and
  uninstalling the Python pack removes its copies of the same names (inferred from the platform's
  code, not measured). Save your edited check lists first and re-apply them in copies under other names.
- Built-in views: some Python pack versions ship views under built-in view ids, and uninstalling
  them deletes those built-in views (measured). Have a clean export of the built-in views of the same
  product version at hand, from an instance that never had the Python pack, and import it after the
  uninstall through Administration > Control Panel > Content Management > Import (measured on 9.1.1:
  all 120 views restored).

In short: (1) save your edited check lists, (2) uninstall the Python pack, (3) if it was one of the
affected versions, import the built-in views export, (4) install this pak, (5) re-apply your check lists.

**Supported path: uninstall, then install fresh.** This is the only supported way onto this pak
from the Python pack, and from any earlier dev build of this pak that used different content ids
(builds up to 0.0.0.16). An in-place upgrade across an id change is not supported. Normal upgrades
between releases that keep their ids are in-place as usual.

The full guide is in `docs/installing.md`, "Moving from the Python pack".

## Documentation

`docs/README.md` indexes the docset: `overview.md`, `installing.md` (the account
fields, TLS, Test Connection) and the generated inventory tree.
`REFERENCE.md` lists the account, the self metrics and the own kinds' keys.
`NOTICE` credits the original authors (see Credits below).

## What's here

```
adapter.yaml                         # pak metadata (kind, entry class, naming, stitch targets)
describe.xml                         # account fields, credential, own kinds, self metrics
resources/resources.properties       # nameKey -> display strings
icons/icons.yaml                     # kind -> icon map
src/com/vcfcf/adapters/vcommunity/   # adapter source
  VCommunityAdapter.java             #   lifecycle, Test Connection, discovery (SDK side)
  OpsPort.java                       #   Suite API port (SDK side)
  VCommunityConfig.java              #   the 19 fields, defaults, fallbacks (SDK-free)
  CycleEngine.java                   #   one cycle: session, probe, lists, binding, properties, health
  ConnectionTester.java              #   Test Connection logic
  vim/                               #   raw SOAP session, paged streamed reads, no JAX-WS and no vim25.jar
  coll/                              #   what each object kind's keys read; the property phase
  text/                              #   Python text rules, license key mask
  tls/                               #   platform trust with the name check, Allow Insecure SSL; probe; the ESXi pin check
  ops/                               #   resource identity, push set, push on change
  list/                              #   check-list files: parse, validate, store
  health/                            #   failure accounting, warning count, health keys
  json/                              #   small JSON reader and writer
tests/com/vcfcf/adapters/vcommunity/ # plain-main tests, no SDK jar needed
views/ dashboards/ reports/ supermetrics/ symptoms/ alerts/ recommendations/  # the content, as factory YAML
content/files/solutionconfig/        # the six check-list files, packed as they are
docs/design/                         # the approved design, the collection map, key tables
.github/workflows/                   # tests on push; release build on a v* tag
ci/run_java_tests.sh                 # runs every tests/**/*Test.java
```

Everything outside `VCommunityAdapter` and `OpsPort` has no import from the VCF
Operations SDK or the framework jar, so the tests run on a bare JDK:

```sh
ci/run_java_tests.sh
```

The tests make a throwaway TLS certificate with the JDK's `keytool` at run time;
no key or certificate is stored in the repository.

There is **no `lib/` of bundled jars** (see "C2 pak shape" below).

## Local dev build (preview)

The official `.pak` is built by CI on a tag (next section). For a local preview,
use the factory's builder. The Broadcom SDK jar for VCF Operations 9.0.2 is **not** shipped —
supply it from a 9.0.2 appliance (JDK 17 or newer is needed to read it):

```sh
# Obtain the SDK jar once from a VCF Ops appliance:
#   scp root@<appliance>:/usr/lib/vmware-vcops/common/lib/vrops-adapters-sdk.jar ~/vcf-ops-sdk/
#   (check its sha256 and rename it as described in the build instructions below)

# Either pass it explicitly:
python3 -m vcfcf_managementpacks build-sdk content/sdk-adapters/vcommunity-java \
  --sdk-jar /path/to/vrops-adapters-sdk-9.0.2.0.25137845.jar -o dist

# ...or set it once in the environment:
export VCFCF_SDK_JAR=/path/to/vrops-adapters-sdk-9.0.2.0.25137845.jar
python3 -m vcfcf_managementpacks build-sdk content/sdk-adapters/vcommunity-java -o dist
```

Validate first (the cheap loop — exhaust this before building a pak):

```sh
python3 -m vcfcf_managementpacks validate-sdk content/sdk-adapters/vcommunity-java
```

The framework jar (`vcfcf-adapter-base.jar`) is provided by the builder; you do
**not** commit it.

## Building from source

You don't need this repo's CI or the VCF Content Factory checkout to
build the `.pak` — the toolchain is a portable tarball. You need:

- **JDK 17+** (`javac` + `jar` on PATH; the 9.0.2 SDK jar's class files are Java 17)
- **python3** with `pyyaml` (`python3 -m pip install pyyaml`)
- **The GitHub CLI** (`gh`) — used to download the build toolchain
  below. The factory repo is public, so no `gh auth login` is needed
  for the download (authenticate only if you hit anonymous API rate
  limits). No `gh`? See the `curl` alternative under step 1.
- **The Broadcom adapter SDK jar for VCF Operations 9.0.2**, and
  **JDK 17 or newer** to read it. This is a Broadcom build artifact
  with no public redistribution channel — it is **never** bundled in
  the toolchain or this repo. The jar is different on every platform
  version; the build compiles against the 9.0.2 one. Get it from a VCF
  Operations 9.0.2 appliance, where it is
  `/usr/lib/vmware-vcops/common/lib/vrops-adapters-sdk.jar`. Keep it
  **outside this repo's working tree**, so it can never be committed
  by accident:

  ```
  mkdir -p ~/vcf-ops-sdk
  scp root@<appliance>:/usr/lib/vmware-vcops/common/lib/vrops-adapters-sdk.jar ~/vcf-ops-sdk/
  sha256sum ~/vcf-ops-sdk/vrops-adapters-sdk.jar
  ```

  Look the hash up in the table under "Consumer contract" in the
  buildkit release notes. It lists the builds the kit recognises and the
  file name it uses for each, `vrops-adapters-sdk-<version>.<build>.jar`
  (for example `vrops-adapters-sdk-9.0.2.0.25137845.jar`); rename your
  copy to match. The kit identifies the jar by its hash, so a jar from a
  patched appliance is a different build with a different name. If the
  hash is not in the table, the appliance is at a build the kit does not
  know yet: the jar still compiles, the build reports it as an unknown
  jar, and you can name it `vrops-adapters-sdk-<version>.<build>.jar`
  from the appliance's own version and build. The same
  section covers the optional 8.18.7 and 9.1.1 jars for the
  compatibility checks. Partners can pull the jar from the Broadcom TAP
  / partner SDK portal instead.

Then, from the root of this repo:

```bash
# Run as one block. The subshell stops at the first failure, so a checksum
# mismatch never reaches the unpack or the build, and your own shell stays open.
(
set -e
# 1. Fetch the build toolchain. Pin a full sdk-buildkit-vX.Y.Z tag (the
#    floating sdk-buildkit-v1 tag moves under you) and verify the published
#    checksum before unpacking. Use the version this repo's release
#    workflow pins (BUILDKIT_TAG in .github/workflows/build-pak-on-tag.yml);
#    1.0.29 at the time of writing.
BUILDKIT_VERSION=1.0.29
gh release download "sdk-buildkit-v$BUILDKIT_VERSION" \
  --repo sentania-labs/vcf-content-factory \
  --pattern "sdk-buildkit-$BUILDKIT_VERSION.tgz*"
# No gh? The assets are public; fetch both with curl instead:
#   base=https://github.com/sentania-labs/vcf-content-factory/releases/download/sdk-buildkit-v$BUILDKIT_VERSION
#   curl -fsSLO "$base/sdk-buildkit-$BUILDKIT_VERSION.tgz"
#   curl -fsSLO "$base/sdk-buildkit-$BUILDKIT_VERSION.tgz.sha256"
# Verify before unpacking. The strong check is against the hash this repo
# commits for its release builds (BUILDKIT_SHA256 in the same workflow),
# which a replaced release asset cannot match:
echo "$(awk '/^  BUILDKIT_SHA256:/{print $2; exit}' .github/workflows/build-pak-on-tag.yml)  sdk-buildkit-$BUILDKIT_VERSION.tgz" | sha256sum -c -   # macOS: shasum -a 256 -c -
# For a version other than the pinned one, the release's own sidecar only
# proves the download is intact:
#   sha256sum -c "sdk-buildkit-$BUILDKIT_VERSION.tgz.sha256"
tar xzf "sdk-buildkit-$BUILDKIT_VERSION.tgz"

# 2. Point the kit at your SDK jar and build
export VCFCF_SDK_JAR=~/vcf-ops-sdk/vrops-adapters-sdk-9.0.2.0.25137845.jar
python3 -m sdk_buildkit validate-sdk .   # cheap loop: compile-check
python3 -m sdk_buildkit build-sdk .      # emits the .pak
)
```

The kit carries everything else it needs (including the
`vcfcf-adapter-base.jar` framework runtime that ends up in the pak's
`lib/`). `validate-sdk` is the fast iteration loop; exhaust it before
building paks.

**Dev builds vs releases.** Anything you build this way is a *dev
build*. The **official** artifact for this repo is the one its own CI
builds and attaches to a GitHub Release when a `v*` tag is pushed —
deterministic, no developer machine in the path.

**If you fork this repo**, the CI workflow
(`.github/workflows/build-pak-on-tag.yml`) needs one adjustment
before your own `v*` tags will build (it already runs on GitHub-hosted
`ubuntu-latest`, so no runner change is needed).

**SDK jar sourcing**: the upstream workflow fetches the Broadcom
   jar from a private repo via an `SDK_RUNTIME_SSH_KEY` deploy-key
   secret you won't have. Replace that step with your own source:
   store the appliance-extracted jar in **a private repo only**, and
   fetch it from there. Never upload the jar as a workflow artifact:
   on a public repo anyone can download workflow artifacts, which
   would amount to redistributing it. Then **also update the
   `--sdk-jar` argument** on the `build-sdk` line of the workflow to
   point at wherever your replacement step puts the jar. The explicit
   `--sdk-jar` flag overrides `VCFCF_SDK_JAR`, so setting the env var
   alone is not enough — if you leave `--sdk-jar _sdk_runtime/...` in
   place the build will look for the upstream path and fail. Do **not**
   commit the jar to a public repo (no redistribution).

## CI release contract (the official artifact)

This repo is the **single source of truth** for its `.pak`. The shippable artifact
is built by CI, never on a laptop:

1. Author + commit + push to `main`.
2. **Push a `v<version>.<build_number>` tag** (it must equal `version` and `build_number` in `adapter.yaml`; for the first
   release, `v3.0.0.1`). The `build-pak-on-tag` workflow:
   - pulls the `sdk-buildkit` tarball from the factory's Releases at the pinned
     `BUILDKIT_TAG` and verifies it against `BUILDKIT_SHA256` before extracting,
   - gates on the factory defect registry, fetched at `DEFECT_REGISTRY_COMMIT`,
   - fetches the private Broadcom SDK jar from
     `sentania-labs/vcf-content-factory-sdk-runtime` (the jar is committed at
     that repo's root; no release asset is used), authenticated by the
     `SDK_RUNTIME_SSH_KEY` deploy key, at the pinned `SDK_RUNTIME_COMMIT`
     (never that repo's HEAD), and checks the jar against `SDK_JAR_SHA256`
     with `sha256sum -c` before anything compiles against it,
   - builds the `.pak` deterministically (no agent, no factory checkout),
   - gates on `pak-compare` (zero BLOCKING required), and
   - attaches the `.pak` and a `SHA256SUMS` file to the tag's GitHub Release
     with a build-provenance attestation. A published release is never
     overwritten: re-running a tag whose Release already has assets
     fails.

That Release asset **is** the release. A factory `/publish` that references this
pak emits only a **pointer** to the latest Release — it never rebuilds or mirrors
the binary.

### Required release environment secret

`SDK_RUNTIME_SSH_KEY` — a read-only ed25519 **deploy key** for
`sentania-labs/vcf-content-factory-sdk-runtime`. The Broadcom SDK jar is
committed at the root of that private repo and fetched by the workflow via a
shallow git clone; it is never bundled in this repo or the buildkit (C2
redistribution constraint). Setup: generate an ed25519 keypair, add the
PUBLIC half as a read-only deploy key on the sdk-runtime repo, and store the
PRIVATE half as an Environment secret named `SDK_RUNTIME_SSH_KEY` in
`sdk-release` **before** pushing a release tag. See the workflow header for the
runner/JDK/buildkit knobs. Configure required reviewers on `sdk-release` and
protect `v*` tags with repository rulesets before releasing. Those GitHub
settings are managed separately from this repository.

## C2 pak shape — no bundled jars

This pak **never** carries `vrops-adapters-sdk` or any Broadcom jar:

- `vcfcf-adapter-base.jar` (the VCF-CF framework) is provided by the buildkit and
  copied into the pak's `lib/` at build time.
- `vrops-adapters-sdk-*.jar` is on the appliance's shared classpath at runtime and
  is supplied to the *compiler* by the consumer (`--sdk-jar` / `VCFCF_SDK_JAR`);
  it is never placed in the pak.

`.gitignore` ignores `lib/*.jar` so a stray jar can't be committed.

## Bundled content

`adapter.yaml` lists the content in `bundled_content:`; the YAML lives in this repo under `views/`,
`dashboards/`, `reports/`, `supermetrics/`, `symptoms/`, `alerts/` and `recommendations/` (paths resolve
relative to `adapter.yaml`). The six check-list files are under `content/files/solutionconfig/` and are
packed as they are. `content_foreign_adapter_kinds: [VMWARE]` lets the symptoms and alerts, which sit on
vCenter adapter objects, build. The two Section dashboards carry `section_fallback: omit`: the build
prints `SECTIONS OMITTED` for them while the floor is below VCF Operations 9.1.1.

## Credits

Portions of this pak are derived from the vCommunity Management Pack for VCF Operations
by its authors, included here under the MIT License with their permission. `NOTICE` has
the full acknowledgments and the upstream Required Notice line.

- Onur Yuzseven: the original vCommunity Management Pack and its adapter
  ([VCF-Operations-vCommunity](https://github.com/vmbro/VCF-Operations-vCommunity)).
- Iwan "e1" Rahabok: the dashboards, reports, super metrics and the pack's design, and the
  continuation of the pack ([VCF-vCommunity-Adapter](https://github.com/e1-rahabok/VCF-vCommunity-Adapter)).
- Brock Peterson: contributions to the pack, and the blog series that explains it and its use cases.
- Mark Arbogast and Robert Mesropyan: credited by the upstream project as assisting Onur Yuzseven.
- Other contributors in the upstream history: Dave Kaber, Alan Renouf and the contributor recorded as atm8119.

## License

MIT, Copyright (c) 2026 Scott Bowe, for the whole pak, the adapter code and all bundled content, see `LICENSE`. The bundled content was converted from the content files of the upstream vCommunity pack 0.8.1, which is published under its own license (PolyForm Noncommercial 1.0.0); it is released here under MIT by decision of this project's owner, who holds the record of the upstream authors' agreement (`NOTICE`, which carries the "portions derived from" statement and the acknowledgments; the pak shows it as its license text, `EULA.txt`).
