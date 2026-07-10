package pl.altamer.odczytddd.tacho;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Zapisuje obiekty TLV pliku pobrania karty tachografowej:
 * FID(2) + typ(1: 00=dane, 01=podpis) + długość(2) + wartość.
 */
public final class DddWriter {
    public static final int TYPE_DATA = 0x00;
    public static final int TYPE_SIGNATURE = 0x01;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int blockCount;

    public void append(int fid, int type, byte[] value) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("Brak danych bloku DDD");
        }
        if (value.length > 0xFFFF) {
            throw new IOException("Blok DDD jest większy niż 65535 bajtów: " + hexFid(fid));
        }
        out.write((fid >>> 8) & 0xFF);
        out.write(fid & 0xFF);
        out.write(type & 0xFF);
        out.write((value.length >>> 8) & 0xFF);
        out.write(value.length & 0xFF);
        out.write(value);
        blockCount++;
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }

    public int blockCount() {
        return blockCount;
    }

    private static String hexFid(int fid) {
        return String.format("%04X", fid & 0xFFFF);
    }
}
