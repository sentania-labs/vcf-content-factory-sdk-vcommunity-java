package com.vcfcf.adapters.vcommunity.guest;

import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which VMs the guest phase may ask and where they run, from this cycle's vCenter reads (design 8.1, DV-39): the
 * tools and guest family come from the VM read, not from a call per VM, and the ESXi host's recorded
 * thumbprint comes from the host read. It is filled by the two reads as their answers stream past, from
 * different threads, and read by the phase after both are done. A value the read could not give is not a value:
 * a VM whose tools state or family was unreadable is not asked.
 */
public final class GuestRoster {

    public static final String[] VM_PATHS = {"guest.toolsStatus", "guest.guestFamily", "runtime.powerState"};
    public static final String HOST_PATH = "summary.config.sslThumbprint";

    /** One VM of the vCenter. */
    public static final class Vm {
        public final String moref;
        public final String name;
        /** The MoRef value of the host it runs on, or null when unset or unreadable. */
        public final String host;
        /** True/false when the tools state was read; null when it could not be. */
        public final Boolean toolsOk;
        /** True/false when the guest family was read; null when it could not be. */
        public final Boolean windows;
        public final boolean poweredOn;
        /** True when {@code config.template} was read and is true. */
        public final boolean template;
        /** False when {@code config.template} could not be read. */
        public final boolean templateKnown;

        Vm(String moref, String name, String host, Boolean toolsOk, Boolean windows, boolean poweredOn,
                boolean template, boolean templateKnown) {
            this.moref = moref;
            this.name = name;
            this.host = host;
            this.toolsOk = toolsOk;
            this.windows = windows;
            this.poweredOn = poweredOn;
            this.template = template;
            this.templateKnown = templateKnown;
        }

        /** A Windows VM, not a template, whose tools answer: the guest can be asked. */
        public boolean candidate() {
            return templateKnown && !template && Boolean.TRUE.equals(windows) && Boolean.TRUE.equals(toolsOk);
        }

        /** A powered-on Windows VM whose tools do not answer. */
        public boolean toolsDown() {
            return templateKnown && !template && Boolean.TRUE.equals(windows) && Boolean.FALSE.equals(toolsOk)
                    && poweredOn;
        }
    }

    private final Map<String, Vm> vms = new ConcurrentHashMap<>();
    private final Map<String, String> thumbprints = new ConcurrentHashMap<>();
    private final Map<String, String> hostNames = new ConcurrentHashMap<>();
    private volatile boolean vmsRead;
    private volatile boolean hostsRead;

    public void resetVms() {
        vms.clear();
        vmsRead = false;
    }

    public void resetHosts() {
        thumbprints.clear();
        hostNames.clear();
        hostsRead = false;
    }

    public void vmRow(BulkRow row) {
        String tools = text(row, "guest.toolsStatus");
        String family = text(row, "guest.guestFamily");
        Boolean toolsOk = row.readable("guest.toolsStatus") ? (Boolean) "toolsOk".equals(tools) : null;
        Boolean windows = row.readable("guest.guestFamily") ? (Boolean) "windowsGuest".equals(family) : null;
        // a read that worked and found the property unset is "not Windows" / "tools not ok"; one that failed is unknown
        String template = text(row, "config.template");
        XmlNode h = row.val("runtime.host");
        String host = h == null || h.text().isEmpty() ? null : h.text();
        vms.put(row.obj.value, new Vm(row.obj.value, row.name(), host, toolsOk, windows,
                "poweredOn".equals(text(row, "runtime.powerState")), "true".equals(template),
                row.readable("config.template")));
    }

    public void hostRow(BulkRow row) {
        String n = row.name();
        if (n != null) hostNames.put(row.obj.value, n);
        String t = text(row, HOST_PATH);
        if (t != null && !t.isEmpty()) thumbprints.put(row.obj.value, t);
    }

    public void done(boolean vmsFinished, boolean hostsFinished) {
        vmsRead = vmsFinished;
        hostsRead = hostsFinished;
    }

    private static String text(BulkRow row, String path) {
        XmlNode n = row.val(path);
        return n == null ? null : n.text();
    }

    public boolean vmsRead() {
        return vmsRead;
    }

    public boolean hostsRead() {
        return hostsRead;
    }

    public List<Vm> vms() {
        return new ArrayList<>(vms.values());
    }

    public Map<String, String> thumbprints() {
        return thumbprints;
    }

    public String hostName(String hostMoref) {
        return hostMoref == null ? null : hostNames.get(hostMoref);
    }
}
