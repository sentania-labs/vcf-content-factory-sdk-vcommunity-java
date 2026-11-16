package com.vcfcf.adapters.vcommunity.guest;

import com.vcfcf.adapters.vcommunity.vim.VimXml;

/**
 * The Windows account the guest operations run as. The only place the password is read: it goes into the
 * authentication element of a request to vCenter and nowhere else. {@link #toString} says nothing, and
 * {@link #scrub} removes the account name and the password from any text that is about to leave the guest
 * package (a fault message, a log line, a failure cause).
 */
public final class GuestAuth {

    private static final String MASK = "***";

    private final String user;
    private final String password;

    public GuestAuth(String user, String password) {
        if (user == null || user.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("a guest account needs a user name and a password");
        }
        this.user = user;
        this.password = password;
    }

    /** The {@code auth} argument of a guest operation, as the request body carries it. */
    String element() { // REDACT-SECRET
        return "<auth xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xsi:type=\"NamePasswordAuthentication\"><interactiveSession>false</interactiveSession>"
                + "<username>" + VimXml.escape(user) + "</username>"
                + "<password>" + VimXml.escape(password) + "</password></auth>";
    }

    /**
     * The text with the password and the account name replaced by {@code ***}, in the plain form and the
     * form XML escaping gives them. The password is replaced first so a name that is part of it cannot leave
     * a fragment behind.
     */
    public String scrub(String text) {
        if (text == null) return null;
        String out = text;
        for (String secret : new String[] {password, VimXml.escape(password), user, VimXml.escape(user)}) {
            if (!secret.isEmpty()) out = out.replace(secret, MASK);
        }
        return out;
    }

    @Override
    public String toString() {
        return "GuestAuth[redacted]";
    }
}
