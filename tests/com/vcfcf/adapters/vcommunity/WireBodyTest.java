package com.vcfcf.adapters.vcommunity;

import com.integrien.alive.common.adapter3.Logger;
import com.integrien.alive.common.adapter3.ResourceStatus;
import com.integrien.alive.common.adapter3.config.ResourceConfig;
import com.vcfcf.adapter.VcfCfAdapter;
import com.vcfcf.adapter.spi.VcfCfCollector;
import com.vcfcf.adapter.spi.VcfCfTester;
import com.vcfcf.adapter.stitch.SuiteApiStitcher;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bytes on the wire for a property whose text holds two spaces in a row: the tools configuration of a VM,
 * {@code Options|config.tools}, ends {@code fault =  } }} (two spaces, as the Python original prints it).
 *
 * <p>The property goes the whole way: the real cycle over the fake vCenter reads it, formats it, queues it and
 * selects it for sending (the property phase, as every test of it); the batch the cycle hands to the Suite API port
 * is then pushed by the real {@link OpsPort} and the real framework client ({@code SuiteApiStitcher}, the very class
 * the pak ships), whose HTTP connection is a fake Suite API that keeps the bytes of every request body. The test
 * asserts the two spaces in those bytes, not in a Java string.
 *
 * <p>Needs the VCF Operations SDK jar, the framework jar ({@code vcfcf-adapter-base.jar}) and log4j on the class
 * path, so {@code ci/run_java_tests.sh} runs it only when {@code VCFCF_WIRE_CP} names them (a colon separated list);
 * the repository's CI never has the SDK jar. Without it the property is still asserted up to the Suite API port by
 * {@code PropertiesTest} (the VM Options scenario).
 */
public final class WireBodyTest {

    static final String TWO_SPACES = "fault =  } }";
    static final String ONE_SPACE = "fault = } }";
    static final String VM_UUID = "6baa06d3-0811-45a2-ba2a-c7b38f646ce9";
    static final String BASE = "https://localhost/suite-api";

    public static void main(String[] args) throws Exception {
        Coverage.scenario = "WireBodyTest";
        try (FakeVc fake = new FakeVc(FakeVc.happy())) {
            toolsConfigOnTheWire(fake);
        }
        T.done("wire: the two spaces of Options|config.tools are in the bytes sent to the Suite API");
    }

    static void toolsConfigOnTheWire(FakeVc fake) throws Exception {
        FakeVcenter vc = PropertiesTest.dataset();
        SoapFx.Obj vm1 = vc.byType.get("VirtualMachine").get(0);
        vm1.put("config.tools", SoapFx.obj("ToolsConfigInfo", SoapFx.e("toolsVersion", "13312")
                + SoapFx.e("toolsInstallType", "guestToolsTypeOpenVMTools") + SoapFx.e("afterPowerOn", "true")
                + SoapFx.e("afterResume", "true") + SoapFx.e("beforeGuestStandby", "true")
                + SoapFx.e("beforeGuestShutdown", "true") + SoapFx.e("toolsUpgradePolicy", "manual")
                + SoapFx.e("syncTimeWithHostAllowed", "true") + SoapFx.e("syncTimeWithHost", "false")
                + SoapFx.el("lastInstallInfo", SoapFx.e("counter", "0"))));
        try (PropertiesTest.Rig r = PropertiesTest.standard(fake, vc)) {
            PropertiesTest.files(r.ops, "", "", "", "config.tools");
            r.run();

            // 1. what the cycle hands to the Suite API port: the Java string, two spaces
            String key = PropertiesTest.V + "Options|config.tools";
            Object sent = r.ops.pushedProps.get(0).get(PropertiesTest.VM).get(key);
            T.check(sent instanceof String, "the property is queued as a string");
            String text = (String) sent;
            T.check(text.endsWith(TWO_SPACES), "two spaces at the port: " + text);
            T.check(!text.endsWith(ONE_SPACE), "not one space at the port");

            // 2. the same batch through the real port and the real framework client, onto a fake connection
            Map<String, Map<String, Object>> batch = new LinkedHashMap<>();
            batch.put(VM_UUID, new LinkedHashMap<>(r.ops.pushedProps.get(0).get(PropertiesTest.VM)));
            Wire wire = new Wire();
            SuiteApiStitcher stitcher = SuiteApiStitcher.createExplicit(wire.adapter, Logger.getDefaultLogger("wire"),
                    "localhost", "svc-user", "svc-pass");
            PushOutcome o = new OpsPort(stitcher).pushBatch(batch, new LinkedHashMap<>(), PropertiesTest.NOW);
            T.check(o.sent > 0 && o.batchesFailed == 0, "the batch was sent: " + o);

            byte[] body = wire.bodyOf(BASE + "/api/resources/properties");
            T.check(body != null, "a properties request reached the fake Suite API");
            String bytesAsText = new String(body, StandardCharsets.UTF_8);
            T.check(indexOf(body, TWO_SPACES.getBytes(StandardCharsets.UTF_8)) >= 0,
                    "two spaces in the request bytes: " + bytesAsText);
            T.check(indexOf(body, ONE_SPACE.getBytes(StandardCharsets.UTF_8)) < 0,
                    "no single-space variant in the request bytes");
            // the whole value, as one JSON string, is the Java string unchanged (it holds no quote or backslash)
            T.check(text.indexOf('"') < 0 && text.indexOf('\\') < 0, "the value needs no JSON escape");
            T.contains(bytesAsText, "{\"statKey\":\"" + key + "\",\"timestamps\":[" + PropertiesTest.NOW
                    + "],\"values\":[\"" + text + "\"]}", "the value is sent whole, in one JSON string");
        }
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ the fake Suite API

    /** The adapter whose platform connection is the fake Suite API, and the bytes it received. */
    static final class Wire {
        final List<String> urls = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        final FakeAdapter adapter;

        Wire() throws Exception {
            adapter = allocate(FakeAdapter.class);
            adapter.wire = this;
        }

        byte[] bodyOf(String url) {
            byte[] last = null;
            for (int i = 0; i < urls.size(); i++) if (urls.get(i).equals(url)) last = bodies.get(i);
            return last;
        }
    }

    static class FakeAdapter extends VcfCfAdapter<Object> {
        Wire wire;

        @Override
        protected void configureAdapter(ResourceStatus s, ResourceConfig rc) { }

        @Override
        @SuppressWarnings("rawtypes")
        protected VcfCfTester getTester() { return null; }

        @Override
        @SuppressWarnings("rawtypes")
        protected VcfCfCollector getCollector() { return null; }

        @Override
        public URLConnection openPlatformConnection(String url) throws IOException {
            return new Conn(new URL(url), wire);
        }
    }

    /** A connection that keeps the request body bytes and answers a token, then 200. */
    static final class Conn extends HttpURLConnection {
        final Wire wire;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        String answer;

        Conn(URL url, Wire wire) {
            super(url);
            this.wire = wire;
        }

        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void setRequestProperty(String k, String v) { }
        @Override public OutputStream getOutputStream() { return out; }

        private void answer() {
            if (answer == null) {
                wire.urls.add(url.toString());
                wire.bodies.add(out.toByteArray());
                answer = url.toString().endsWith("/api/auth/token/acquire") ? "{\"token\":\"tok-1\"}" : "{}";
            }
        }

        @Override public int getResponseCode() { answer(); return 200; }

        @Override public InputStream getInputStream() {
            answer();
            return new ByteArrayInputStream(answer.getBytes(StandardCharsets.UTF_8));
        }

        @Override public InputStream getErrorStream() { return null; }
    }

    @SuppressWarnings("unchecked")
    static <X> X allocate(Class<X> cls) throws Exception {
        java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        Method m = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return (X) m.invoke(unsafe, cls);
    }
}
