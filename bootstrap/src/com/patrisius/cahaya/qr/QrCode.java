package com.patrisius.cahaya.qr;

/**
 * Minimal QR encoder: byte mode, error-correction level M, versions 1-4.
 * Zero dependencies. Enough for short URLs like http://192.168.1.11:8081.
 *
 * <p>Usage: {@code boolean[][] m = QrCode.encode("http://192.168.1.11:8081");}
 * {@code true} = dark module.
 */
public final class QrCode {

    private QrCode() {
    }

    /** version, total codewords, EC codewords per block, block count, alignment centers */
    private static final int[][] VERSIONS = {
            // v, total, ecPerBlock, blocks, align...
            {1, 26, 10, 1},
            {2, 44, 16, 1, 6, 18},
            {3, 70, 26, 1, 6, 22},
            {4, 100, 18, 2, 6, 26},
    };
    /** Max byte-mode chars per version at EC level M. */
    private static final int[] CAPACITY = {14, 26, 42, 62};

    public static boolean[][] encode(String text) {
        byte[] data;
        try {
            data = text.getBytes("ISO-8859-1");
        } catch (Exception e) {
            data = text.getBytes();
        }
        int version = 0;
        for (int i = 0; i < CAPACITY.length; i++) {
            if (data.length <= CAPACITY[i]) {
                version = i + 1;
                break;
            }
        }
        if (version == 0) {
            throw new IllegalArgumentException("text too long for QR v4-M");
        }
        int[] v = VERSIONS[version - 1];
        int totalCw = v[1], ecPerBlock = v[2], numBlocks = v[3];
        int dataCw = totalCw - ecPerBlock * numBlocks;

        // --- bit stream: mode(0100) + count(8 bits) + data + terminator + pad ---
        BitBuffer bits = new BitBuffer();
        bits.append(0b0100, 4);
        bits.append(data.length, 8);
        for (byte b : data) {
            bits.append(b & 0xFF, 8);
        }
        int capacityBits = dataCw * 8;
        bits.append(0, Math.min(4, capacityBits - bits.size())); // terminator
        while (bits.size() % 8 != 0) {
            bits.append(0, 1);
        }
        int pad = 0xEC;
        while (bits.size() < capacityBits) {
            bits.append(pad, 8);
            pad = (pad == 0xEC) ? 0x11 : 0xEC;
        }
        byte[] dataCodewords = bits.toBytes();

        // --- split into blocks, Reed-Solomon EC per block, interleave ---
        int dataPerBlock = dataCw / numBlocks;
        byte[][] blocks = new byte[numBlocks][dataPerBlock];
        for (int i = 0; i < numBlocks; i++) {
            System.arraycopy(dataCodewords, i * dataPerBlock, blocks[i], 0, dataPerBlock);
        }
        byte[][] ecBlocks = new byte[numBlocks][ecPerBlock];
        for (int i = 0; i < numBlocks; i++) {
            ecBlocks[i] = reedSolomon(blocks[i], ecPerBlock);
        }
        byte[] finalCw = new byte[totalCw];
        int p = 0;
        for (int i = 0; i < dataPerBlock; i++) {
            for (int b = 0; b < numBlocks; b++) {
                finalCw[p++] = blocks[b][i];
            }
        }
        for (int i = 0; i < ecPerBlock; i++) {
            for (int b = 0; b < numBlocks; b++) {
                finalCw[p++] = ecBlocks[b][i];
            }
        }

        // --- matrix with function patterns ---
        int size = 17 + 4 * version;
        boolean[][] modules = new boolean[size][size];
        boolean[][] fixed = new boolean[size][size];
        placeFinder(modules, fixed, 0, 0);
        placeFinder(modules, fixed, size - 7, 0);
        placeFinder(modules, fixed, 0, size - 7);
        // timing
        for (int i = 8; i < size - 8; i++) {
            boolean dark = (i % 2 == 0);
            modules[6][i] = dark;
            fixed[6][i] = true;
            modules[i][6] = dark;
            fixed[i][6] = true;
        }
        // alignment patterns
        int[] align = new int[v.length - 4];
        System.arraycopy(v, 4, align, 0, align.length);
        for (int ay : align) {
            for (int ax : align) {
                if ((ax == 6 && ay == 6) || (ax == 6 && ay == size - 7)
                        || (ax == size - 7 && ay == 6)) {
                    continue; // overlaps a finder
                }
                placeAlignment(modules, fixed, ax, ay);
            }
        }
        // dark module: column 8, row 4*version+9
        modules[4 * version + 9][8] = true;
        fixed[4 * version + 9][8] = true;
        // reserve format info areas
        for (int i = 0; i <= 8; i++) {
            if (i != 6) {
                fixed[8][i] = true;
                fixed[i][8] = true;
            }
        }
        for (int i = 0; i < 7; i++) {
            fixed[size - 7 + i][8] = true; // vertical: bits 14..8
        }
        for (int i = 0; i < 8; i++) {
            fixed[8][size - 8 + i] = true; // horizontal: bits 7..0
        }

        // --- try masks, pick lowest penalty ---
        boolean[][] best = null;
        int bestMask = 0, bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] trial = copy(modules);
            placeData(trial, fixed, finalCw, mask, size);
            int penalty = penalty(trial, size);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = trial;
                bestMask = mask;
            }
        }
        drawFormat(best, fixed, bestMask, size);
        return best;
    }

    private static void placeFinder(boolean[][] m, boolean[][] f, int x, int y) {
        for (int dy = -1; dy <= 7; dy++) {
            for (int dx = -1; dx <= 7; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= m.length || yy >= m.length) {
                    continue;
                }
                boolean dark;
                if (dx == -1 || dx == 7 || dy == -1 || dy == 7) {
                    dark = false; // separator
                } else if (dx == 0 || dx == 6 || dy == 0 || dy == 6) {
                    dark = true;
                } else if (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4) {
                    dark = true;
                } else {
                    dark = false;
                }
                m[yy][xx] = dark;
                f[yy][xx] = true;
            }
        }
    }

    private static void placeAlignment(boolean[][] m, boolean[][] f, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int a = Math.max(Math.abs(dx), Math.abs(dy));
                m[cy + dy][cx + dx] = (a != 1);
                f[cy + dy][cx + dx] = true;
            }
        }
    }

    private static void placeData(boolean[][] m, boolean[][] f, byte[] cw, int mask, int size) {
        int bit = 0;
        int totalBits = cw.length * 8;
        for (int x = size - 1; x > 0; x -= 2) {
            if (x == 6) {
                x = 5; // skip vertical timing column
            }
            for (int y = 0; y < size; y++) {
                for (int dx = 0; dx < 2; dx++) {
                    int xx = x - dx;
                    int yy = ((x + 1) & 2) == 0 ? size - 1 - y : y; // upward/downward snake
                    if (f[yy][xx]) {
                        continue;
                    }
                    boolean dark = false;
                    if (bit < totalBits) {
                        dark = ((cw[bit / 8] >> (7 - (bit % 8))) & 1) == 1;
                        bit++;
                    }
                    if (maskBit(mask, xx, yy)) {
                        dark = !dark;
                    }
                    m[yy][xx] = dark;
                }
            }
        }
    }

    private static boolean maskBit(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (x / 3 + y / 2) % 2 == 0;
            case 5: return (x * y) % 2 + (x * y) % 3 == 0;
            case 6: return ((x * y) % 2 + (x * y) % 3) % 2 == 0;
            default: return ((x + y) % 2 + (x * y) % 3) % 2 == 0;
        }
    }

    /** Format info: 15 bits for EC level M (01) + mask, with BCH + XOR mask. */
    private static void drawFormat(boolean[][] m, boolean[][] f, int mask, int size) {
        int data = (0b00 << 3) | mask; // EC level M = 00
        int bits = data << 10;
        int g = 0x537;
        for (int i = 14; i >= 10; i--) {
            if (((bits >> i) & 1) == 1) {
                bits ^= g << (i - 10);
            }
        }
        int format = ((data << 10) | (bits & 0x3FF)) ^ 0x5412;
        // top-left copy, MSB first: (8,0)..(8,5),(8,7),(8,8),(7,8),(5,8)..(0,8)
        int[][] pos = {
                {8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {8, 7},
                {8, 8}, {7, 8}, {5, 8}, {4, 8}, {3, 8}, {2, 8}, {1, 8}, {0, 8},
        };
        for (int j = 0; j < 15; j++) {
            m[pos[j][0]][pos[j][1]] = ((format >> (14 - j)) & 1) == 1;
        }
        // split copies: bits 14..8 vertical, bits 7..0 horizontal
        for (int j = 0; j < 7; j++) {
            m[size - 1 - j][8] = ((format >> (14 - j)) & 1) == 1;
        }
        for (int j = 7; j < 15; j++) {
            m[8][size - 15 + j] = ((format >> (14 - j)) & 1) == 1;
        }
    }

    // ---- penalty rules (mask selection) ----

    private static int penalty(boolean[][] m, int size) {
        int p = 0;
        // rule 1: runs of 5+ same color
        for (int y = 0; y < size; y++) {
            p += runPenalty(m[y]);
        }
        for (int x = 0; x < size; x++) {
            boolean[] col = new boolean[size];
            for (int y = 0; y < size; y++) {
                col[y] = m[y][x];
            }
            p += runPenalty(col);
        }
        // rule 2: 2x2 blocks
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = m[y][x];
                if (m[y][x + 1] == c && m[y + 1][x] == c && m[y + 1][x + 1] == c) {
                    p += 3;
                }
            }
        }
        // rule 3: finder-like patterns 10111010000 / 00001011101
        boolean[] pat1 = {true, false, true, true, true, false, true, false, false, false, false};
        boolean[] pat2 = {false, false, false, false, true, false, true, true, true, false, true};
        for (int y = 0; y < size; y++) {
            for (int x = 0; x <= size - 11; x++) {
                if (matches(m, y, x, true, pat1) || matches(m, y, x, true, pat2)) {
                    p += 40;
                }
            }
        }
        for (int x = 0; x < size; x++) {
            for (int y = 0; y <= size - 11; y++) {
                if (matches(m, y, x, false, pat1) || matches(m, y, x, false, pat2)) {
                    p += 40;
                }
            }
        }
        // rule 4: dark ratio
        int dark = 0;
        for (boolean[] row : m) {
            for (boolean b : row) {
                if (b) {
                    dark++;
                }
            }
        }
        int percent = dark * 100 / (size * size);
        p += Math.abs(percent - 50) / 5 * 10;
        return p;
    }

    private static int runPenalty(boolean[] line) {
        int p = 0, run = 1;
        for (int i = 1; i <= line.length; i++) {
            if (i < line.length && line[i] == line[i - 1]) {
                run++;
            } else {
                if (run >= 5) {
                    p += 3 + (run - 5);
                }
                run = 1;
            }
        }
        return p;
    }

    private static boolean matches(boolean[][] m, int y, int x, boolean horiz, boolean[] pat) {
        for (int i = 0; i < pat.length; i++) {
            boolean b = horiz ? m[y][x + i] : m[y + i][x];
            if (b != pat[i]) {
                return false;
            }
        }
        return true;
    }

    // ---- Reed-Solomon over GF(256) ----

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if (x >= 256) {
                x ^= 0x11D;
            }
        }
        for (int i = 255; i < 512; i++) {
            EXP[i] = EXP[i - 255];
        }
    }

    private static int gfMul(int a, int b) {
        return (a == 0 || b == 0) ? 0 : EXP[LOG[a] + LOG[b]];
    }

    private static byte[] reedSolomon(byte[] data, int ecCount) {
        int[] gen = {1};
        for (int i = 0; i < ecCount; i++) {
            int[] next = new int[gen.length + 1];
            for (int j = 0; j < gen.length; j++) {
                next[j] ^= gfMul(gen[j], EXP[i]);
                next[j + 1] ^= gen[j];
            }
            gen = next;
        }
        // gen is constant-first: gen[0]=g0 .. gen[ecCount]=1. Direct long division
        // of (data * x^ecCount) by g(x); the remainder is the EC codewords.
        int[] msg = new int[data.length + ecCount];
        for (int i = 0; i < data.length; i++) {
            msg[i] = data[i] & 0xFF;
        }
        for (int i = 0; i < data.length; i++) {
            int coef = msg[i];
            if (coef != 0) {
                for (int j = 0; j <= ecCount; j++) {
                    msg[i + j] ^= gfMul(gen[ecCount - j], coef);
                }
            }
        }
        byte[] ec = new byte[ecCount];
        for (int i = 0; i < ecCount; i++) {
            ec[i] = (byte) msg[data.length + i];
        }
        return ec;
    }

    private static boolean[][] copy(boolean[][] m) {
        boolean[][] c = new boolean[m.length][];
        for (int i = 0; i < m.length; i++) {
            c[i] = m[i].clone();
        }
        return c;
    }

    private static final class BitBuffer {
        private final java.util.ArrayList<Byte> bytes = new java.util.ArrayList<>();
        private int bitLen = 0;

        void append(int val, int n) {
            for (int i = n - 1; i >= 0; i--) {
                int b = (val >> i) & 1;
                int idx = bitLen / 8;
                while (bytes.size() <= idx) {
                    bytes.add((byte) 0);
                }
                if (b == 1) {
                    bytes.set(idx, (byte) (bytes.get(idx) | (1 << (7 - (bitLen % 8)))));
                }
                bitLen++;
            }
        }

        int size() {
            return bitLen;
        }

        byte[] toBytes() {
            byte[] out = new byte[bytes.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = bytes.get(i);
            }
            return out;
        }
    }
}
