package org.broad.igv.hic;

/**
 * Pure-Java port of {@code blosclz_decompress} from c-blosc 1.21.6 (blosc/blosclz.c).
 * <p>
 * blosclz is a small LZ77 variant: a control byte encodes either a literal run
 * (value &lt; 32) or a match (value &ge; 32), where the high 3 bits hold the match length
 * and the low 5 bits the high part of the backward distance.
 */
final class BlosclzDecompressor {

    /** Maximum backward distance that can be encoded with the short form. */
    private static final int MAX_DISTANCE = 8191;

    private BlosclzDecompressor() {
        // Utility class
    }

    /**
     * Decompress a blosclz stream.
     *
     * @param input        buffer holding the compressed stream
     * @param inputOffset  offset of the stream in {@code input}
     * @param length       compressed length in bytes
     * @param output       destination buffer
     * @param outputOffset offset in {@code output}
     * @param maxOut       maximum number of bytes to write
     * @return number of bytes written, or 0 on error (matching the C implementation)
     */
    static int decompress(byte[] input, int inputOffset, int length,
                          byte[] output, int outputOffset, int maxOut) {

        if (length == 0) {
            return 0;
        }

        int ip = inputOffset;
        final int ipLimit = inputOffset + length;
        int op = outputOffset;
        final int opLimit = outputOffset + maxOut;

        // The first control byte only carries a literal run length (low 5 bits).
        int ctrl = input[ip++] & 31;

        while (true) {
            if (ctrl >= 32) {
                // ---- match ----
                int len = (ctrl >> 5) - 1;
                int ofs = (ctrl & 31) << 8;
                int ref = op - ofs;

                if (len == 6) {
                    int code;
                    do {
                        if (ip >= ipLimit) {
                            return 0;
                        }
                        code = input[ip++] & 0xFF;
                        len += code;
                    } while (code == 255);
                }

                if (ip >= ipLimit) {
                    return 0;
                }
                int code = input[ip++] & 0xFF;
                len += 3;
                ref -= code;

                // far match: distance stored in the next two bytes
                if (code == 255 && ofs == (31 << 8)) {
                    if (ip + 1 >= ipLimit) {
                        return 0;
                    }
                    ofs = (input[ip++] & 0xFF) << 8;
                    ofs += input[ip++] & 0xFF;
                    ref = op - ofs - MAX_DISTANCE;
                }

                if (op + len > opLimit) {
                    return 0;
                }
                if (ref - 1 < outputOffset) {
                    return 0;
                }

                // Read the next control byte, but do not stop before copying: when the stream
                // ends with a match token there is no next control byte, and breaking here
                // silently dropped that last match (short output -> "decompression failed").
                final boolean moreTokens = ip < ipLimit;
                final int nextCtrl = moreTokens ? (input[ip++] & 0xFF) : 0;
                ref--;

                if (ref == op - 1) {
                    // run of a single repeated byte
                    byte value = output[ref];
                    for (int i = 0; i < len; i++) {
                        output[op + i] = value;
                    }
                } else {
                    // overlapping copy must be byte-by-byte
                    for (int i = 0; i < len; i++) {
                        output[op + i] = output[ref + i];
                    }
                }
                op += len;

                if (!moreTokens) {
                    break;
                }
                ctrl = nextCtrl;

            } else {
                // ---- literal run ----
                ctrl++;
                if (op + ctrl > opLimit) {
                    return 0;
                }
                if (ip + ctrl > ipLimit) {
                    return 0;
                }
                System.arraycopy(input, ip, output, op, ctrl);
                op += ctrl;
                ip += ctrl;

                if (ip >= ipLimit) {
                    break;
                }
                ctrl = input[ip++] & 0xFF;
            }
        }

        return op - outputOffset;
    }
}
