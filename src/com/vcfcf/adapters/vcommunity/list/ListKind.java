package com.vcfcf.adapters.vcommunity.list;

/** The six check-list files, with the account field that names each. */
public enum ListKind {
    ESXI_SETTINGS("esxi_adv_settings_config_file", "esxi_settings", "ESXi Advanced System Settings", Gate.ALWAYS),
    ESXI_PACKAGES("esxi_vib_driver_config_file", "esxi_packages", "ESXi Software Packages", Gate.ALWAYS),
    VM_PARAMS("vm_adv_settings_config_file", "vm_params", "VM Advanced Parameters", Gate.ALWAYS),
    VM_OPTIONS("vm_configuration_config_file", "vm_options", "VM Options", Gate.ALWAYS),
    WIN_SERVICES("win_service_config_file", "win_services", "Windows Services", Gate.SERVICE_MONITORING),
    WIN_EVENTS("win_event_config_file", "win_events", "Windows Event Log", Gate.EVENT_MONITORING);

    /** When a file is read at all (DV-28). */
    public enum Gate { ALWAYS, SERVICE_MONITORING, EVENT_MONITORING }

    /** The account field holding the file name. */
    public final String field;
    /** A short id for failure causes and logs. */
    public final String id;
    public final String title;
    public final Gate gate;

    ListKind(String field, String id, String title, Gate gate) {
        this.field = field;
        this.id = id;
        this.title = title;
        this.gate = gate;
    }

    /** The Windows event list is an XML structure passed on whole, not a comma list. */
    public boolean isEventList() {
        return this == WIN_EVENTS;
    }
}
