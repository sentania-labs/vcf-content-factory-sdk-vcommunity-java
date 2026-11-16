package com.vcfcf.adapters.vcommunity.guest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks the URL a file transfer call returns before anything is sent to it (design 8.2, DV-45). It must be
 * HTTPS, name a host, carry no user information and no control character. When the host is {@code *}, which
 * vCenter returns when it cannot tell the client the host's address, the name vCenter holds for the VM's own ESXi
 * host takes its place. The URL holds a one-time ticket in its query, so nothing here puts it in a message.
 */
final class TransferUrl {

    private static final Pattern HOST_NAME = Pattern.compile("[A-Za-z0-9._\\-]{1,253}");

    private TransferUrl() {}

    /**
     * @param esxiName the inventory name of the VM's ESXi host, or null when it is not known
     * @return the URL to use
     * @throws IllegalArgumentException the URL is not acceptable; the message does not repeat it
     */
    static String check(String url, String esxiName) {
        if (url == null || url.isEmpty() || url.length() > 2048) {
            throw new IllegalArgumentException("vCenter returned no usable transfer URL");
        }
        for (int i = 0; i < url.length(); i++) {
            if (url.charAt(i) < 0x21 || url.charAt(i) > 0x7e) {
                throw new IllegalArgumentException("the transfer URL holds a character a URL cannot carry");
            }
        }
        String u = url;
        if (u.regionMatches(true, 0, "https://*", 0, 9) && (u.length() == 9 || u.charAt(9) == ':' || u.charAt(9) == '/')) {
            if (esxiName == null || !HOST_NAME.matcher(esxiName).matches()) {
                throw new IllegalArgumentException("the transfer URL names no host and the ESXi host's name is not known");
            }
            u = u.substring(0, 8) + esxiName + u.substring(9);
        }
        URI uri;
        try {
            uri = new URI(u);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("the transfer URL is not a valid URL");
        }
        if (!"https".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("the transfer URL is not an HTTPS URL");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("the transfer URL carries user information");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("the transfer URL names no host");
        }
        return u;
    }
}
