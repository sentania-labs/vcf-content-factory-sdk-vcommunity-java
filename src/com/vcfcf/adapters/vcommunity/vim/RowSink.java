package com.vcfcf.adapters.vcommunity.vim;

/** Receives each object of a read as it streams past; the row is not kept by the reader. */
public interface RowSink {
    void row(BulkRow row);
}
