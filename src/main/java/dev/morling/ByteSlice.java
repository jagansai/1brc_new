package dev.morling;

import java.nio.charset.StandardCharsets;

public class ByteSlice {
    // small owned byte array containing the bytes for the key (no ref to
        // the larger block buffer)
        final byte[] data;
        final int len;
        final int hash;

        public ByteSlice(byte[] src, int off, int len) {
            this.data = new byte[len];
            System.arraycopy(src, off, this.data, 0, len);
            this.len = len;
            int h = 1;
            for (int i = 0; i < len; i++) {
                h = 31 * h + (this.data[i] & 0xff);
            }
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof ByteSlice))
                return false;
            ByteSlice b = (ByteSlice) o;
            if (this.len != b.len)
                return false;
            for (int i = 0; i < len; i++) {
                if (this.data[i] != b.data[i])
                    return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return new String(data, 0, len, StandardCharsets.UTF_8);
        }
}
