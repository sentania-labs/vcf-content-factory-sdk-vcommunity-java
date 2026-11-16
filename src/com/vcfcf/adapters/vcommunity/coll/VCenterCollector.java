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
 * setting that is not there is not sent (a blank one that vCenter did return is sent as it is), and a
 * failed call is withheld and counted (DV-37, DV-112).
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

    /** What to keep of one {@code OptionValue} answer: the setting's text as returned (an empty text is a value), or null when it carries none. */
    public static String optionText(XmlNode option) {
        return PyText.scalar(option.child("value"));
    }

    /**
     * @param settings the ten settings the vCenter has, by name (a setting it lacks is absent); null when the read failed
     * @param about    the session's {@code about}
     */
    public static ObjectOut collect(Map<String, String> settings, About about) {
        ObjectOut o = new ObjectOut(KIND, null);
        // a setting the vCenter lacks sends nothing (DV-112); a read that failed is withheld and counted
        if (settings == null) {
            o.withhold(SETTINGS.size());
        } else {
            for (Map.Entry<String, String> e : SETTINGS.entrySet()) o.prop(e.getValue(), settings.get(e.getKey()));
        }
        if (about == null) {
            o.withhold(1);
        } else {
            o.prop(Vals.P + "Configuration|Patch Level", Quirks.text(about.patchLevel));
        }
        return o;
    }
}
