package com.vcfcf.adapters.vcommunity.guest;

/** One thing that went wrong for one VM: a step and a credential-free sentence. */
public final class GuestFailure {
    public final GuestStep step;
    public final String message;
    /** Which script it was (services, os, events), or null when it belongs to the VM as a whole. */
    public final String script;

    public GuestFailure(GuestStep step, String script, String message) {
        this.step = step;
        this.script = script;
        // one short line, whatever the text held: nothing a guest or a fault wrote can start another log line
        this.message = GuestText.eventText(message == null ? "" : message, 300).replace('\n', ' ').replace('\t', ' ');
    }

    @Override
    public String toString() {
        return step.cause() + (script == null ? "" : " (" + script + ")") + ": " + message;
    }
}
