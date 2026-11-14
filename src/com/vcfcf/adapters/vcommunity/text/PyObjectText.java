package com.vcfcf.adapters.vcommunity.text;

import com.vcfcf.adapters.vcommunity.vim.PathSpec;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The text the original publishes for a VM Options entry whose value is not a single scalar: pyVmomi's printed form
 * of a list of managed objects and of the one data object that is commonly listed ({@code config.tools}). The
 * stored form seen in the live dumps is what is reproduced: line breaks and indentation folded to single spaces and
 * the marker for an unset field removed. Only what the dumps show is reproduced; any other shape returns null and
 * the entry is skipped and counted (DV-26, DV-75).
 */
public final class PyObjectText {

    private PyObjectText() {}

    /** The declared element type printed in front of a list, by path (only the one the dumps show). */
    private static final Map<String, String> LIST_TYPES = new HashMap<>();

    static {
        LIST_TYPES.put("datastore", "ManagedObject");
    }

    /** What to keep of a {@code config.tools} value while it streams past: the fields printed, and the last-install counter. */
    public static final PathSpec TOOLS_SPEC = PathSpec.keeping("toolsVersion", "toolsInstallType", "afterPowerOn",
            "afterResume", "beforeGuestStandby", "beforeGuestShutdown", "beforeGuestReboot", "toolsUpgradePolicy",
            "pendingCustomization", "syncTimeWithHostAllowed", "syncTimeWithHost", "lastInstallInfo.counter");

    /** What to keep of any other option value: a list's elements, an object's top level; nothing deeper. */
    public static final PathSpec LIST_SPEC = PathSpec.keepingInElements();

    /**
     * The printed form of the value at {@code path}, or null when it is not reproduced.
     */
    public static String of(String path, XmlNode val) {
        if (val == null) return null;
        if ("config.tools".equals(path)) return toolsConfigInfo(val);
        String declared = LIST_TYPES.get(path);
        String xsi = val.xsiType();
        if (declared != null && xsi != null && "ArrayOfManagedObjectReference".equals(PyText.stripPrefix(xsi))) {
            return moRefList(declared, val);
        }
        return null;
    }

    private static String moRefList(String declared, XmlNode val) {
        if (val.hasSkipped()) return null;
        StringBuilder b = new StringBuilder("(").append(declared).append(") [");
        boolean first = true;
        for (XmlNode e : val.children()) {
            String ref = PyText.moRef(e.attr("type"), e.text());
            if (ref == null) return null;
            b.append(first ? " " : ", ").append(ref);
            first = false;
        }
        return b.append(first ? "]" : " ]").toString();
    }

    private static final List<String> BOOLS = Arrays.asList("afterPowerOn", "afterResume", "beforeGuestStandby",
            "beforeGuestShutdown", "beforeGuestReboot");

    /** One field's value in the printed form: empty for an unset field; null when the value is not reproducible. */
    private static String quoted(XmlNode v, String field) {
        XmlNode n = v.child(field);
        if (n == null) return "";
        String t = n.rawText();
        if (t.indexOf('\'') >= 0 || t.indexOf('\\') >= 0 || t.indexOf('\n') >= 0 || t.indexOf('<') >= 0) return null;
        return "'" + t + "'";
    }

    private static String whole(XmlNode v, String field) {
        XmlNode n = v.child(field);
        if (n == null) return "";
        return PyText.integer(n.text());
    }

    private static String flag(XmlNode v, String field) {
        XmlNode n = v.child(field);
        if (n == null) return "";
        String t = n.text();
        return "true".equals(t) || "false".equals(t) ? t : null;
    }

    /**
     * {@code (vim.vm.ToolsConfigInfo) { ... }} with its fifteen fields in their declared order, the stored form
     * of the live dumps. Null when any field outside those printed is present (a key provider, a last-install
     * fault, dynamic properties) or a value is not of the expected shape.
     */
    static String toolsConfigInfo(XmlNode v) {
        if (v.hasSkipped()) return null;
        XmlNode last = v.child("lastInstallInfo");
        if (last != null && last.hasSkipped()) return null;
        if (v.child("customizationKeyId") != null || v.child("dynamicProperty") != null) return null;
        String version = whole(v, "toolsVersion");
        String install = quoted(v, "toolsInstallType");
        String policy = quoted(v, "toolsUpgradePolicy");
        String pending = quoted(v, "pendingCustomization");
        String allowed = flag(v, "syncTimeWithHostAllowed");
        String sync = flag(v, "syncTimeWithHost");
        String counter = last == null ? "" : whole(last, "counter");
        if (version == null || install == null || policy == null || pending == null || allowed == null || sync == null
                || counter == null) {
            return null;
        }
        String[] b = new String[5];
        for (int i = 0; i < 5; i++) {
            b[i] = flag(v, BOOLS.get(i));
            if (b[i] == null) return null;
        }
        return "(vim.vm.ToolsConfigInfo) { dynamicType = , dynamicProperty = (vmodl.DynamicProperty) [], toolsVersion = "
                + version + ", toolsInstallType = " + install + ", afterPowerOn = " + b[0] + ", afterResume = " + b[1]
                + ", beforeGuestStandby = " + b[2] + ", beforeGuestShutdown = " + b[3] + ", beforeGuestReboot = " + b[4]
                + ", toolsUpgradePolicy = " + policy + ", pendingCustomization = " + pending
                + ", customizationKeyId = , syncTimeWithHostAllowed = " + allowed + ", syncTimeWithHost = " + sync
                + ", lastInstallInfo = (vim.vm.ToolsConfigInfo.ToolsLastInstallInfo) { dynamicType = , "
                + "dynamicProperty = (vmodl.DynamicProperty) [], counter = " + counter + ", fault =  } }";
    }
}
