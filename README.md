# VCF Content Factory vCommunity

A management pack for VCF Operations that adds extra vSphere configuration, licensing, capacity and health data
to your clusters, hosts, VMs, datastores and vCenters, with 52 dashboards, 170 views and 16 reports to go with it.

It is a Java rewrite of the community [vCommunity pack](#credits). Metric and property names are unchanged, so
history carries over from the Python pack.

## Status

**3.0.0.29 is a preview for evaluation.** Expect rough edges, and please report what you find as
[issues](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues). Most useful right now:
anything that is wrong or missing compared with the pack you run today, and anything in the install that
surprised you.

This is a community project. It is not a Broadcom product and is not supported by Broadcom Support. It is
provided as is under the MIT license.

| | |
|---|---|
| Requires | VCF Operations 8.18 or later |
| Tested on | 8.18.7, 9.0.2, 9.1.1 ([details](docs/verification.md)) |
| Download | [Releases](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/releases) |
| What changed from the Python pack | [CHANGELOG](CHANGELOG.md) |

## Install

1. **Coming from the Python pack?** Uninstall it first. See [Moving from the Python pack](#moving-from-the-python-pack).
2. **Upload the pak** under Administration > Integrations > Repository > Add. The pak is unsigned: tick
   "Ignore the PAK file signature checking" before uploading.
3. **Add an account** under Administration > Integrations > Accounts > Add. Enter the vCenter by a name that is
   on its certificate, not an IP address.
4. **Click Validate Connection and accept the certificate**, then save.
5. Data appears within a few minutes. Most dashboards ship disabled; enable the ones you want.

Menu paths are as seen on 9.1; they differ slightly on older versions.

The full guide, including every account field, is in [docs/installing.md](docs/installing.md).

## Known issues

The full list is in the [issue tracker](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues).
The ones most likely to affect an evaluation:

**Things you need to switch on**
- Alerts and super metrics are not enabled by the install. Enable them in your policy.

**Limited or not working**
- Network Speed Degradation only covers NICs with a fixed speed. Auto-negotiated NICs, which is most 10 GbE
  and faster, are skipped ([#39](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/39)).
- Cluster Memory Tiering Consumption has no value ([#7](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/7)).
- VM Snapshots List is always empty ([#9](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/9)).
- VOA reports show "No data" in their dashboard sections on 9.1.1 ([#10](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/10)).
- If you save an account without accepting its certificate, it shows as collecting but collects nothing
  ([#34](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/34)).

**Not yet verified**
- Windows guest monitoring (off by default, lab use only). In one test it logged on to two member servers and
  was refused by two domain controllers, which paused it for every VM. The values it publishes have not been checked
  ([#28](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/28),
  [#30](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/30)).
- The nenic, nfnic and VIB Info views have not been seen with data
  ([#12](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/12)).

## Moving from the Python pack

Running both packs side by side is not supported. Uninstall the Python pack, then install this one.

1. Save any configuration files you edited (the files that list which settings and packages to collect).
2. Uninstall the Python pack.
3. If built-in dashboards now show "The view does not exist", restore the built-in views (below).
4. Install this pak.
5. Re-create your configuration files as copies, then add the account using their names.

What to expect:
- **Metric history carries over.** The keys have the same names on the same objects.
- **Content is replaced.** Dashboards, views, reports and alerts are this pak's own copies. Nine views have new
  titles. Anything you cloned or customized is yours to re-point.
- **Old placeholder values remain for a while.** Values such as `None` or `N/A` written by the Python pack stay on
  existing objects until a real value replaces them.
- **Built-in views may be removed.** vCommunity versions 0.1.5 to 0.8.1 install some views under built-in view
  ids, so uninstalling them also removes those built-in views. To restore them you need an export of the built-in views from a clean
  instance of the same product version; ask the maintainer for one. A script for this will ship with the public
  release ([#32](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/32)).

Details and the restore steps are in [the guide](docs/installing.md#moving-from-the-python-pack).

## Documentation

- [Install guide](docs/installing.md): account fields, certificates, configuration files, guest monitoring, uninstalling, troubleshooting
- [Overview](docs/overview.md): what the pak collects and how
- [What has been verified live](docs/verification.md)
- [Development](docs/development.md): building from source, the release process, repository layout

## Credits

This pak is derived from the vCommunity Management Pack for VCF Operations.

- **Onur Yuzseven** created vCommunity and its adapter
  ([VCF-Operations-vCommunity](https://github.com/vmbro/VCF-Operations-vCommunity)).
- **Iwan "e1" Rahabok** designed its dashboards, reports and super metrics, and continued the pack
  ([VCF-vCommunity-Adapter](https://github.com/e1-rahabok/VCF-vCommunity-Adapter)).
- **Brock Peterson** contributed to the pack and wrote the blog series that explains it.
- **Mark Arbogast** and **Robert Mesropyan** are credited by the vCommunity project as assisting Onur Yuzseven.
- **Dave Kaber**, **Alan Renouf** and the contributor recorded as atm8119 appear in the vCommunity commit history.

## License

MIT, Copyright (c) 2026 Scott Bowe. See [LICENSE](LICENSE).

The content converted from vCommunity 0.8.1 (published under the PolyForm Noncommercial License) is released
here under MIT with the agreement of the vCommunity authors. See [NOTICE](NOTICE).
