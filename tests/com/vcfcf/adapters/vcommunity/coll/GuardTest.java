package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.MoRef;

import java.util.Collections;

/** One object's unexpected failure is counted and does not take the rest of the read with it. */
public final class GuardTest {

    public static void main(String[] args) {
        Failures f = new Failures();
        PropertyCollection.Tally t = new PropertyCollection.Tally();
        BulkRow row = BulkRow.of(new MoRef("VirtualMachine", "vm-7"), Collections.emptyMap());
        ObjectOut ok = PropertyCollection.guarded(r -> new ObjectOut("VirtualMachine", r.obj.value), row, f, "vm", "R-VM", t);
        check(ok != null && f.isEmpty() && !PropertyCollection.allFailed(t), "a collector that works is not touched");
        ObjectOut bad = PropertyCollection.guarded(r -> {
            throw new IllegalStateException("boom with a secret: hunter2");
        }, row, f, "vm", "R-VM", t);
        check(bad == null, "the object that failed gives nothing");
        check(f.readsFailed() == 1 && f.forCollector("vm") == 1, "and is counted once");
        check(f.first().contains("F-COLLECT-R-VM") && f.first().contains("vm-7"), "named apart from a vCenter fault: " + f.first());
        check(!f.first().contains("hunter2") && !f.first().contains("boom"), "never the exception's own text: " + f.first());
        check(f.valuesWithheld() == 1, "and its keys are counted as withheld");
        check(!PropertyCollection.allFailed(t), "one failure among successes is not a failed type");
        // every object threw: the collector is broken, the type is failed
        PropertyCollection.Tally all = new PropertyCollection.Tally();
        for (int i = 0; i < 3; i++) {
            PropertyCollection.guarded(r -> {
                throw new NullPointerException();
            }, row, f, "vm", "R-VM", all);
        }
        check(PropertyCollection.allFailed(all), "when every object threw the type failed");
        check(!PropertyCollection.allFailed(new PropertyCollection.Tally()), "an empty read is not a failure");
        System.out.println("ok  an unexpected failure on one object is isolated");
    }

    static void check(boolean c, String what) {
        if (!c) throw new AssertionError(what);
    }
}
