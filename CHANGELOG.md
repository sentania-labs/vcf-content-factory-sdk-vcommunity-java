# Changelog

One entry per build. Dev builds are stamped `0.0.0.<build>`; the release is the
`v*` tag built by this repository's CI.

## 1.0.0.3 (Unreleased)

- fix(adapter): the session is visible in the account log at INFO, one line each:
  login (vCenter product and version, new session), reuse each cycle, renewal
  when the held session expired, logout with its reason (`reconfigure` or
  `stop`); and one cycle summary line (duration, vCenter object matched, objects
  not matched, series and properties pushed, reads failed, values withheld,
  warnings). Before, only the expiry line existed, so a live run could not show a
  session being opened, reused or ended.
- fix(adapter): a pin mismatch keeps its message under FIPS. The provider's alert
  is an IOException that is not an SSLException, so the cause walk was skipped;
  the walk now covers every IOException, causes and suppressed, and the pinned
  trust manager records its decision per thread as a last resort. Other TLS
  failures name the alert and the mode; an untrusted chain in Enabled says to
  accept the certificate in Validate Connection or use Pinned.
- fix(adapter): Test Connection logs success at INFO only (never WARN); each thing
  needing attention is its own WARN line. A Suite API client that cannot be built
  during a test is a plain "not checked during a test" line, not a warning.
- fix(adapter): a failed check-list read shows the answer's own words (bounded),
  so an HTTP 400 with no apiErrorCode (seen on 8.18.7) can be told from a missing
  file next time; it is still a failed read, not an absent file.
- docs: log lines, Test Connection logging, creating an account through the API.

## 1.0.0.2 (Unreleased)

- fix(adapter): the session cookie is found whatever the case of the
  `Set-Cookie` header (a proxy in front of the vCenter may send it lower case);
  before, every cycle failed and left an authenticated session behind.
- fix(adapter): a count that was not measured is not sent: check-list entries
  skipped (unless every file read was parsed), objects not matched (unless the
  vCenter object lookup completed), on the adapter instance and on the vCenter
  object.
- fix(adapter): the period choices carry their labels (`01 hour` ... `24 hours`),
  every account field has a description, and the Trust on First Use text says
  plainly that credentials are sent before any certificate check; enums carry no
  `displayOrder` (not in the schema).
- fix(adapter): SOAP answers built into a tree are capped at 1 MiB and 32
  levels; redirects are not followed; a page of resources with no total is not
  taken for the whole list; a Unicode host is converted to punycode for the name
  check.
- fix(adapter): a skipped check-list entry is a reported problem, not a failed
  read; a file name is refused only when it is a path or a query; no runtime
  exception in the vCenter object lookup can end the cycle; a clean Test
  Connection logs at INFO.
- test: lower case headers, the real certificate name check in Enabled mode,
  oversized and deep answers, every unmeasured-count path.
- docs: the design copy is corrected (own-kind units, self-metric transport) and
  deviations DV-61 to DV-66 are registered; `docs/reviews/p1-skeleton-review.md`.

## 1.0.0.1

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
  and item count (in the adapter log).
- feat(adapter): check-list files: secure parse, per-list validation (VM option
  paths, Windows service names, the event list structure), last good list kept
  on a read or parse failure, an absent file empties its list.
- test: plain-main tests for every class outside the two SDK-facing ones,
  including the TLS modes and the session against a local TLS server with a
  certificate made at run time, and describe.xml pinned to the original pack.
- docs: README, overview, installing guide, reference and NOTICE (placeholder
  wording; the final text waits on the licensing record).
