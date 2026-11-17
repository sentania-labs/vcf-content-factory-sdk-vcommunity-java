package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.VCommunityConfig.GuestTlsMode;
import com.vcfcf.adapters.vcommunity.list.ListKind;

import java.util.HashMap;
import java.util.Map;

/** The 18 fields: defaults, fallbacks, failures, redaction. */
public final class ConfigTest {

    private static final String PIN64 = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";

    static Map<String, String> fields(String... kv) {
        Map<String, String> m = new HashMap<>();
        m.put("host", "vc.example.test");
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] == null) m.remove(kv[i]);
            else m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    static VCommunityConfig parse(Map<String, String> m) {
        return VCommunityConfig.parse(m::get, "svc-user", "s3cret-pw", null, null);
    }

    public static void main(String[] a) {
        defaults();
        periodAndTime();
        wholeNumbers();
        allowInsecure();
        unsetValues();
        failures();
        guestAndSwitches();
        filesAndIdentity();
        redaction();
        certificateCheckUrl();
        idn();
    }

    static void idn() {
        VCommunityConfig u = parse(fields("host", "b\u00fccher.example", "port", "8443"));
        T.eq("xn--bcher-kva.example", u.asciiHost(), "Unicode host in punycode");
        T.eq("xn--bcher-kva.example:8443", u.dialledHost(), "the name check gets the punycode form");
        T.eq("xn--bcher-kva.example:8443", u.hostPort(), "and so does the URL");
        T.eq("vc.example.test", parse(fields()).asciiHost(), "ASCII is unchanged");
        T.eq("fe80::1", parse(fields("host", "fe80::1")).asciiHost(), "IPv6 literal is unchanged");
        T.eq("https://xn--bcher-kva.example:443", VCommunityConfig.certificateCheckUrl(
                fields("host", "b\u00fccher.example")::get), "review URL");
        T.eq("bad..name\u00fc", parse(fields("host", "bad..name\u00fc")).asciiHost(), "an unusable name is left as entered");
        T.done("config IDN host");
    }

    static void defaults() {
        VCommunityConfig c = parse(fields());
        T.eq("vc.example.test", c.host, "host");
        T.eq(443, c.port, "port default");
        T.check(!c.allowInsecure, "a new account verifies the certificate");
        T.eq("esxi_advanced_system_settings", c.esxiSettingsFile, "esxi settings file");
        T.eq("esxi_packages", c.esxiPackagesFile, "esxi packages file");
        T.eq("vm_advanced_parameters", c.vmParamsFile, "vm params file");
        T.eq("vm_options", c.vmOptionsFile, "vm options file");
        T.eq("windows_service_list", c.winServiceFile, "service file");
        T.eq("windows_event_list", c.winEventFile, "event file");
        T.eq(24, c.periodHours, "period");
        T.eq(0, c.checkHour, "hour");
        T.eq(0, c.checkMinute, "minute");
        T.eq(5, c.worstPercent, "worst percent");
        T.eq(50, c.monsterCpuPercent, "monster cpu");
        T.eq(40, c.monsterMemPercent, "monster mem");
        T.eq(256, c.monsterMemMinGb, "monster mem min");
        T.check(!c.serviceMonitoring && !c.eventMonitoring, "monitoring off by default");
        T.eq(GuestTlsMode.PINNED, c.guestTlsMode, "guest tls default");
        T.check(c.guestTlsError == null, "no guest tls error");
        T.check(c.warnings.isEmpty(), "no warnings for an untouched account: " + c.warnings);
        T.eq("user", "svc-user".equals(c.user) ? "user" : "no", "user");
        T.check(!c.hasWindowsCredential(), "no windows credential");
        T.done("config defaults");
    }

    static void periodAndTime() {
        T.eq(6, parse(fields("cluster_check_period_hours", "6")).periodHours, "'6' accepted");
        T.eq(6, parse(fields("cluster_check_period_hours", "06")).periodHours, "'06' accepted");
        T.eq(1, parse(fields("cluster_check_period_hours", "01")).periodHours, "'01' accepted");
        T.eq(12, parse(fields("cluster_check_period_hours", " 12 ")).periodHours, "padded blanks");
        VCommunityConfig bad = parse(fields("cluster_check_period_hours", "7"));
        T.eq(24, bad.periodHours, "7 falls back");
        T.eq(1, bad.warnings.size(), "one warning for the period");
        T.contains(bad.warnings.get(0), "Cluster Check Period '7'", "period warning text");
        T.eq(24, parse(fields("cluster_check_period_hours", "")).periodHours, "blank period");
        T.check(parse(fields("cluster_check_period_hours", "")).warnings.isEmpty(), "blank is silent");
        T.eq(24, parse(fields("cluster_check_period_hours", "abc")).periodHours, "text period");
        T.eq(24, parse(fields("cluster_check_period_hours", "0")).periodHours, "zero period");

        VCommunityConfig t = parse(fields("periodic_cluster_check_time", "06:30"));
        T.eq(6, t.checkHour, "hour 6");
        T.eq(30, t.checkMinute, "minute 30");
        T.eq(23, parse(fields("periodic_cluster_check_time", "23:59")).checkHour, "23:59");
        T.eq(7, parse(fields("periodic_cluster_check_time", " 7 : 05 ")).checkHour, "spaces around parts");
        for (String badTime : new String[] {"24:00", "12:60", "noon", "12", "1:2:3", "-1:00"}) {
            VCommunityConfig c = parse(fields("periodic_cluster_check_time", badTime));
            T.eq(0, c.checkHour + c.checkMinute, "time '" + badTime + "' falls back");
            T.eq(1, c.warnings.size(), "time '" + badTime + "' warns once");
            T.contains(c.warnings.get(0), "is not HH:MM", "time warning text");
        }
        T.done("config period and time");
    }

    static void wholeNumbers() {
        T.eq(0, parse(fields("cluster_check_worst_percent", "0")).worstPercent, "worst 0 allowed");
        T.eq(50, parse(fields("cluster_check_worst_percent", "50")).worstPercent, "worst 50 allowed");
        VCommunityConfig c = parse(fields("cluster_check_worst_percent", "51"));
        T.eq(5, c.worstPercent, "worst 51 falls back");
        T.contains(c.warnings.get(0), "from 0 to 50", "worst range text");
        T.eq(5, parse(fields("cluster_check_worst_percent", "-1")).worstPercent, "negative");
        T.eq(5, parse(fields("cluster_check_worst_percent", "1.5")).worstPercent, "decimal");

        T.eq(100, parse(fields("monster_vm_cpu_percent_of_cores", "100")).monsterCpuPercent, "cpu 100");
        T.eq(50, parse(fields("monster_vm_cpu_percent_of_cores", "0")).monsterCpuPercent, "cpu 0 falls back");
        T.eq(50, parse(fields("monster_vm_cpu_percent_of_cores", "101")).monsterCpuPercent, "cpu 101");
        T.eq(40, parse(fields("monster_vm_memory_percent_of_dram", "x")).monsterMemPercent, "mem text");
        T.eq(1, parse(fields("monster_vm_memory_min_gb", "1")).monsterMemMinGb, "min 1");
        T.eq(256, parse(fields("monster_vm_memory_min_gb", "0")).monsterMemMinGb, "min 0 falls back");
        T.eq(256, parse(fields("monster_vm_memory_min_gb", "99999999999")).monsterMemMinGb,
                "overflowing number falls back");
        VCommunityConfig w = parse(fields("monster_vm_memory_min_gb", "zero",
                "monster_vm_cpu_percent_of_cores", "0"));
        T.eq(2, w.warnings.size(), "one warning per field");
        T.done("config whole numbers");
    }

    /** Each optional setting: absent, blank, the text null (any case, padded), a valid value, an invalid one. */
    static void unsetValues() {
        String[] unset = {null, "", "  ", "null", "NULL", " Null "};
        // every field with its default, a valid non-default value, and an invalid one
        Object[][] cases = {
            {"cluster_check_period_hours", "12", "7", (java.util.function.Function<VCommunityConfig, Object>) c -> c.periodHours, 24, 12},
            {"periodic_cluster_check_time", "06:30", "noon", (java.util.function.Function<VCommunityConfig, Object>) c -> c.checkHour * 100 + c.checkMinute, 0, 630},
            {"cluster_check_worst_percent", "10", "51", (java.util.function.Function<VCommunityConfig, Object>) c -> c.worstPercent, 5, 10},
            {"monster_vm_cpu_percent_of_cores", "60", "0", (java.util.function.Function<VCommunityConfig, Object>) c -> c.monsterCpuPercent, 50, 60},
            {"monster_vm_memory_percent_of_dram", "30", "x", (java.util.function.Function<VCommunityConfig, Object>) c -> c.monsterMemPercent, 40, 30},
            {"monster_vm_memory_min_gb", "128", "zero", (java.util.function.Function<VCommunityConfig, Object>) c -> c.monsterMemMinGb, 256, 128},
            {"port", "8443", null, (java.util.function.Function<VCommunityConfig, Object>) c -> c.port, 443, 8443},
            {"serviceMonitoring", "Enabled", "enabled", (java.util.function.Function<VCommunityConfig, Object>) c -> c.serviceMonitoring, false, true},
            {"winEventMonitoring", "Enabled", "yes", (java.util.function.Function<VCommunityConfig, Object>) c -> c.eventMonitoring, false, true},
            {"guest_tls_validation", "Strict", "Maybe", (java.util.function.Function<VCommunityConfig, Object>) c -> c.guestTlsMode, GuestTlsMode.PINNED, GuestTlsMode.STRICT},
        };
        for (Object[] k : cases) {
            String key = (String) k[0];
            @SuppressWarnings("unchecked")
            java.util.function.Function<VCommunityConfig, Object> get = (java.util.function.Function<VCommunityConfig, Object>) k[3];
            for (String u : unset) {
                VCommunityConfig c = parse(fields(key, u));
                T.eq(k[4], get.apply(c), key + " '" + u + "' is the default");
                T.check(c.warnings.isEmpty() && c.guestTlsError == null, key + " '" + u + "' is silent: " + c.warnings);
            }
            VCommunityConfig ok = parse(fields(key, (String) k[1]));
            T.eq(k[5], get.apply(ok), key + " valid value");
            T.check(ok.warnings.isEmpty() && ok.guestTlsError == null, key + " valid is silent");
            if (k[2] != null) {
                VCommunityConfig bad = parse(fields(key, (String) k[2]));
                // an unknown guest TLS mode has no fallback: guest operations stay off (DV-29)
                T.eq(key.equals("guest_tls_validation") ? null : k[4], get.apply(bad), key + " invalid value falls back");
                T.check(bad.warnings.size() + (bad.guestTlsError == null ? 0 : 1) == 1, key + " invalid is reported once: " + bad.warnings);
            }
        }
        // a check-list file: the text null is the default; a blank field still reads no file (DV-62)
        for (String u : new String[] {"null", "NULL", " null "}) {
            VCommunityConfig c = parse(fields("esxi_adv_settings_config_file", u, "win_event_config_file", u));
            T.eq("esxi_advanced_system_settings", c.esxiSettingsFile, "file '" + u + "' is the default");
            T.eq("windows_event_list", c.winEventFile, "event file '" + u + "' is the default");
            T.check(c.warnings.isEmpty(), "file null is silent");
        }
        T.eq("", parse(fields("esxi_adv_settings_config_file", "")).esxiSettingsFile, "a blank file field reads no file");
        // Allow Insecure SSL: anything but the text true verifies
        for (String u : unset) T.check(!parse(fields("allowInsecure", u)).allowInsecure, "allowInsecure '" + u + "' verifies");
        T.check(parse(fields("allowInsecure", "TRUE")).allowInsecure, "TRUE is insecure");
        T.check(!parse(fields("allowInsecure", "maybe")).allowInsecure, "any other text verifies");
        // the review URL takes the same view of the port
        T.eq("https://vc.example.test:443", VCommunityConfig.certificateCheckUrl(fields("port", "null")::get), "port null in the URL");
        T.done("config unset values");
    }

    static void allowInsecure() {
        T.check(!parse(fields()).allowInsecure, "absent: verified");
        T.check(!parse(fields("allowInsecure", "")).allowInsecure, "blank: verified");
        T.check(!parse(fields("allowInsecure", "false")).allowInsecure, "false: verified");
        T.check(!parse(fields("allowInsecure", "FALSE")).allowInsecure, "FALSE: verified");
        T.check(parse(fields("allowInsecure", "true")).allowInsecure, "true: insecure");
        T.check(parse(fields("allowInsecure", "TRUE")).allowInsecure, "any case");
        T.check(!parse(fields("allowInsecure", " true")).allowInsecure, "a padded true verifies, as in the other paks");
        T.check(!parse(fields("allowInsecure", "yes")).allowInsecure, "anything but true verifies");
        T.check(!parse(fields("allowInsecure", "1")).allowInsecure, "1 verifies");
        // the removed three-value setting and the thumbprint are not read at all, whatever they hold
        T.check(!parse(fields("vcenter_tls_validation", "Trust on First Use")).allowInsecure, "the old TOFU value is ignored");
        T.check(!parse(fields("vcenter_tls_validation", "Pinned", "vcenter_thumbprint", PIN64)).allowInsecure, "old Pinned ignored");
        T.check(!parse(fields("vcenter_tls_validation", "bogus")).allowInsecure, "an unknown old value is ignored, not an error");
        T.check(parse(fields("vcenter_tls_validation", "Enabled", "allowInsecure", "true")).allowInsecure, "only allowInsecure decides");
        T.check(VCommunityConfig.parseAllowInsecure("true") && !VCommunityConfig.parseAllowInsecure(null), "the parse itself");
        T.contains(parse(fields("allowInsecure", "true")).toString(), "verifyCertificate=false", "toString says it");
        T.done("config Allow Insecure SSL");
    }

    static void failures() {
        T.throwsIae(() -> parse(fields("host", null)), "vCenter Server", "no host");
        T.throwsIae(() -> parse(fields("host", "   ")), "vCenter Server", "blank host");
        T.throwsIae(() -> parse(fields("port", "https")), "Port 'https'", "text port");
        T.throwsIae(() -> parse(fields("port", "0")), "1 to 65535", "port 0");
        T.throwsIae(() -> parse(fields("port", "70000")), "1 to 65535", "port 70000");
        T.eq(443, parse(fields("port", "")).port, "blank port is the default");
        T.eq(8443, parse(fields("port", " 8443 ")).port, "padded port");
        T.throwsIae(() -> VCommunityConfig.parse(fields()::get, null, "pw", null, null), "User Name", "no user");
        T.throwsIae(() -> VCommunityConfig.parse(fields()::get, "u", null, null, null), "Password", "no password");
        T.throwsIae(() -> VCommunityConfig.parse(fields()::get, "u", "", null, null), "Password", "empty password");
        // the message quotes no secret
        try {
            VCommunityConfig.parse(fields("port", "bogus")::get, "u", "s3cret-pw", null, null);
            throw new AssertionError("no failure");
        } catch (IllegalArgumentException e) {
            T.notContains(e.getMessage(), "s3cret-pw", "failure message");
        }
        T.done("config failures name the field");
    }

    static void guestAndSwitches() {
        T.check(parse(fields("serviceMonitoring", "Enabled")).serviceMonitoring, "Enabled");
        T.check(!parse(fields("serviceMonitoring", "enabled")).serviceMonitoring, "case sensitive");
        T.check(!parse(fields("serviceMonitoring", "Disabled")).serviceMonitoring, "Disabled");
        T.check(parse(fields("winEventMonitoring", "Enabled")).eventMonitoring, "events Enabled");
        T.check(!parse(fields("winEventMonitoring", "true")).eventMonitoring, "events other text");
        T.eq(GuestTlsMode.STRICT, parse(fields("guest_tls_validation", "strict")).guestTlsMode, "strict");
        T.eq(GuestTlsMode.DISABLED, parse(fields("guest_tls_validation", "DISABLED")).guestTlsMode, "disabled");
        T.eq(GuestTlsMode.PINNED, parse(fields("guest_tls_validation", "")).guestTlsMode, "blank guest tls");
        // an unknown guest TLS mode does not fail the account (DV-29)
        VCommunityConfig c = parse(fields("guest_tls_validation", "Maybe"));
        T.check(c.guestTlsMode == null, "no mode");
        T.contains(c.guestTlsError, "'Maybe'", "guest tls error names the value");
        VCommunityConfig win = VCommunityConfig.parse(fields()::get, "u", "p", "DOM\\svc", "wpw");
        T.check(win.hasWindowsCredential(), "both parts");
        T.check(!VCommunityConfig.parse(fields()::get, "u", "p", "DOM\\svc", null).hasWindowsCredential(), "user only");
        T.check(!VCommunityConfig.parse(fields()::get, "u", "p", null, "wpw").hasWindowsCredential(), "password only");
        T.done("config switches and guest TLS");
    }

    static void filesAndIdentity() {
        VCommunityConfig c = parse(fields());
        Map<ListKind, String> in = c.filesInUse();
        T.eq(4, in.size(), "the four identifying files only while monitoring is off");
        T.check(!in.containsKey(ListKind.WIN_SERVICES) && !in.containsKey(ListKind.WIN_EVENTS), "gated off");
        Map<ListKind, String> svc = parse(fields("serviceMonitoring", "Enabled")).filesInUse();
        T.eq(5, svc.size(), "services on");
        T.eq("windows_service_list", svc.get(ListKind.WIN_SERVICES), "service file");
        Map<ListKind, String> both = parse(fields("serviceMonitoring", "Enabled", "winEventMonitoring", "Enabled")).filesInUse();
        T.eq(6, both.size(), "both on");
        T.eq("esxi_advanced_system_settings,esxi_packages,vm_advanced_parameters,vm_options",
                c.fileIdentityText(), "identity text");
        VCommunityConfig custom = parse(fields("esxi_adv_settings_config_file", " my_esx ",
                "vm_configuration_config_file", ""));
        T.eq("my_esx", custom.esxiSettingsFile, "trimmed only");
        T.eq("", custom.vmOptionsFile, "a cleared field stays cleared (no file)");
        T.eq("my_esx,esxi_packages,vm_advanced_parameters,", custom.fileIdentityText(), "identity as stored");
        T.eq("vc.example.test:443", c.hostPort(), "host port");
        T.eq("[fe80::1]:8443", parse(fields("host", "fe80::1", "port", "8443")).hostPort(), "ipv6 bracketed");
        T.eq("vc.example.test", c.dialledHost(), "dialled host omits the default port");
        T.eq("vc.example.test:8443", parse(fields("port", "8443")).dialledHost(), "dialled host with port");
        T.done("config files and identity");
    }

    static void redaction() {
        VCommunityConfig c = VCommunityConfig.parse(fields()::get, "svc-user", "s3cret-pw", "winu", "w1nd0ws-pw");
        String s = c.toString();
        T.notContains(s, "s3cret-pw", "toString");
        T.notContains(s, "w1nd0ws-pw", "toString");
        T.eq("s3cret-pw", c.password(), "accessor");
        T.contains(s, "windowsCredential=true", "toString says only whether it is set");
        T.done("config redaction");
    }

    static void certificateCheckUrl() {
        T.eq("https://vc.example.test:443", VCommunityConfig.certificateCheckUrl(fields()::get), "the default offers the review");
        T.eq("https://vc.example.test:8443", VCommunityConfig.certificateCheckUrl(
                fields("port", "8443")::get), "with the port");
        T.check(VCommunityConfig.certificateCheckUrl(fields("allowInsecure", "true")::get) == null, "Allow Insecure SSL: none");
        T.eq("https://vc.example.test:443", VCommunityConfig.certificateCheckUrl(fields("allowInsecure", "false")::get), "false offers it");
        T.check(VCommunityConfig.certificateCheckUrl(fields("host", " ")::get) == null, "no host");
        T.check(VCommunityConfig.certificateCheckUrl(fields("port", "x")::get) == null, "bad port never throws");
        T.done("config certificate check url");
    }
}
