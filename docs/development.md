# Development: building, release and repository layout

For operators, see the [README](../README.md) and the [install guide](installing.md). This page is for people building or changing the pak.

## What's here

```
adapter.yaml                         # pak metadata (kind, entry class, naming, stitch targets)
describe.xml                         # account fields, credential, own kinds, self metrics
resources/resources.properties       # nameKey -> display strings
icons/icons.yaml                     # kind -> icon map
src/com/vcfcf/adapters/vcommunity/   # adapter source
  VCommunityAdapter.java             #   lifecycle, Test Connection, discovery (SDK side)
  OpsPort.java                       #   Suite API port (SDK side)
  VCommunityConfig.java              #   the 18 fields, defaults, fallbacks (SDK-free)
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
views/ dashboards/ reports/ supermetrics/ symptoms/ alerts/ recommendations/  # the content, as YAML
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
use the VCF Content Factory builder. The Broadcom SDK jar for VCF Operations 9.0.2 is **not** shipped —
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
   release, `v3.0.0.29`). The `build-pak-on-tag` workflow:
   - pulls the `sdk-buildkit` tarball from the VCF Content Factory repository's Releases at the pinned
     `BUILDKIT_TAG` and verifies it against `BUILDKIT_SHA256` before extracting,
   - gates on the VCF Content Factory defect registry, fetched at `DEFECT_REGISTRY_COMMIT`,
   - fetches the private Broadcom SDK jar from
     `sentania-labs/vcf-content-factory-sdk-runtime` (the jar is committed at
     that repo's root; no release asset is used), authenticated by the
     `SDK_RUNTIME_SSH_KEY` deploy key, at the pinned `SDK_RUNTIME_COMMIT`
     (never that repo's HEAD), and checks the jar against `SDK_JAR_SHA256`
     with `sha256sum -c` before anything compiles against it,
   - builds the `.pak` deterministically (no factory checkout),
   - gates on `pak-compare` (zero BLOCKING required), and
   - attaches the `.pak` and a `SHA256SUMS` file to the tag's GitHub Release
     with a build-provenance attestation. A published release is never
     overwritten: re-running a tag whose Release already has assets
     fails.

That Release asset **is** the release. The VCF Content Factory's publish step emits only a **pointer** to the latest Release; it never rebuilds or mirrors
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

