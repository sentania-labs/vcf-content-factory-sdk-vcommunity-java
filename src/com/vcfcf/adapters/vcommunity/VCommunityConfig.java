package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.list.ListKind;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The adapter instance's 18 fields and four credential fields, parsed when
 * the adapter is configured (an edit of the account configures it again).
 * SDK-free: the adapter hands in a field reader, tests hand in a
 * map. Defaults, fallbacks and warnings follow the configuration contract of
 * the original pack (design section 2.1) so an account behaves the same.
 *
 * <p>What fails and what falls back:
 * <ul>
 *   <li>A host that is blank, a port that is not a whole number, or a
 *       missing user name or password throws {@link IllegalArgumentException} with a
 *       message that names the field and quotes no secret. The cycle then
 *       fails with that message.</li>
 *   <li>The period, time, worst percent and the three monster sizes fall
 *       back to their defaults with one warning each; a blank value falls
 *       back silently.</li>
 *   <li>An unknown guest TLS mode does not fail the cycle: it is kept in
 *       {@link #guestTlsError} so the guest phase can be switched off for
 *       the cycle (DV-29).</li>
 * </ul>
 * The passwords are held but never printed: {@link #toString()} names no
 * secret.
 */
public final class VCommunityConfig {

    // Instance field keys, in describe.xml order.
    public static final String F_HOST = "host";
    public static final String F_ESXI_SETTINGS_FILE = "esxi_adv_settings_config_file";
    public static final String F_ESXI_PACKAGES_FILE = "esxi_vib_driver_config_file";
    public static final String F_VM_PARAMS_FILE = "vm_adv_settings_config_file";
    public static final String F_VM_OPTIONS_FILE = "vm_configuration_config_file";
    public static final String F_PORT = "port";
    /** Allow Insecure SSL: the same key as the other paks; only the text "true" (any case) turns verification off. */
    public static final String F_ALLOW_INSECURE = "allowInsecure";
    public static final String F_PERIOD = "cluster_check_period_hours";
    public static final String F_CHECK_TIME = "periodic_cluster_check_time";
    public static final String F_WORST_PERCENT = "cluster_check_worst_percent";
    public static final String F_MONSTER_CPU = "monster_vm_cpu_percent_of_cores";
    public static final String F_MONSTER_MEM = "monster_vm_memory_percent_of_dram";
    public static final String F_MONSTER_MEM_MIN = "monster_vm_memory_min_gb";
    public static final String F_WIN_SERVICE_FILE = "win_service_config_file";
    public static final String F_WIN_EVENT_FILE = "win_event_config_file";
    public static final String F_SERVICE_MONITORING = "serviceMonitoring";
    public static final String F_GUEST_TLS = "guest_tls_validation";
    public static final String F_EVENT_MONITORING = "winEventMonitoring";

    // Credential field keys.
    public static final String C_USER = "user";
    public static final String C_PASSWORD = "password";
    public static final String C_WIN_USER = "winUser";
    public static final String C_WIN_PASSWORD = "winPass";

    // Defaults (describe.xml carries the same values; a test pins that).
    public static final int DEFAULT_PORT = 443;
    public static final String DEFAULT_ESXI_SETTINGS_FILE = "esxi_advanced_system_settings";
    public static final String DEFAULT_ESXI_PACKAGES_FILE = "esxi_packages";
    public static final String DEFAULT_VM_PARAMS_FILE = "vm_advanced_parameters";
    public static final String DEFAULT_VM_OPTIONS_FILE = "vm_options";
    public static final int DEFAULT_PERIOD_HOURS = 24;
    public static final String DEFAULT_CHECK_TIME = "00:00";
    public static final int DEFAULT_WORST_PERCENT = 5;
    public static final int MAX_WORST_PERCENT = 50;
    public static final int DEFAULT_MONSTER_CPU_PERCENT = 50;
    public static final int DEFAULT_MONSTER_MEM_PERCENT = 40;
    public static final int DEFAULT_MONSTER_MEM_MIN_GB = 256;
    public static final String DEFAULT_WIN_SERVICE_FILE = "windows_service_list";
    public static final String DEFAULT_WIN_EVENT_FILE = "windows_event_list";
    /** The period choices, as stored in the account (zero padded so the UI sorts them). */
    public static final String[] PERIOD_CHOICES = {"01", "02", "04", "06", "08", "12", "24"};

    /**
     * Whether the account turned certificate verification off. Only the text {@code true} (any case, not trimmed:
     * a stored " true" connects verified, as in the other paks) does; blank, absent, {@code false} and anything
     * else verify. There is no other value to read, and the old three-value setting is not read at all.
     */
    public static boolean parseAllowInsecure(String text) {
        return "true".equalsIgnoreCase(text);
    }

    /** How the guest file transfer certificate is treated (guest phase, later). */
    public enum GuestTlsMode {
        PINNED("Pinned"),
        STRICT("Strict"),
        DISABLED("Disabled");

        public final String label;

        GuestTlsMode(String label) {
            this.label = label;
        }

        public static final GuestTlsMode DEFAULT = PINNED;

        public static GuestTlsMode parse(String text) {
            String t = text == null ? "" : text.trim();
            if (t.isEmpty() || t.equalsIgnoreCase("null")) return DEFAULT;
            for (GuestTlsMode m : values()) {
                if (m.label.equalsIgnoreCase(t)) return m;
            }
            return null;
        }
    }

    public final String host;
    public final int port;
    /** True when verification of the vCenter certificate is off (Allow Insecure SSL). */
    public final boolean allowInsecure;

    public final String esxiSettingsFile;
    public final String esxiPackagesFile;
    public final String vmParamsFile;
    public final String vmOptionsFile;
    public final String winServiceFile;
    public final String winEventFile;

    public final int periodHours;
    public final int checkHour;
    public final int checkMinute;
    public final int worstPercent;
    public final int monsterCpuPercent;
    public final int monsterMemPercent;
    public final int monsterMemMinGb;

    public final boolean serviceMonitoring;
    public final boolean eventMonitoring;
    /** Null when {@link #guestTlsError} is set. */
    public final GuestTlsMode guestTlsMode;
    /** Why the guest TLS mode is unusable, or null. Never fails the cycle (DV-29). */
    public final String guestTlsError;

    public final String user;
    public final String winUser;
    private final String password;
    private final String winPassword;

    /** One entry per field that fell back to its default, in field order. */
    public final List<String> warnings;

    private VCommunityConfig(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.allowInsecure = b.allowInsecure;
        this.esxiSettingsFile = b.esxiSettingsFile;
        this.esxiPackagesFile = b.esxiPackagesFile;
        this.vmParamsFile = b.vmParamsFile;
        this.vmOptionsFile = b.vmOptionsFile;
        this.winServiceFile = b.winServiceFile;
        this.winEventFile = b.winEventFile;
        this.periodHours = b.periodHours;
        this.checkHour = b.checkHour;
        this.checkMinute = b.checkMinute;
        this.worstPercent = b.worstPercent;
        this.monsterCpuPercent = b.monsterCpuPercent;
        this.monsterMemPercent = b.monsterMemPercent;
        this.monsterMemMinGb = b.monsterMemMinGb;
        this.serviceMonitoring = b.serviceMonitoring;
        this.eventMonitoring = b.eventMonitoring;
        this.guestTlsMode = b.guestTlsMode;
        this.guestTlsError = b.guestTlsError;
        this.user = b.user;
        this.password = b.password;
        this.winUser = b.winUser;
        this.winPassword = b.winPassword;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(b.warnings));
    }

    private static final class Builder {
        String host;
        int port;
        boolean allowInsecure;
        String esxiSettingsFile, esxiPackagesFile, vmParamsFile, vmOptionsFile;
        String winServiceFile, winEventFile;
        int periodHours, checkHour, checkMinute, worstPercent;
        int monsterCpuPercent, monsterMemPercent, monsterMemMinGb;
        boolean serviceMonitoring, eventMonitoring;
        GuestTlsMode guestTlsMode;
        String guestTlsError;
        String user, password, winUser, winPassword;
        final List<String> warnings = new ArrayList<>();
    }

    /**
     * Parse an account.
     *
     * @param field reads an instance field by key; null when the account has
     *              no stored value (the default then applies)
     * @throws IllegalArgumentException the account cannot be used; the message
     *         names the field and quotes no secret
     */
    public static VCommunityConfig parse(Function<String, String> rawField, String user,
            String password, String winUser, String winPassword) {
        Builder b = new Builder();
        // A form that preselects nothing stores the text "null" for a setting nobody touched; that, a blank
        // and an absent value all mean "not set" and take the default without a word. A check-list file field
        // keeps its own rule (a blank one reads no file, DV-62), but the text "null" is still "not set".
        Function<String, String> field = k -> unset(rawField.apply(k));
        Function<String, String> fileField = k -> {
            String v = rawField.apply(k);
            return v != null && "null".equalsIgnoreCase(v.trim()) ? null : v;
        };

        b.host = trimToNull(field.apply(F_HOST));
        if (b.host == null) {
            throw new IllegalArgumentException("vCenter Server is not set on the account");
        }
        b.port = parsePort(field.apply(F_PORT));
        b.allowInsecure = parseAllowInsecure(field.apply(F_ALLOW_INSECURE));

        b.esxiSettingsFile = fileName(fileField.apply(F_ESXI_SETTINGS_FILE), DEFAULT_ESXI_SETTINGS_FILE);
        b.esxiPackagesFile = fileName(fileField.apply(F_ESXI_PACKAGES_FILE), DEFAULT_ESXI_PACKAGES_FILE);
        b.vmParamsFile = fileName(fileField.apply(F_VM_PARAMS_FILE), DEFAULT_VM_PARAMS_FILE);
        b.vmOptionsFile = fileName(fileField.apply(F_VM_OPTIONS_FILE), DEFAULT_VM_OPTIONS_FILE);
        b.winServiceFile = fileName(fileField.apply(F_WIN_SERVICE_FILE), DEFAULT_WIN_SERVICE_FILE);
        b.winEventFile = fileName(fileField.apply(F_WIN_EVENT_FILE), DEFAULT_WIN_EVENT_FILE);

        b.periodHours = parsePeriod(field.apply(F_PERIOD), b.warnings);
        int[] hm = parseCheckTime(field.apply(F_CHECK_TIME), b.warnings);
        b.checkHour = hm[0];
        b.checkMinute = hm[1];
        b.worstPercent = parseWholeNumber(field.apply(F_WORST_PERCENT), 0, MAX_WORST_PERCENT,
                DEFAULT_WORST_PERCENT, "Cluster Check Worst Percent", b.warnings);
        b.monsterCpuPercent = parseWholeNumber(field.apply(F_MONSTER_CPU), 1, 100,
                DEFAULT_MONSTER_CPU_PERCENT, "Monster VM CPU (% of host cores)", b.warnings);
        b.monsterMemPercent = parseWholeNumber(field.apply(F_MONSTER_MEM), 1, 100,
                DEFAULT_MONSTER_MEM_PERCENT, "Monster VM Memory (% of host DRAM)", b.warnings);
        b.monsterMemMinGb = parseWholeNumber(field.apply(F_MONSTER_MEM_MIN), 1, Integer.MAX_VALUE,
                DEFAULT_MONSTER_MEM_MIN_GB, "Monster VM Memory Minimum (GB)", b.warnings);

        // Case sensitive, as the original compares (parity).
        b.serviceMonitoring = parseSwitch(field.apply(F_SERVICE_MONITORING),
                "Guest OS Service Monitoring Status", b.warnings);
        b.eventMonitoring = parseSwitch(field.apply(F_EVENT_MONITORING),
                "Windows Event Log Monitoring Status", b.warnings);

        String guestRaw = field.apply(F_GUEST_TLS);
        b.guestTlsMode = GuestTlsMode.parse(guestRaw);
        if (b.guestTlsMode == null) {
            b.guestTlsError = "Guest File Transfer TLS Validation '" + shown(guestRaw)
                    + "' is not one of: Pinned, Strict, Disabled";
        }

        if (isBlank(user)) {
            throw new IllegalArgumentException("vCenter User Name is not set on the account");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("vCenter Password is not set on the account");
        }
        b.user = user.trim();
        b.password = password;
        b.winUser = trimToNull(winUser);
        b.winPassword = winPassword == null || winPassword.isEmpty() ? null : winPassword;
        return new VCommunityConfig(b);
    }

    /** The vCenter password; never log it. */
    public String password() { // REDACT-SECRET
        return password;
    }

    /** The Windows guest password, or null; never log it. */
    public String winPassword() { // REDACT-SECRET
        return winPassword;
    }

    /** Whether a Windows guest credential is set (both parts). */
    public boolean hasWindowsCredential() {
        return winUser != null && winPassword != null;
    }

    /**
     * The check-list files to read: the four identifying lists, and the
     * Windows service and event lists only when their monitoring is switched
     * on (DV-28). The value is the file name as the account holds it (blank:
     * nothing to read).
     */
    public Map<ListKind, String> filesInUse() {
        Map<ListKind, String> files = new EnumMap<>(ListKind.class);
        files.put(ListKind.ESXI_SETTINGS, esxiSettingsFile);
        files.put(ListKind.ESXI_PACKAGES, esxiPackagesFile);
        files.put(ListKind.VM_PARAMS, vmParamsFile);
        files.put(ListKind.VM_OPTIONS, vmOptionsFile);
        if (serviceMonitoring) files.put(ListKind.WIN_SERVICES, winServiceFile);
        if (eventMonitoring) files.put(ListKind.WIN_EVENTS, winEventFile);
        return files;
    }

    /** The four identifying check-list file names, in field order. */
    public String[] fileIdentity() {
        return new String[] {esxiSettingsFile, esxiPackagesFile, vmParamsFile, vmOptionsFile};
    }

    /** The four identifying file names joined with a comma, as stored (trimmed only). */
    public String fileIdentityText() {
        return String.join(",", fileIdentity());
    }

    /**
     * The host as it is dialled: a Unicode name in its punycode ({@code xn--})
     * form, anything else as entered. The certificate name check wants this form.
     */
    public String asciiHost() {
        if (host.indexOf(':') >= 0 || host.chars().allMatch(c -> c < 128)) return host;
        try {
            return java.net.IDN.toASCII(host);
        } catch (IllegalArgumentException e) {
            return host; // unusable: the name check then refuses every handshake
        }
    }

    /** {@code host:port} with a bracketed IPv6 literal, for URLs. */
    public String hostPort() {
        return urlHost(asciiHost()) + ":" + port;
    }

    /** The host for the certificate name check, with the port when not 443. */
    public String dialledHost() {
        return port == DEFAULT_PORT ? asciiHost() : urlHost(asciiHost()) + ":" + port;
    }

    /**
     * The URL Validate Connection offers for certificate review: not when Allow
     * Insecure SSL is on (nothing is verified, so there is nothing to accept), and
     * only when the host and port are usable.
     * Never throws; null means no URL.
     */
    public static String certificateCheckUrl(Function<String, String> field) {
        try {
            String host = trimToNull(field.apply(F_HOST));
            if (parseAllowInsecure(field.apply(F_ALLOW_INSECURE)) || host == null) return null;
            String ascii = host;
            if (host.indexOf(':') < 0 && !host.chars().allMatch(c -> c < 128)) {
                ascii = java.net.IDN.toASCII(host);
            }
            return "https://" + urlHost(ascii) + ":" + parsePort(field.apply(F_PORT));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String urlHost(String host) {
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) return "[" + host + "]";
        return host;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * A setting that was left alone: null, blank or the text {@code null} in any case. All three mean "not set",
     * and the caller takes the default without a warning.
     */
    static String unset(String text) {
        if (text == null) return null;
        String t = text.trim();
        return t.isEmpty() || t.equalsIgnoreCase("null") ? null : text;
    }

    /** Enabled/Disabled choice; not set is Disabled; any other text is Disabled and warned about once. */
    private static boolean parseSwitch(String text, String label, List<String> warnings) {
        if (text == null || "Disabled".equals(text)) return false;
        if ("Enabled".equals(text)) return true; // case sensitive, as the original compares (parity)
        warnings.add(label + " '" + shown(text) + "' is not one of: Enabled, Disabled. Using Disabled.");
        return false;
    }

    private static int parsePort(String text) {
        String t = unset(text) == null ? "" : text.trim();
        if (t.isEmpty()) return DEFAULT_PORT;
        int p;
        try {
            p = Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port '" + shown(text)
                    + "' is not a whole number from 1 to 65535");
        }
        if (p < 1 || p > 65535) {
            throw new IllegalArgumentException("Port " + p + " is not a whole number from 1 to 65535");
        }
        return p;
    }

    private static int parsePeriod(String text, List<String> warnings) {
        String v = text == null ? "" : text.trim();
        if (v.matches("[0-9]+") && v.length() <= 3) {
            String padded = String.format("%02d", Integer.parseInt(v));
            for (String choice : PERIOD_CHOICES) {
                if (choice.equals(padded)) return Integer.parseInt(choice);
            }
        }
        if (!v.isEmpty()) {
            warnings.add("Cluster Check Period '" + shown(text) + "' is not one of "
                    + String.join(", ", PERIOD_CHOICES) + ". Using " + DEFAULT_PERIOD_HOURS
                    + " hours.");
        }
        return DEFAULT_PERIOD_HOURS;
    }

    private static int[] parseCheckTime(String text, List<String> warnings) {
        String v = text == null ? "" : text.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,9})\\s*:\\s*(\\d{1,9})$").matcher(v);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int mi = Integer.parseInt(m.group(2));
            if (h >= 0 && h <= 23 && mi >= 0 && mi <= 59) return new int[] {h, mi};
        }
        if (!v.isEmpty()) {
            warnings.add("Cluster Check Time '" + shown(text) + "' is not HH:MM. Using "
                    + DEFAULT_CHECK_TIME + " UTC.");
        }
        return new int[] {0, 0};
    }

    private static int parseWholeNumber(String text, int lo, int hi, int dflt, String label,
            List<String> warnings) {
        String v = text == null ? "" : text.trim();
        if (v.matches("[0-9]{1,9}")) {
            int n = Integer.parseInt(v);
            if (n >= lo && n <= hi) return n;
        }
        if (!v.isEmpty()) {
            warnings.add(label + " '" + shown(text) + "' is not a whole number "
                    + (hi == Integer.MAX_VALUE ? "of " + lo + " or more" : "from " + lo + " to " + hi)
                    + ". Using " + dflt + ".");
        }
        return dflt;
    }

    /** A file field: absent means the default; present means as typed, trimmed (blank = none). */
    private static String fileName(String text, String dflt) {
        if (text == null) return dflt;
        return text.trim();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** A user-typed setting value for a message: bounded, one line. */
    static String shown(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return t.length() > 40 ? t.substring(0, 40) + "..." : t;
    }

    @Override
    public String toString() {
        return "host=" + host + " port=" + port + " verifyCertificate=" + !allowInsecure
                + " period=" + periodHours + "h at " + String.format("%02d:%02d", checkHour, checkMinute)
                + " services=" + serviceMonitoring + " events=" + eventMonitoring
                + " windowsCredential=" + hasWindowsCredential();
    }
}
