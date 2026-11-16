package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.text.PyObjectText;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * The text of a VM Options value that is a list of references or the tools configuration, against the stored form
 * the live Python baseline holds (second differential, 2026-11-13): single spaces, the unset marker removed.
 */
public final class PyObjectTextTest {

    static XmlNode val(String xml) throws Exception {
        String doc = "<val xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " + xml.substring(5);
        return XmlNode.parse(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)));
    }

    static String tools(String version, String install, String sync, String counter, String extra) {
        return "<val xsi:type=\"ToolsConfigInfo\"><toolsVersion>" + version + "</toolsVersion>"
                + (install == null ? "" : "<toolsInstallType>" + install + "</toolsInstallType>")
                + "<afterPowerOn>true</afterPowerOn><afterResume>true</afterResume>"
                + "<beforeGuestStandby>true</beforeGuestStandby><beforeGuestShutdown>true</beforeGuestShutdown>"
                + "<toolsUpgradePolicy>manual</toolsUpgradePolicy><syncTimeWithHostAllowed>true</syncTimeWithHostAllowed>"
                + "<syncTimeWithHost>" + sync + "</syncTimeWithHost>" + extra
                + "<lastInstallInfo><counter>" + counter + "</counter></lastInstallInfo></val>";
    }

    /**
     * The text a VM of the third live differential (build 11) holds on the Python side, exactly: the last field of the printed
     * object is written {@code fault = } and its closing brace follows one space later, so two spaces. (The Java side of that
     * dump held one space; the formatter has two, and the pak of that build was checked to have them, so what is lost is lost
     * after the push: see the design, note L1.)
     */
    static final String PYTHON_DUMP_ADFS1 = "(vim.vm.ToolsConfigInfo) { dynamicType = , dynamicProperty = (vmodl.DynamicProperty) [], "
            + "toolsVersion = 0, toolsInstallType = 'guestToolsTypeUnknown', afterPowerOn = true, afterResume = true, "
            + "beforeGuestStandby = true, beforeGuestShutdown = true, beforeGuestReboot = , toolsUpgradePolicy = 'manual', "
            + "pendingCustomization = , customizationKeyId = , syncTimeWithHostAllowed = true, syncTimeWithHost = false, "
            + "lastInstallInfo = (vim.vm.ToolsConfigInfo.ToolsLastInstallInfo) { dynamicType = , "
            + "dynamicProperty = (vmodl.DynamicProperty) [], counter = 1, fault =  } }";

    public static void main(String[] args) throws Exception {
        // the value the baseline stores for 11 of its VMs
        T.eq("(vim.vm.ToolsConfigInfo) { dynamicType = , dynamicProperty = (vmodl.DynamicProperty) [], toolsVersion = 13312, "
                + "toolsInstallType = 'guestToolsTypeOpenVMTools', afterPowerOn = true, afterResume = true, "
                + "beforeGuestStandby = true, beforeGuestShutdown = true, beforeGuestReboot = , toolsUpgradePolicy = 'manual', "
                + "pendingCustomization = , customizationKeyId = , syncTimeWithHostAllowed = true, syncTimeWithHost = false, "
                + "lastInstallInfo = (vim.vm.ToolsConfigInfo.ToolsLastInstallInfo) { dynamicType = , "
                + "dynamicProperty = (vmodl.DynamicProperty) [], counter = 0, fault =  } }",
                PyObjectText.of("config.tools", val(tools("13312", "guestToolsTypeOpenVMTools", "false", "0", ""))),
                "the tools configuration, as the baseline stores it");
        // the variants of the baseline: no install type, sync on, a counter of 41
        String noInstall = PyObjectText.of("config.tools", val(tools("2147483647", null, "false", "0", "")));
        T.contains(noInstall, "toolsVersion = 2147483647, toolsInstallType = , afterPowerOn", "an unset install type is empty");
        T.contains(PyObjectText.of("config.tools", val(tools("13312", "guestToolsTypeOpenVMTools", "true", "41", ""))),
                "syncTimeWithHost = true, lastInstallInfo", "sync on");
        // the exact string of the fresh Python dump (L1), byte for byte, with its two spaces before the closing brace
        T.eq(PYTHON_DUMP_ADFS1, PyObjectText.of("config.tools", val(tools("0", "guestToolsTypeUnknown", "false", "1", ""))),
                "the tools configuration, as the third live dump stores it");
        T.check(PYTHON_DUMP_ADFS1.endsWith("fault =  } }"), "two spaces after fault =");
        // a field the printed form does not know: not reproduced, never guessed
        T.check(PyObjectText.of("config.tools", val(tools("1", "x", "false", "0", "<customizationKeyId><id>k</id></customizationKeyId>"))) == null,
                "a key provider is not reproduced");

        // lists of references
        String one = "<val xsi:type=\"ArrayOfManagedObjectReference\"><ManagedObjectReference type=\"Datastore\">datastore-7001</ManagedObjectReference></val>";
        T.eq("(ManagedObject) [ 'vim.Datastore:datastore-7001' ]", PyObjectText.of("datastore", val(one)), "one datastore, as the baseline");
        String two = "<val xsi:type=\"ArrayOfManagedObjectReference\"><ManagedObjectReference type=\"Datastore\">datastore-1</ManagedObjectReference>"
                + "<ManagedObjectReference type=\"Datastore\">datastore-2</ManagedObjectReference></val>";
        T.eq("(ManagedObject) [ 'vim.Datastore:datastore-1', 'vim.Datastore:datastore-2' ]", PyObjectText.of("datastore", val(two)), "two");
        T.eq("(ManagedObject) []", PyObjectText.of("datastore", val("<val xsi:type=\"ArrayOfManagedObjectReference\"></val>")), "none");
        // a list under a path the dumps do not show the declared type of is not reproduced
        T.check(PyObjectText.of("network", val(one.replace("Datastore", "Network"))) == null, "another path: not guessed");
        T.check(PyObjectText.of("datastore", val("<val xsi:type=\"ArrayOfString\"><string>a</string></val>")) == null, "a list of text: not guessed");
        T.done("py object text: the tools configuration and a list of datastores");
    }
}
