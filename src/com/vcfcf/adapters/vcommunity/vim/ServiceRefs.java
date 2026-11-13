package com.vcfcf.adapters.vcommunity.vim;

/**
 * The managed object references of {@code ServiceContent} that the reads
 * use. A reference the server did not send is null: the read that needs it
 * fails with a plain message instead of calling with a made-up one.
 */
public final class ServiceRefs {
    public final MoRef rootFolder;
    public final MoRef propertyCollector;
    public final MoRef viewManager;
    public final MoRef licenseManager;
    public final MoRef setting;

    public ServiceRefs(MoRef rootFolder, MoRef propertyCollector, MoRef viewManager,
            MoRef licenseManager, MoRef setting) {
        this.rootFolder = rootFolder;
        this.propertyCollector = propertyCollector;
        this.viewManager = viewManager;
        this.licenseManager = licenseManager;
        this.setting = setting;
    }

    static ServiceRefs from(XmlNode serviceContent) {
        return new ServiceRefs(ref(serviceContent, "rootFolder", "Folder"),
                ref(serviceContent, "propertyCollector", "PropertyCollector"),
                ref(serviceContent, "viewManager", "ViewManager"),
                ref(serviceContent, "licenseManager", "LicenseManager"),
                ref(serviceContent, "setting", "OptionManager"));
    }

    private static MoRef ref(XmlNode sc, String name, String defaultType) {
        XmlNode n = sc.child(name);
        if (n == null || n.text().isEmpty()) return null;
        String t = n.attr("type");
        return new MoRef(t == null ? defaultType : t, n.text());
    }
}
