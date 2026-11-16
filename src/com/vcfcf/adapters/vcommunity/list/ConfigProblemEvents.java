package com.vcfcf.adapters.vcommunity.list;

import com.vcfcf.adapters.vcommunity.ops.OpsEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The warning events for check-list files that could not be used (design 4.10, DV-31): one on the vCenter's own
 * object for each file that is absent, could not be read or could not be parsed, saying so in one sentence
 * after the prefix {@code vCommunity configuration: }. An entry skipped inside a file that was read is a
 * warning in the log and a count, not an event. The text is the sentence the store wrote, made safe to carry
 * (a file name is the administrator's, an error text is the Operations API's).
 */
public final class ConfigProblemEvents {

    public static final String PREFIX = "vCommunity configuration: ";

    private ConfigProblemEvents() {}

    public static List<OpsEvent> of(Collection<CheckListStore.Entry> entries, String vcenterResourceId, long nowMs) {
        List<OpsEvent> out = new ArrayList<>();
        if (vcenterResourceId == null) return out;
        for (CheckListStore.Entry e : entries) {
            if (e.outcome == CheckListStore.Outcome.OK || e.outcome == CheckListStore.Outcome.NOT_CONFIGURED
                    || e.problem == null) {
                continue;
            }
            String text = com.vcfcf.adapters.vcommunity.guest.GuestText.lineText(e.problem, 600)
                    .replace('\n', ' ').replace('\t', ' ');
            out.add(new OpsEvent(vcenterResourceId, PREFIX + text, OpsEvent.Severity.WARNING, nowMs));
        }
        return out;
    }
}
