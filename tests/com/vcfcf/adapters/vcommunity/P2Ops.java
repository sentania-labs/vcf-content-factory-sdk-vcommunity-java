package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsEvent;
import com.vcfcf.adapters.vcommunity.ops.OpsHttpException;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Suite API for the property phase: resource lists by kind (paged as the real
 * one is), check-list files, the property read-back, and a recorder of what is
 * pushed. Safe to call from the pool.
 */
final class P2Ops implements OpsApi {

    /** Kind to its resources: {id, moref (or null), vcid}. */
    final Map<String, List<String[]>> resources = new HashMap<>();
    final Map<String, Object> files = new HashMap<>();
    /** The answer to the properties query: a JSON body, or an Exception to throw. */
    volatile Object readBack = "{\"values\":[]}";
    PushOutcome outcome = new PushOutcome(2, 1, 0, 0, 0, false);
    final List<Map<String, Map<String, Object>>> pushedProps = new ArrayList<>();
    final List<Map<String, Map<String, Double>>> pushedStats = new ArrayList<>();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> posts = new CopyOnWriteArrayList<>();
    /** What Operations holds for the derived metrics (phase P4); null when a scenario does not use it. */
    volatile P4Data data;
    /** Fail a kind's list read with this. */
    final Map<String, IOException> listFails = new HashMap<>();

    P2Ops() {
        for (String f : new String[] {"esxi_advanced_system_settings", "esxi_packages",
            "vm_advanced_parameters", "vm_options"}) {
            files.put(f, "<L></L>");
        }
        add("VMwareAdapter Instance", "vc-res", null, FakeVc.INSTANCE_UUID);
        // the one vSphere World object every Operations has (the roll-ups of phase P4 push onto it)
        add("vSphere World", "world-res", null, null);
    }

    P2Ops add(String kind, String id, String moref, String vcid) {
        resources.computeIfAbsent(kind, k -> new ArrayList<>()).add(new String[] {id, moref, vcid});
        return this;
    }

    @Override
    public String get(String path, String accept) throws IOException {
        paths.add(path);
        if (path.startsWith("/api/configurations/files?path=SolutionConfig/")) {
            String name = path.substring(path.indexOf("SolutionConfig/") + 15, path.length() - 4);
            Object r = files.get(name);
            if (r instanceof IOException) throw (IOException) r;
            if (r == null) throw new OpsHttpException("missing", 404, null);
            return (String) r;
        }
        P4Data d = data;
        if (d != null) {
            String r = d.get(path);
            if (r != null) return r;
        }
        if (path.startsWith("/api/resources?")) {
            Matcher km = Pattern.compile("resourceKind=([^&]+)").matcher(path);
            km.find();
            String kind = URLDecoder.decode(km.group(1), "UTF-8");
            IOException f = listFails.get(kind);
            if (f != null) throw f;
            int page = Integer.parseInt(path.substring(path.lastIndexOf("page=") + 5));
            List<String[]> rows = resources.getOrDefault(kind, new ArrayList<>());
            if (page > 0) return OpsTest.page(rows.size());
            String[] js = new String[rows.size()];
            for (int i = 0; i < js.length; i++) {
                String[] r = rows.get(i);
                js[i] = OpsTest.row(r[0], r[1], r[2]);
            }
            return OpsTest.page(rows.size(), js);
        }
        throw new IOException("unexpected " + path);
    }

    @Override
    public String post(String path, String body) throws IOException {
        posts.add(path + " " + body);
        P4Data d = data;
        if (d != null && !body.contains("vCommunity|Certificate|")) {
            String a = d.post(path, body);
            if (a != null) return a;
        }
        Object r = readBack;
        if (r instanceof IOException) throw (IOException) r;
        return (String) r;
    }

    @Override
    public synchronized PushOutcome pushBatch(Map<String, Map<String, Object>> properties,
            Map<String, Map<String, Double>> stats, long ts) {
        pushedProps.add(properties);
        pushedStats.add(stats);
        return outcome;
    }

    /** Every call to send events, in order; what each answered is {@link #eventOutcome}, or a clean send of all. */
    final List<List<OpsEvent>> eventPushes = new CopyOnWriteArrayList<>();
    volatile PushOutcome eventOutcome;

    @Override
    public PushOutcome pushEvents(java.util.Collection<OpsEvent> events) {
        eventPushes.add(new ArrayList<>(events));
        PushOutcome o = eventOutcome;
        return o != null ? o : new PushOutcome(events.size(), 1, 0, 0, 0, false);
    }

    int pushes() {
        return pushedProps.size();
    }

    /** The value pushed for a resource and key in push number {@code n}, or null. */
    Object prop(int n, String rid, String key) {
        Map<String, Object> m = pushedProps.get(n).get(rid);
        return m == null ? null : m.get(key);
    }

    Double stat(int n, String rid, String key) {
        Map<String, Double> m = pushedStats.get(n).get(rid);
        return m == null ? null : m.get(key);
    }
}
