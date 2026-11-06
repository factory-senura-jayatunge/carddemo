package org.carddemo.identity.user;

import java.nio.charset.Charset;
import java.util.Arrays;

/** The user ID as EBCDIC bytes, so the user list can keep the VSAM key order (NUM-USRL-002). */
public final class EbcdicSortKey {

    private static final Charset CP037 = Charset.forName("IBM037");

    private EbcdicSortKey() {
    }

    public static byte[] of(String userId) {
        byte[] key = new byte[8];
        Arrays.fill(key, (byte) 0x40);
        byte[] encoded = userId.getBytes(CP037);
        System.arraycopy(encoded, 0, key, 0, Math.min(8, encoded.length));
        return key;
    }
}
