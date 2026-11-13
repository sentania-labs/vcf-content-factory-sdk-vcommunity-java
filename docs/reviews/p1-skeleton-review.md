# P1 skeleton review: how each finding was resolved

Review of branch `p1/skeleton` (CHANGES REQUESTED: 2 blocking, 6 warnings, 9
nits). All are addressed in build 2; live-only items are listed at the end.

| Id | Finding | Resolution |
|----|---------|------------|
| B1 | `Set-Cookie` looked up case-sensitively | `VimSoapClient.sessionCookie` scans header names ignoring case. `FakeVc` can send lower-case headers; `TlsAndSessionTest` logs in, keeps the session, sends the cookie on the keepalive and logs out. |
| B2 | Unmeasured counts sent as 0 | `checklistSkipped` and `objectsUnmatched` are nullable. Skipped is set only when every file read was parsed or absent; unmatched only when the vCenter lookup completed (resolved or not found). Absent from the self metrics and the vCenter keys otherwise. Tests: failed login, no Suite API, deadline, failed or crashing lookup, all fetches failing, last good list in force, no UUID. |
| W1 | Period choices lack labels | `01 hour` ... `24 hours` via a `nameKey` per choice (labels checked in the built 0.8.1 pak); `displayOrder` removed from every enum (also N1). `DescribeTest` pins both. |
| W2 | No field descriptions | One `<nameKey>.description` per field, own words; the TLS one warns in plain words that credentials are sent before any certificate check under Trust on First Use. Pinned by `DescribeTest`. |
| W3 | Stated as fact that Test Connection cannot show success text | Reworded as unverified in `overview.md` and `installing.md`; listed below as a live experiment. |
| W4 | Trust on First Use described as watching | `installing.md` now says P1 stores and compares nothing and that change reporting arrives with the vCenter collector phase. |
| W5 | Unbounded tree-built SOAP answers | Cap 1 MiB, depth limit 32 (`XmlNode`), iterative `find`. The large cap is left to the streaming bulk reader of later phases. Tests: oversized answer, deep document. |
| W6 | Wording close to upstream | `PinMismatchException` and `PinnedTrustManager` text rewritten, the Trust on First Use log line, the check-list problem texts and the docs reworded. A six-word shingle comparison of every Java, doc and descriptor file against the whole 0.8.1 tree was run; what remains is field keys and labels (parity), identifiers, file names, XML and regular-expression syntax, and common phrases. |
| N2 | A runtime exception in the vCenter lookup could end the cycle | The lookup and the check-list stage catch `RuntimeException` too and count a failed read. Test with an `IllegalStateException`. |
| N3 | A skipped entry counted as a failed read | Not any more: it is a WARN and part of the skipped count. `check_lists` is PARTIAL; FAILED only when every file failed and none is in force. Tests. |
| N4 | Port range and file-name refusal not registered; name rule too wide | Registered as DV-61 and DV-62 in the design copy here. `isPlainName` refuses only a path or a query (slash, backslash, `?`, control character, `.` or `..` alone); `%`, `&`, `#` and dots inside a name are allowed and encoded. Tests. |
| N5 | Redirects followed | `setInstanceFollowRedirects(false)`; a 302 is a failure and nothing is followed (test). |
| N6 | Page with no `pageInfo` assumed complete | Only a page shorter than the page size is the last; a full page is followed by another read; the page limit fails loudly. Tests. |
| N7 | Unicode host in the name check | `VCommunityConfig.asciiHost()` (IDN) is used for the name check, the URL, the review URL and the probe. Tests. |
| N8 | Wrong javadoc ("per cycle") | Fixed: parsed at configure. |
| N9 | Test Connection log level; stub name check | A clean test logs at INFO, anything needing attention at WARN. `TlsAndSessionTest.enabledNameCheck` runs Enabled mode against a real certificate with a real JSSE name check: a trusted certificate that names the host passes, one that does not is refused with nothing sent. (The framework's own `CertificateNames` is not reachable from the SDK-free tests; the platform adds it on top.) |
| Design | Design text wrong in 2.3 and 2.4 | `docs/design/vcommunity-java-port-design.md` corrected (no unit on own-kind keys; self metrics through the SDK result) and DV-61 to DV-66 registered. The factory's copy is untouched. |

## Only provable live
- Test Connection: whether the dialog can show text for a passing test (try a
  success-path message and record what it shows), and whether the INFO line
  reaches a log under the pak's logging entry.
- That the platform renders the period labels, the descriptions and the
  integer fields in the account dialog.
- The framework's own certificate name check in Enabled mode, and the accept
  dialog.
- That a real vCenter, and one behind a proxy, returns the session cookie as the
  client now expects; E15 (FIPS collectors and custom trust managers).
