package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.tls.CertificateStateAccess;

import java.io.IOException;

/** The certificate read-back answer, as the Suite API returns the latest properties. */
public final class CertificateTest {

    public static void main(String[] args) throws Exception {
        certificateParse();
    }

    static void certificateParse() throws Exception {
        T.eq("t", CertificateStateAccess.parse("{\"values\":[{\"resourceId\":\"r\",\"property-contents\":{\"property-content\":["
                + "{\"statKey\":\"vCommunity|Certificate|SHA-256 Thumbprint\",\"values\":[\"old\",\"t\"]}]}}]}", "r")[0],
                "the last sample of a text property");
        T.eq(null, CertificateStateAccess.parse("{\"values\":[{\"resourceId\":\"other\",\"property-contents\":{\"property-content\":["
                + "{\"statKey\":\"vCommunity|Certificate|SHA-256 Thumbprint\",\"values\":[\"t\"]}]}}]}", "r")[0],
                "another resource's value is not ours");
        T.eq(null, CertificateStateAccess.parse("{\"values\":[]}", "r")[0], "nothing recorded");
        T.eq(null, CertificateStateAccess.parse("{}", "r")[0], "nothing recorded");
        T.eq("5", CertificateStateAccess.parse("{\"values\":[{\"resourceId\":\"r\",\"property-contents\":{\"property-content\":["
                + "{\"statKey\":\"vCommunity|Certificate|Last Changed\",\"data\":[5]}]}}]}", "r")[1], "a numeric sample is its text");
        try {
            CertificateStateAccess.parse("[1,2]", "r");
            throw new AssertionError("an answer that is not an object must fail");
        } catch (IOException expected) {
            // the state stays unknown
        }
        try {
            CertificateStateAccess.parse("{oops", "r");
            throw new AssertionError("malformed JSON must fail");
        } catch (IOException expected) {
            // the state stays unknown
        }
        T.done("certificate read-back answer");
    }
}
