package com.vcfcf.adapters.vcommunity.vim;

import java.util.Objects;

/** A vim25 managed object reference: the type and the value (the MoRef id). */
public final class MoRef {
    public final String type;
    public final String value;

    public MoRef(String type, String value) {
        this.type = type;
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MoRef && Objects.equals(type, ((MoRef) o).type)
                && Objects.equals(value, ((MoRef) o).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    @Override
    public String toString() {
        return type + ":" + value;
    }
}
