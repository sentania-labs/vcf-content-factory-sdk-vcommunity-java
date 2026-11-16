package com.vcfcf.adapters.vcommunity.vim;

/** Escapes text for a SOAP request body. */
public final class VimXml {

    private VimXml() {}

    /**
     * @throws IllegalArgumentException the text holds a character XML 1.0 cannot carry; it is
     *         refused, never dropped
     */
    public static String escape(String s) {
        return VimSoapClient.xml(s);
    }
}
