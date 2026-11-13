#!/usr/bin/env bash
# Compiles and runs the adapter's plain-main Java tests (no test framework,
# no external deps beyond a JDK; BcHandshakeTest also fetches the BouncyCastle
# TLS jars). Exits non-zero on any compile error,
# failed assertion, or when no tests are found. Run from anywhere:
#   ci/run_java_tests.sh
# Every tests/**/*Test.java is a test: its class is derived from its path
# and its main() is run, so a new test needs no edit here. Helpers (T.java)
# are compiled but not run; BcHandshakeTest also runs, in its own JVM with the
# BouncyCastle jars (see below). The tested classes must have no VCF Ops SDK or
# framework-jar dependency; .github/workflows/pak-tests.yml runs this on
# every push and pull request.
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
mapfile -t sources < <(find tests -name '*.java' | sort)
mapfile -t tests < <(find tests -name '*Test.java' | sort)
if [ "${#tests[@]}" -eq 0 ]; then
    echo "no tests/**/*Test.java found" >&2
    exit 1
fi
javac -d "$out" -sourcepath src:tests "${sources[@]}"
for f in "${tests[@]}"; do
    cls="${f#tests/}"
    cls="${cls%.java}"
    # BcHandshakeTest needs the BouncyCastle jars; it runs below.
    [ "$(basename "$f" .java)" = "BcHandshakeTest" ] && continue
    java -cp "$out" "${cls//\//.}"
done

# Real handshakes under the BouncyCastle JSSE provider, the TLS provider family
# of VCF Operations 9.1.1 (FIPS) collectors. The jars come from Maven Central
# (public, no credentials) with pinned SHA-256s (the same ones the compliance
# adapter pins); they are test only and never ship in the pak. In CI a failed
# fetch fails the run; on a workstation without network the test is skipped and
# says so, last. These are the non-FIPS jars: the FIPS ones are not on Maven
# Central. They throw the same alert type (an IOException that is not an
# SSLException) that lost the pin message.
bc_version=1.82
bc_dir="$out/bc"
mkdir -p "$bc_dir"
bc_ok=1
bc_skipped=0
while read -r art sum; do
    jar="$art-jdk18on-$bc_version.jar"
    url="https://repo1.maven.org/maven2/org/bouncycastle/$art-jdk18on/$bc_version/$jar"
    if ! curl -fsSL --retry 3 -o "$bc_dir/$jar" "$url"; then
        bc_ok=0
        break
    fi
    echo "$sum  $bc_dir/$jar" | sha256sum -c --quiet - || {
        echo "BouncyCastle $jar checksum mismatch" >&2
        exit 1
    }
done <<'SUMS'
bcprov 14cde2fdfaa8890480a8e5b67aceef0c90f96682c1e23c133bafdc9e0b3255ce
bctls 0f4d9d69ab9e1d0d11f3ae399edf77e0fd8af50aa93f565af5beeca222abad30
bcutil 4420691958ad1c0ba275a6d6d8a6317adbdbdc9277055b6a72aa89c88cda8c7d
SUMS
if [ "$bc_ok" = 1 ]; then
    java -cp "$out:$bc_dir/*" com.vcfcf.adapters.vcommunity.BcHandshakeTest
elif [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    echo "BouncyCastle jars could not be fetched from Maven Central" >&2
    exit 1
else
    bc_skipped=1
fi
# Printed last and on stdout so a passing exit code on a workstation is not
# mistaken for a run that exercised the BouncyCastle handshakes.
if [ "$bc_skipped" = 1 ]; then
    echo
    echo "################################################################"
    echo "## SKIPPED: BcHandshakeTest was NOT run. The BouncyCastle jars"
    echo "## could not be fetched from Maven Central (offline?). The 9.1.1"
    echo "## collector TLS path is untested in this run; CI runs it."
    echo "################################################################"
fi
