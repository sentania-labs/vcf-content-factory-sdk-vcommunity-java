package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.list.CheckListStore.Outcome;
import com.vcfcf.adapters.vcommunity.list.ConfigFileFetch;
import com.vcfcf.adapters.vcommunity.list.ListKind;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsHttpException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * An absent check-list file on each line of VCF Operations. 9.x answers HTTP 400
 * with apiErrorCode 1501 in a JSON body the framework reads. 8.18.7 answers the
 * same refusal for {@code Accept: *}{@code /*} with an XML error whose code the framework does not
 * read, and the same refusal as JSON, with the code, for {@code Accept: application/json}.
 * The exact 8.18.7 XML body was NOT captured: only its prolog
 * ({@code <?xml version="1.0" encoding="UTF-8" standalone="yes"?>}) and root element
 * ({@code ops:error}, with the xsi namespace) were seen live. The body below is
 * built from those two facts and a plausible {@code <ops:message>}; the tests do
 * not depend on anything else of it.
 */
public final class ConfigFileErrorTest {

    static final String JSON_MSG = "Value \"esxi_advanced_system_settings.xml of type SOLUTION_CONFIG\" is invalid for request param.";
    static final String XML_PROLOG_ONLY = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<ops:error xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:ops=\"http://webservice.vmware.com/vRealizeOpsMgr/1.0/\" "
            + "httpStatusCode=\"400\" apiErrorCode=\"1501\"><ops:message>Value is invalid for request param.</ops:message></ops:error>";

    /** Answers by Accept header, like the 8.18.7 store. Only {@code get} is answered, so the fake does not
     *  depend on the rest of the interface (this test applies to the skeleton phase as it is). */
    static final class Line {
        final List<String> accepts = new ArrayList<>();
        OpsHttpException forStar;
        OpsHttpException forJson;

        OpsApi api() {
            return (OpsApi) java.lang.reflect.Proxy.newProxyInstance(OpsApi.class.getClassLoader(),
                    new Class<?>[] {OpsApi.class}, (proxy, m, args) -> {
                        if (!m.getName().equals("get")) throw new UnsupportedOperationException(m.getName());
                        String accept = (String) args[1];
                        accepts.add(accept);
                        OpsHttpException e = "application/json".equals(accept) ? forJson : forStar;
                        if (e != null) throw e;
                        return "<L>a</L>";
                    });
        }
    }

    static CheckListStore.Entry read(Line l) throws Exception {
        Map<ListKind, String> files = new EnumMap<>(ListKind.class);
        files.put(ListKind.ESXI_SETTINGS, "esxi_advanced_system_settings");
        return new CheckListStore(null).refresh(files, p -> ConfigFileFetch.fetch(l.api(), p)).get(ListKind.ESXI_SETTINGS);
    }

    public static void main(String[] args) throws Exception {
        // 9.x: the first answer already carries 1501
        Line nine = new Line();
        nine.forStar = new OpsHttpException("x", 400, 1501, "{\"message\":\"" + JSON_MSG.replace("\"", "\\\"") + "\",\"apiErrorCode\":1501}");
        CheckListStore.Entry e = read(nine);
        T.eq(Outcome.ABSENT, e.outcome, "9.x: 400 with 1501 is absent");
        T.contains(e.problem, "does not exist", "9.x says so");
        T.eq(1, nine.accepts.size(), "9.x: one request");

        // 8.18.7: XML error, no code read; the JSON look settles it
        Line old = new Line();
        old.forStar = new OpsHttpException("x", 400, null, XML_PROLOG_ONLY);
        old.forJson = new OpsHttpException("x", 400, 1501, "{\"message\":\"" + JSON_MSG.replace("\"", "\\\"") + "\"}");
        e = read(old);
        T.eq(Outcome.ABSENT, e.outcome, "8.18.7: absent, as on 9.x");
        T.contains(e.problem, "does not exist", "8.18.7 says so");
        T.eq(java.util.Arrays.asList("*/*", "application/json"), old.accepts, "asked for JSON once, after the refusal");

        // 8.18.7, a different refusal: still a failed read, and the excerpt is the message, not markup
        Line other = new Line();
        other.forStar = new OpsHttpException("x", 400, null, XML_PROLOG_ONLY);
        other.forJson = new OpsHttpException("x", 400, 1500, "{\"message\":\"Something else\"}");
        e = read(other);
        T.eq(Outcome.FETCH_FAILED, e.outcome, "another code is not absent");
        T.contains(e.problem, "HTTP 400", "status");

        // the JSON look fails too: the XML refusal's own message is quoted, never the prolog
        Line noJson = new Line();
        noJson.forStar = new OpsHttpException("x", 400, null, XML_PROLOG_ONLY);
        noJson.forJson = new OpsHttpException("x", 500, null, "");
        e = read(noJson);
        T.eq(Outcome.FETCH_FAILED, e.outcome, "no code anywhere: a failed read");
        T.contains(e.problem, "it said: Value is invalid for request param.", "the message text");
        T.notContains(e.problem, "<", "no markup");
        T.notContains(e.problem, "?xml", "no prolog");

        // an excerpt cut before any message: no empty 'it said:'
        Line cut = new Line();
        cut.forStar = new OpsHttpException("x", 400, null, XML_PROLOG_ONLY.substring(0, 140));
        e = read(cut);
        T.eq(Outcome.FETCH_FAILED, e.outcome, "failed read");
        T.notContains(e.problem, "it said", "nothing to say, so no clause");
        T.notContains(e.problem, "<", "no markup");

        // W11: a bare 404 on the second look is no evidence that the file is absent, and clears nothing
        Line four = new Line();
        four.forStar = new OpsHttpException("x", 400, null, XML_PROLOG_ONLY);
        four.forJson = new OpsHttpException("x", 404, null, "{\"message\":\"Not found\"}");
        Map<ListKind, String> files = new EnumMap<>(ListKind.class);
        files.put(ListKind.ESXI_SETTINGS, "esx");
        CheckListStore keep = new CheckListStore(null);
        Line good = new Line();
        keep.refresh(files, p -> ConfigFileFetch.fetch(good.api(), p)); // the list "a" is now the last good one
        e = keep.refresh(files, p -> ConfigFileFetch.fetch(four.api(), p)).get(ListKind.ESXI_SETTINGS);
        T.eq(Outcome.FETCH_FAILED, e.outcome, "a 404 on the second look does not make the file absent");
        T.check(e.lastGood && e.items.contains("a"), "and the last good list stays in force");
        T.contains(e.problem, "HTTP 400", "the status is the refused request's, not the second one's");

        // markup is never quoted, whatever carries it
        Line html = new Line();
        html.forStar = new OpsHttpException("x", 400, null, "\uFEFF<html><body>Bad Request</body></html>");
        e = read(html);
        T.notContains(e.problem, "<", "no markup from an HTML body, BOM first");
        T.notContains(e.problem, "it said", "nothing to say");
        Line jsonTag = new Line();
        jsonTag.forStar = new OpsHttpException("x", 400, null, "{\"message\":\"<b>bold</b> text\"}");
        e = read(jsonTag);
        T.notContains(e.problem, "<", "no markup from a JSON message");

        // a message in an attribute, and a non-client error is not retried
        Line attr = new Line();
        attr.forStar = new OpsHttpException("x", 400, null, "<e message=\"in an attribute\"/>");
        T.contains(read(attr).problem, "it said: in an attribute", "attribute message");
        Line down = new Line();
        down.forStar = new OpsHttpException("x", 503, null, "");
        read(down);
        T.eq(1, down.accepts.size(), "a 503 is not asked about again");
        T.done("config file errors: 9.x and 8.18.7 shapes");
    }
}
