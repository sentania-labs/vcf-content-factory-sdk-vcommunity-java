package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.text.DetailedDataParser;
import com.vcfcf.adapters.vcommunity.text.LicenseKeyMask;
import com.vcfcf.adapters.vcommunity.text.PyText;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** The Python text rules (appendix B of the design), the license key mask and the detailed data pattern. */
public final class PyTextTest {

    public static void main(String[] args) throws Exception {
        booleans();
        integers();
        floats();
        dateTimes();
        scalars();
        strip();
        licenseMask();
        detailedData();
        hex16();
    }

    static void booleans() {
        T.eq("True", PyText.bool("true"), "true");
        T.eq("False", PyText.bool("false"), "false");
        T.eq("True", PyText.bool(" 1 "), "1");
        T.eq(null, PyText.bool("yes"), "not a boolean");
        T.eq(null, PyText.bool(null), "null");
        T.done("py text: booleans");
    }

    static void integers() {
        T.eq("30", PyText.integer("30"), "plain");
        T.eq("-31114", PyText.integer("-31114"), "a short, negative");
        T.eq("5", PyText.integer("+5"), "no plus sign");
        T.eq("7", PyText.integer("007"), "no leading zeros");
        T.eq("9223372036854775808", PyText.integer("9223372036854775808"), "beyond a long");
        T.eq(null, PyText.integer("1.5"), "a float is not an integer");
        T.eq(null, PyText.integer(""), "empty");
        T.done("py text: integers");
    }

    static void floats() {
        String[][] v = {{"2", "2.0"}, {"0.1", "0.1"}, {"0.00001", "1e-05"}, {"15000000000000000", "1.5e+16"},
            {"123456789", "123456789.0"}, {"1e16", "1e+16"}, {"0.0001", "0.0001"}, {"-2.5", "-2.5"},
            {"100", "100.0"}, {"1234567890123456789", "1.2345678901234568e+18"}, {"0", "0.0"},
            {"0.30000000000000004", "0.30000000000000004"}, {"1e22", "1e+22"}, {"5e-324", "5e-324"},
            {"123456789012345.6", "123456789012345.6"}, {"9999999999999998", "9999999999999998.0"}};
        for (String[] c : v) T.eq(c[1], PyText.floating(c[0]), "repr of " + c[0]);
        T.eq("nan", PyText.floating("NaN"), "nan");
        T.eq("inf", PyText.floating("INF"), "inf");
        T.eq("-inf", PyText.floating("-INF"), "-inf");
        T.eq(null, PyText.floating("abc"), "not a number");
        T.done("py text: floats");
    }

    static void dateTimes() {
        T.eq("2025-01-02 03:04:05.123000+00:00", PyText.dateTime("2025-01-02T03:04:05.123Z"), "milliseconds");
        T.eq("2025-01-02 03:04:05+00:00", PyText.dateTime("2025-01-02T03:04:05Z"), "no fraction");
        T.eq("2025-01-02 03:04:05.5+00:00".replace(".5", ".500000"), PyText.dateTime("2025-01-02T03:04:05.5Z"), "one digit");
        T.eq("2025-01-02 03:04:05.123456+00:00", PyText.dateTime("2025-01-02T03:04:05.123456789Z"), "beyond microseconds is cut");
        T.eq("2025-01-02 03:04:05+00:00", PyText.dateTime("2025-01-02T03:04:05.000Z"), "a zero fraction is not printed");
        T.eq("2025-01-02 03:04:05-05:00", PyText.dateTime("2025-01-02T03:04:05-05:00"), "an offset is kept");
        T.eq("2025-01-02 03:04:05", PyText.dateTime("2025-01-02T03:04:05"), "naive stays naive");
        T.eq("2025-01-02T03:04:05.123456+00:00", PyText.isoUtc("2025-01-02T03:04:05.123456Z"), "install date form");
        T.eq("2025-01-02T03:04:05+00:00", PyText.isoUtc("2025-01-02T03:04:05Z"), "no fraction");
        T.eq("2025-01-02T08:04:05+00:00", PyText.isoUtc("2025-01-02T03:04:05-05:00"), "converted to UTC");
        T.eq("2025-01-01T22:04:05+00:00", PyText.isoUtc("2025-01-02T03:04:05+05:00"), "across midnight");
        T.eq(null, PyText.dateTime("not a date"), "not a date");
        T.eq(null, PyText.dateTime("2025-13-02T03:04:05Z"), "month 13");
        T.check(PyText.instant("2030-01-01T00:00:00Z") != null, "an instant with an offset");
        T.eq(null, PyText.instant("2030-01-01T00:00:00"), "none for a naive time");
        T.done("py text: date times");
    }

    static XmlNode val(String xml) throws Exception {
        return XmlNode.parse(new ByteArrayInputStream(("<r xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + xml + "</r>").getBytes(StandardCharsets.UTF_8))).child("val");
    }

    static void scalars() throws Exception {
        T.eq("True", PyText.scalar(val("<val xsi:type=\"xsd:boolean\">true</val>")), "bool");
        T.eq("42", PyText.scalar(val("<val xsi:type=\"xsd:long\">42</val>")), "long");
        T.eq("0.1", PyText.scalar(val("<val xsi:type=\"xsd:double\">0.1</val>")), "double");
        T.eq("2025-01-02 03:04:05+00:00", PyText.scalar(val("<val xsi:type=\"xsd:dateTime\">2025-01-02T03:04:05Z</val>")), "date");
        T.eq("  padded ", PyText.scalar(val("<val xsi:type=\"xsd:string\">  padded </val>")), "a string keeps its spaces");
        T.eq("", PyText.scalar(val("<val xsi:type=\"xsd:string\"></val>")), "an empty string is a value");
        T.eq("green", PyText.scalar(val("<val xsi:type=\"ManagedEntityStatus\">green</val>")), "an enum is its text");
        T.eq("x", PyText.scalar(val("<val>x</val>")), "no type: the text");
        T.eq(null, PyText.scalar(val("<val xsi:type=\"ArrayOfString\"><string>a</string></val>")), "an array is not a scalar");
        T.eq(null, PyText.scalar(val("<val xsi:type=\"ArrayOfString\"/>")), "an empty array is not a scalar");
        T.eq(null, PyText.scalar(val("<val xsi:type=\"VirtualHardware\"><numCPU>1</numCPU></val>")), "an object");
        T.eq(null, PyText.scalar(val("<val type=\"HostSystem\" xsi:type=\"ManagedObjectReference\">host-1</val>")), "a reference");
        T.eq(null, PyText.scalar(val("<val xsi:type=\"xsd:boolean\">maybe</val>")), "text that does not match its type");
        T.eq(null, PyText.scalar(null), "no value");
        // a MoRef element's own type attribute is not its xsi:type
        XmlNode m = val("<val type=\"HostImageConfigManager\" xsi:type=\"ManagedObjectReference\">img</val>");
        T.eq("HostImageConfigManager", m.attr("type"), "the plain type attribute");
        T.eq("ManagedObjectReference", m.xsiType(), "the xsi type");
        XmlNode m2 = val("<val xsi:type=\"ManagedObjectReference\" type=\"HostImageConfigManager\">img</val>");
        T.eq("HostImageConfigManager", m2.attr("type"), "in the other order too");
        T.eq("ManagedObjectReference", m2.xsiType(), "in the other order too");
        T.done("py text: scalars");
    }

    static void strip() {
        T.eq("a b", PyText.strip(" \t a b \n"), "ASCII and no-break space");
        T.eq("", PyText.strip("    "), "only spaces");
        T.eq("x", PyText.strip("\u0085x\u001f"), "NEL and unit separator");
        T.done("py text: strip");
    }

    static void licenseMask() {
        T.eq("*****-*****-*****-*****-MN90P", LicenseKeyMask.mask("AB12C-DE34F-GH56I-JK78L-MN90P"), "five groups");
        T.eq("*****-*****-*****-*****-00000", LicenseKeyMask.mask("00000-00000-00000-00000-00000"), "the evaluation key");
        T.eq("*****-*****-X", LicenseKeyMask.mask("AB12C-DE34F-X"), "short last group");
        T.eq("*****-", LicenseKeyMask.mask("AB12C-"), "a trailing hyphen leaves an empty last group");
        T.eq("*****", LicenseKeyMask.mask("ABCDE"), "five characters, no hyphen: all masked");
        T.eq("****", LicenseKeyMask.mask("ABCD"), "four characters: all masked");
        T.eq("**********ABCDE", LicenseKeyMask.mask("0123456789ABCDE"), "no hyphen keeps the last five");
        T.eq(null, LicenseKeyMask.mask(""), "empty");
        T.eq(null, LicenseKeyMask.mask("   "), "blank");
        T.eq(null, LicenseKeyMask.mask(null), "none");
        String masked = LicenseKeyMask.mask("AB12C-DE34F-GH56I-JK78L-MN90P");
        T.check(!masked.contains("AB12C") && !masked.contains("JK78L"), "no earlier group survives");
        T.done("license key mask");
    }

    static void detailedData() {
        Map<String, String> m = DetailedDataParser.parse("a='1' bb=\"two words\" c='' d='x\"y' e='it''s'");
        T.eq("1", m.get("a"), "single quotes");
        T.eq("two words", m.get("bb"), "double quotes");
        T.check(!m.containsKey("c"), "an empty value is left out");
        T.eq("x", m.get("d"), "a value is cut at the first quote");
        T.eq("it", m.get("e"), "cut at the first quote");
        T.eq("  ", DetailedDataParser.parse("k='  '").getOrDefault("k", "  "), "blank values are left out");
        T.check(DetailedDataParser.parse("k='  '").isEmpty(), "blank values are left out");
        T.check(DetailedDataParser.parse(null).isEmpty(), "none");
        T.eq("b", DetailedDataParser.parse("k='a' k='b'").get("k"), "the last of a repeated name");
        T.eq("v", DetailedDataParser.parse("été='v'").get("été"), "a Unicode name, as Python's \\w");
        T.check(DetailedDataParser.parse("k='a\nb'").isEmpty(), "a value does not span a line feed");
        T.done("detailed data pattern");
    }

    static void hex16() {
        T.eq("8086", com.vcfcf.adapters.vcommunity.coll.HostCollectorAccess.hex16("-32634"), "negative short");
        T.eq("1508", com.vcfcf.adapters.vcommunity.coll.HostCollectorAccess.hex16("5384"), "positive");
        T.eq("0000", com.vcfcf.adapters.vcommunity.coll.HostCollectorAccess.hex16("0"), "zero");
        T.eq("ffff", com.vcfcf.adapters.vcommunity.coll.HostCollectorAccess.hex16("-1"), "-1");
        T.eq(null, com.vcfcf.adapters.vcommunity.coll.HostCollectorAccess.hex16("x"), "not a number");
        T.eq(null, com.vcfcf.adapters.vcommunity.coll.HostCollectorAccess.hex16(null), "none");
        T.done("pci id text");
    }
}
