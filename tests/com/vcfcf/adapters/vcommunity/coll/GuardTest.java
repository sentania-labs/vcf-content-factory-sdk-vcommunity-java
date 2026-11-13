package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.MoRef;

import java.util.Collections;

/** One object's unexpected failure is counted and does not take the rest of the read with it. */
public final class GuardTest {

    public static void main(String[] args) {
        Failures f = new Failures();
        BulkRow row = BulkRow.of(new MoRef("VirtualMachine", "vm-7"), Collections.emptyMap());
        ObjectOut ok = PropertyCollection.guarded(r -> new ObjectOut("VirtualMachine", r.obj.value), row, f, "vm", "R-VM");
        check(ok != null && f.isEmpty(), "a collector that works is not touched");
        ObjectOut bad = PropertyCollection.guarded(r -> {
            throw new IllegalStateException("boom with a secret: hunter2");
        }, row, f, "vm", "R-VM");
        check(bad == null, "the object that failed gives nothing");
        check(f.readsFailed() == 1 && f.forCollector("vm") == 1, "and is counted once");
        check(f.first().contains("F-SOAP-R-VM") && f.first().contains("vm-7"), "naming the read and the object: " + f.first());
        check(!f.first().contains("hunter2") && !f.first().contains("boom"), "never the exception's own text: " + f.first());
        PropertyCollection.guarded(r -> {
            throw new NullPointerException();
        }, row, f, "vm", "R-VM");
        check(f.readsFailed() == 1, "the same object and cause is one unit");
        System.out.println("ok  an unexpected failure on one object is isolated");
    }

    static void check(boolean c, String what) {
        if (!c) throw new AssertionError(what);
    }
}
