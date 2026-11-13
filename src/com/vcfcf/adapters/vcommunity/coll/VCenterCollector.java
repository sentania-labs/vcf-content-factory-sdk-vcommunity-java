package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.text.PyText;
import com.vcfcf.adapters.vcommunity.text.Quirks;
import com.vcfcf.adapters.vcommunity.vim.About;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The vCenter's own configuration keys (design 4.7, rows R60-R69 and R226).
 * The ten settings come from one {@code QueryOptions} for every setting; a
 * setting that is not there, or is blank, is not sent, and a failed call sends
 * none (DV-37).
 */
public final class VCenterCollector {

    public static final String KIND = "VMwareAdapter Instance";
    public static final String READ = "R-OPT";

    private VCenterCollector() {}

    /** Setting name, and the key it is sent under. */
    private static final Map<String, String> SETTINGS = new LinkedHashMap<>();

    static {
        String c = Vals.P + "Configuration|";
        SETTINGS.put("ads.timeout", c + "User Directory Timeout");
        SETTINGS.put("config.vpxd.enableDebugBrowse", c + "Enable MOB");
        SETTINGS.put("event.maxAge", c + "Database|Event retention (days)");
        SETTINGS.put("event.maxAgeEnabled", c + "Database|Event Cleanup Enabled");
        SETTINGS.put("task.maxAge", c + "Database|Task retention (days)");
        SETTINGS.put("task.maxAgeEnabled", c + "Database|Task Cleanup Enabled");
        SETTINGS.put("instance.id", c + "Runtime|vCenter Unique ID");
        SETTINGS.put("VirtualCenter.ManagedIP", c + "Runtime|vCenter managed address");
        SETTINGS.put("VirtualCenter.MaxDBConnection", c + "Database|Maximum Connections");
        SETTINGS.put("log.level", c + "Log Level");
    }

    /** The setting names the read keeps. */
    public static Set<String> settingNames() {
        return Collections.unmodifiableSet(new HashSet<>(SETTINGS.keySet()));
    }

    /** What to keep of one {@code OptionValue} answer: the setting's text, or null when it carries none. */
    public static String optionText(XmlNode option) {
        String t = PyText.scalar(option.child("value"));
        return t == null || PyText.strip(t).isEmpty() ? null : t;
    }

    /**
     * @param settings the ten settings the vCenter has, by name (a setting it lacks or leaves blank
     *                 is absent); null when the read failed
     * @param about    the session's {@code about}
     */
    public static ObjectOut collect(Map<String, String> settings, About about) {
        ObjectOut o = new ObjectOut(KIND, null);
        for (Map.Entry<String, String> e : SETTINGS.entrySet()) {
            o.propY(e.getValue(), settings == null ? null : settings.get(e.getKey()));
        }
        String patch = about == null ? null : about.patchLevel;
        o.propY(Vals.P + "Configuration|Patch Level", Quirks.text(patch));
        return o;
    }
}
