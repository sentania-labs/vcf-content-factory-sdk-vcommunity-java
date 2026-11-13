# Changelog

One entry per build. Dev builds are stamped `0.0.0.<build>`; the release is the
`v*` tag built by this repository's CI.

## 1.0.0.1 (Unreleased)

- feat(adapter): phase 1, skeleton and configuration parity. The adapter kind
  is `vcfcf_vcommunity`. describe.xml declares the 19 account fields and the
  `vsphere_user` credential of the original pack (keys, labels, defaults,
  choices, main form versus Advanced Settings), the `vCommunityCollector`
  object, the three own kinds with their 26 keys, and the `Collection|` self
  metrics on the adapter instance.
- feat(adapter): `VCommunityConfig` with the original pack's fallbacks and one
  warning per field; failures name the field and quote no secret.
- feat(adapter): the three vCenter TLS modes (trust on first use, platform
  trust with the certificate name checked, pinned SHA-256 or SHA-512), the
  certificate thumbprint probe, and a raw SOAP vim25 session (no JAX-WS)
  with keepalive, one relogin on a lapsed session, and one login attempt per
  cycle after a failure.
- feat(adapter): the collection cycle skeleton: session, probe, check-list
  files, the vCenter's own object matched by instance UUID, and the
  `vCommunity|Collection Health|` keys and `Collection|` self metrics. A failed
  read sends nothing and is counted; no value is ever sent that was not read.
- feat(adapter): Test Connection reports the login, the certificate
  thumbprint, whether the Suite API answers, and each check-list file's outcome
  and item count (in the collector log at WARN; the platform shows no text for
  a passing test).
- feat(adapter): check-list files: secure parse, per-list validation (VM option
  paths, Windows service names, the event list structure), last good list kept
  on a read or parse failure, an absent file empties its list.
- test: plain-main tests for every class outside the two SDK-facing ones,
  including the TLS modes and the session against a local TLS server with a
  certificate made at run time, and describe.xml pinned to the original pack.
- docs: README, overview, installing guide, reference and NOTICE (placeholder
  wording; the final text waits on the licensing record).
