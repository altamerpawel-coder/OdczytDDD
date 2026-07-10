package pl.altamer.odczytddd.tacho;

import java.util.Arrays;

public final class ApduResponse {
    private final byte[] data;
    private final int sw1;
    private final int sw2;

    public ApduResponse(byte[] raw) {
        if (raw == null || raw.length < 2) {
            throw new IllegalArgumentException("Odpowiedź APDU jest za krótka");
        }
        this.data = Arrays.copyOf(raw, raw.length - 2);
        this.sw1 = raw[raw.length - 2] & 0xFF;
        this.sw2 = raw[raw.length - 1] & 0xFF;
    }

    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    public int sw1() {
        return sw1;
    }

    public int sw2() {
        return sw2;
    }

    public int statusWord() {
        return (sw1 << 8) | sw2;
    }

    public boolean isSuccess() {
        return statusWord() == 0x9000;
    }

    public boolean isFileNotFound() {
        int sw = statusWord();
        return sw == 0x6A82 || sw == 0x6A86;
    }

    public String statusHex() {
        return String.format("%02X%02X", sw1, sw2);
    }
}
