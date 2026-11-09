package org.broad.igv.hic;

import io.jhdf.exceptions.HdfFilterException;
import io.jhdf.filter.Filter;

import java.io.IOException;

/**
 * Registers Blosc (HDF5 filter id 32001) with io.jhdf so that HDF5 files written by
 * PyTables / HiCExplorer can be read without any native library.
 */
public class BloscFilter implements Filter {

    /** HDF5 filter identifier reserved for blosc. */
    public static final int FILTER_ID = 32001;

    @Override
    public int getId() {
        return FILTER_ID;
    }

    @Override
    public String getName() {
        return "blosc";
    }

    @Override
    public byte[] decode(byte[] data, int[] filterParams) {
        try {
            return BloscDecompressor.decompress(data);
        } catch (IOException e) {
            throw new HdfFilterException("Failed to decompress blosc chunk: " + e.getMessage(), e);
        }
    }
}
