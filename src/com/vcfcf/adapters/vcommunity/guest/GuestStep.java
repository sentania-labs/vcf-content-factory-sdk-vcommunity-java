package com.vcfcf.adapters.vcommunity.guest;

import com.vcfcf.adapters.vcommunity.health.FailureCause;

/**
 * Why a guest operation on one VM gave nothing. Each has a fixed id ({@code F-GUEST-<name>}) used in the
 * health keys and in tests; a failure is never published as a service state or as "no events".
 */
public enum GuestStep {
    /** The VM's tools are not running (or the VM is off): nothing can be asked of the guest. */
    TOOLS("the VMware Tools are not running"),
    /** vCenter or the guest refused the Windows account. */
    AUTH("the guest refused the Windows account"),
    /** Guest operations are not available on this VM (tools, version, policy). */
    UNAVAILABLE("guest operations are not available"),
    /** A call, a transfer or the program took longer than its limit. */
    TIMEOUT("it took too long"),
    /** The program in the guest ended with a failure code. */
    EXIT("the program in the guest failed"),
    /** The program could not be started. */
    START("the program could not be started"),
    /** A file could not be sent to the guest. */
    UPLOAD("a file could not be sent to the guest"),
    /** A file could not be read back from the guest. */
    DOWNLOAD("the output could not be read back from the guest"),
    /** The ESXi host's certificate was not accepted, or no thumbprint is recorded for it. */
    TLS("the ESXi host's certificate was not accepted"),
    /** The output is larger than the limit. */
    TOOLARGE("the output is too large"),
    /** The output has no column the keys need. */
    HEADER("the output has no usable header"),
    /** The output is not a CSV this port can read. */
    PARSE("the output could not be read"),
    /** A row of the output was left out. */
    ROW("a row of the output was left out"),
    /** The temporary directory could not be created or has an unexpected name. */
    DIRECTORY("the temporary directory could not be made"),
    /** The temporary directory could not be removed. */
    CLEANUP("the temporary directory could not be removed"),
    /** Any other refusal of a vCenter call. */
    CALL("vCenter refused the call"),
    /** Not tried: the cycle ended, or the account was being protected. */
    SKIPPED("it was not tried");

    public final String meaning;

    GuestStep(String meaning) {
        this.meaning = meaning;
    }

    /** {@code F-GUEST-<NAME>}. */
    public String cause() {
        return FailureCause.guest(name());
    }
}
