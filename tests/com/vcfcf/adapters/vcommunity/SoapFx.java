package com.vcfcf.adapters.vcommunity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand-built vim25 SOAP pieces for the fixture tests: the typed {@code val}
 * element of a property, and the objects a property collector answer holds.
 * Shapes follow what vCenter sends (a typed value, an array typed
 * {@code ArrayOf...} with one element per item, a data object with its fields),
 * with the optional parts left out the way vCenter leaves them out.
 */
final class SoapFx {

    private SoapFx() {}

    static final String NS = "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"";

    static String envelope(String inner) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope "
                + "xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" " + NS
                + "><soapenv:Body>" + inner + "</soapenv:Body></soapenv:Envelope>";
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---- typed values (a property's val element)

    static String str(String v) { return "<val xsi:type=\"xsd:string\">" + esc(v) + "</val>"; }
    static String bool(boolean v) { return "<val xsi:type=\"xsd:boolean\">" + v + "</val>"; }
    static String intv(long v) { return "<val xsi:type=\"xsd:int\">" + v + "</val>"; }
    static String longv(long v) { return "<val xsi:type=\"xsd:long\">" + v + "</val>"; }
    static String dbl(String v) { return "<val xsi:type=\"xsd:double\">" + v + "</val>"; }
    static String date(String v) { return "<val xsi:type=\"xsd:dateTime\">" + v + "</val>"; }
    static String en(String type, String v) { return "<val xsi:type=\"" + type + "\">" + esc(v) + "</val>"; }
    static String moref(String type, String v) {
        return "<val type=\"" + type + "\" xsi:type=\"ManagedObjectReference\">" + esc(v) + "</val>";
    }
    static String obj(String type, String inner) { return "<val xsi:type=\"" + type + "\">" + inner + "</val>"; }
    /** An array value; {@code items} are the elements, each already a complete element. */
    static String arr(String type, String items) { return "<val xsi:type=\"" + type + "\">" + items + "</val>"; }

    // ---- elements inside objects

    static String e(String name, String text) { return "<" + name + ">" + esc(text) + "</" + name + ">"; }
    static String e(String name, String attrs, String inner) { return "<" + name + " " + attrs + ">" + inner + "</" + name + ">"; }
    static String el(String name, String inner) { return "<" + name + ">" + inner + "</" + name + ">"; }
    static String typed(String name, String xsiType, String inner) {
        return "<" + name + " xsi:type=\"" + xsiType + "\">" + inner + "</" + name + ">";
    }

    /** One stored vCenter object: its type, MoRef value and the vals by path. */
    static final class Obj {
        final String type;
        final String moid;
        final Map<String, String> vals = new LinkedHashMap<>();
        final java.util.List<String> missing = new java.util.ArrayList<>();

        Obj(String type, String moid) {
            this.type = type;
            this.moid = moid;
        }

        Obj put(String path, String valXml) {
            vals.put(path, valXml);
            return this;
        }

        Obj name(String n) {
            return put("name", str(n));
        }

        Obj missing(String path) {
            missing.add(path);
            return this;
        }

        /** The {@code objects} element for the paths asked for; an unasked or absent path is left out. */
        String render(java.util.Set<String> asked) {
            StringBuilder b = new StringBuilder("<objects><obj type=\"" + type + "\">" + moid + "</obj>");
            for (Map.Entry<String, String> e : vals.entrySet()) {
                if (asked.contains(e.getKey())) {
                    b.append("<propSet><name>").append(e.getKey()).append("</name>")
                            .append(e.getValue()).append("</propSet>");
                }
            }
            for (String m : missing) {
                if (asked.contains(m)) {
                    b.append("<missingSet><path>").append(m).append("</path><fault xsi:type=\"LocalizedMethodFault\">"
                            + "<fault xsi:type=\"NoPermission\"/><localizedMessage>denied</localizedMessage>"
                            + "</fault></missingSet>");
                }
            }
            return b.append("</objects>").toString();
        }
    }
}
