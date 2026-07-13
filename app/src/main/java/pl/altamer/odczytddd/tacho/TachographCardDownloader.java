package pl.altamer.odczytddd.tacho;

import pl.altamer.odczytddd.usb.CcidSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Downloader karty kierowcy tachografu (Gen1 + Gen2).
 * Tworzy plik DDD jako ciąg obiektów TLV i po złożeniu pliku próbuje zapisać
 * czas ostatniego pobrania w EF Card_Download (050E).
 *
 * Uwzględnia specyfikę restrykcyjnych kart:
 *  - READ BINARY z Le=0x00 (karta zwraca 6Cxx z dokładną długością, potem odczyt dokładny),
 *  - PSO: Compute Digital Signature z dokładną długością podpisu (Gen1 RSA 128 B / Gen2 ECC 64 B),
 *  - obsługę WTX (prośby czytnika o więcej czasu) realizuje warstwa {@link CcidSession}.
 */
public final class TachographCardDownloader {
    private static final byte[] AID_GEN1 = hex("FF544143484F");
    private static final byte[] AID_GEN2 = hex("FF534D524454");

    private static final int FID_ICC = 0x0002;
    private static final int FID_IC = 0x0005;
    private static final int FID_CARD_DOWNLOAD = 0x050E;

    private static final FileSpec[] GEN1_FILES = new FileSpec[]{
            unsigned(0xC100, true),
            unsigned(0xC108, true),
            signed(0x0501, true),
            signed(0x0520, true),
            signed(0x0521, false),
            signed(0x0502, true),
            signed(0x0503, true),
            signed(0x0504, true),
            signed(0x0505, true),
            signed(0x0506, true),
            signed(0x0507, true),
            signed(0x0508, true),
            signed(0x0522, false)
    };

    private static final FileSpec[] GEN2_FILES = new FileSpec[]{
            unsigned(0xC100, true),
            unsigned(0xC101, true),
            unsigned(0xC108, true),
            unsigned(0xC109, false),
            signed(0x0501, true),
            signed(0x0520, true),
            signed(0x0521, false),
            signed(0x0502, true),
            signed(0x0503, true),
            signed(0x0504, true),
            signed(0x0505, true),
            signed(0x0506, true),
            signed(0x0507, true),
            signed(0x0508, true),
            signed(0x0522, false),
            signed(0x0523, false),
            signed(0x0524, false)
    };

    private final CcidSession session;

    public TachographCardDownloader(CcidSession session) {
        this.session = session;
    }

    public DownloadResult download() throws IOException {
        byte[] atr = session.powerOn();
        if (atr.length == 0) {
            throw new IOException("Karta nie odpowiedziała po włączeniu");
        }

        DddWriter writer = new DddWriter();
        List<AppGeneration> downloadedApps = new ArrayList<>();

        appendUnsignedIfPresent(writer, FID_ICC, false);
        appendUnsignedIfPresent(writer, FID_IC, false);

        if (selectAid(AID_GEN1)) {
            downloadApplication(writer, AppGeneration.GEN1, GEN1_FILES);
            downloadedApps.add(AppGeneration.GEN1);
        }

        if (selectAid(AID_GEN2)) {
            downloadApplication(writer, AppGeneration.GEN2, GEN2_FILES);
            downloadedApps.add(AppGeneration.GEN2);
        }

        if (downloadedApps.isEmpty()) {
            throw new IOException("Nie rozpoznano aplikacji karty tachografowej Gen1 ani Gen2");
        }
        if (writer.blockCount() < 4) {
            throw new IOException("Odczyt karty jest niekompletny");
        }

        byte[] ddd = writer.toByteArray();
        return new DownloadResult(ddd, downloadedApps, atr);
    }

    private void downloadApplication(DddWriter writer, AppGeneration generation, FileSpec[] files)
            throws IOException {
        for (FileSpec spec : files) {
            if (!selectFile(spec.fid)) {
                if (spec.required) {
                    throw new IOException("Brak wymaganego pliku karty " + hexFid(spec.fid)
                            + " w " + generation.label);
                }
                continue;
            }

            if (spec.signed) {
                int hashAlgorithm = performHashWithFallback(generation);
                byte[] data = readSelectedFile();
                if (data.length == 0 && spec.required) {
                    throw new IOException("Pusty wymagany plik " + hexFid(spec.fid));
                }
                byte[] signature = computeDigitalSignature(generation, hashAlgorithm, spec.fid);
                writer.append(spec.fid, DddWriter.TYPE_DATA, data);
                writer.append(spec.fid, DddWriter.TYPE_SIGNATURE, signature);
            } else {
                byte[] data = readSelectedFile();
                writer.append(spec.fid, DddWriter.TYPE_DATA, data);
            }
        }
    }

    private void appendUnsignedIfPresent(DddWriter writer, int fid, boolean required) throws IOException {
        if (!selectFile(fid)) {
            if (required) {
                throw new IOException("Brak wymaganego pliku " + hexFid(fid));
            }
            return;
        }
        writer.append(fid, DddWriter.TYPE_DATA, readSelectedFile());
    }

    private boolean selectAid(byte[] aid) throws IOException {
        byte[] apdu = new byte[5 + aid.length];
        apdu[0] = 0x00;
        apdu[1] = (byte) 0xA4;
        apdu[2] = 0x04;
        apdu[3] = 0x0C;
        apdu[4] = (byte) aid.length;
        System.arraycopy(aid, 0, apdu, 5, aid.length);
        ApduResponse response = transmit(apdu);
        if (response.isSuccess()) {
            return true;
        }
        if (response.isFileNotFound() || response.statusWord() == 0x6A81) {
            return false;
        }
        throw new IOException("Błąd wyboru aplikacji karty: " + response.statusHex());
    }

    private boolean selectFile(int fid) throws IOException {
        byte[] apdu = new byte[]{
                0x00, (byte) 0xA4, 0x02, 0x0C, 0x02,
                (byte) (fid >>> 8), (byte) fid
        };
        ApduResponse response = transmit(apdu);
        if (response.isSuccess()) {
            return true;
        }
        if (response.isFileNotFound()) {
            return false;
        }
        throw new IOException("Błąd wyboru pliku " + hexFid(fid) + ": " + response.statusHex());
    }

    private byte[] readSelectedFile() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        final int fullChunk = 256; // Le=0x00: karta odpowie 6Cxx z dokładną długością

        while (offset <= 0x7FFF) {
            // Pytamy z Le=0x00. Restrykcyjna karta odpowiada 6Cxx z dokładną
            // liczbą dostępnych bajtów, którą następnie pobieramy precyzyjnie.
            ApduResponse response = readBinary(offset, fullChunk);

            if (response.sw1() == 0x6C) {
                int exact = response.sw2() == 0 ? 256 : response.sw2();
                response = readBinary(offset, exact);
            }

            if (response.isSuccess()) {
                byte[] data = response.data();
                out.write(data, 0, data.length);
                offset += data.length;
                if (data.length < fullChunk) {
                    break; // ostatni (niepełny) fragment pliku
                }
                continue;
            }

            if (response.statusWord() == 0x6282) {
                byte[] data = response.data();
                out.write(data, 0, data.length);
                break;
            }

            // Po odczytaniu części pliku każdy błąd traktujemy jako koniec pliku.
            if (offset > 0) {
                break;
            }

            throw new IOException("Błąd READ BINARY przy pozycji " + offset
                    + ": " + response.statusHex());
        }

        if (offset > 0x7FFF) {
            throw new IOException("Plik karty przekracza zakres obsługiwany przez krótki READ BINARY");
        }
        return out.toByteArray();
    }

    private ApduResponse readBinary(int offset, int length) throws IOException {
        int le = length == 256 ? 0 : length;
        byte[] apdu = new byte[]{
                0x00, (byte) 0xB0,
                (byte) ((offset >>> 8) & 0x7F),
                (byte) offset,
                (byte) le
        };
        return transmit(apdu);
    }

    private int performHashWithFallback(AppGeneration generation) throws IOException {
        int[] candidates = generation == AppGeneration.GEN1
                ? new int[]{0x00}
                : new int[]{0x01, 0x02, 0x03, 0x00};

        IOException last = null;
        for (int algorithm : candidates) {
            ApduResponse response = transmit(new byte[]{
                    (byte) 0x80, 0x2A, (byte) 0x90, (byte) algorithm
            });
            if (response.isSuccess()) {
                return algorithm;
            }
            last = new IOException("PERFORM HASH odrzucone: " + response.statusHex());
            if (response.statusWord() != 0x6A86 && response.statusWord() != 0x6D00
                    && response.statusWord() != 0x6985) {
                break;
            }
        }
        throw last != null ? last : new IOException("Nie udało się uruchomić haszowania pliku");
    }

    private byte[] computeDigitalSignature(AppGeneration generation, int hashAlgorithm, int fid)
            throws IOException {
        // Karta wymaga dokładnej długości Le i nie negocjuje przez 6Cxx dla PSO.
        // Podpis to Gen1 RSA (128 B) lub Gen2 ECC (64/96 B). Próbujemy malejąco —
        // pierwsza długość z 9000 to dokładny rozmiar podpisu (za duże Le => 6700).
        int[] candidateLe = {0x80, 0x60, 0x40}; // 128, 96, 64
        ApduResponse response = null;
        for (int le : candidateLe) {
            response = transmit(new byte[]{0x00, 0x2A, (byte) 0x9E, (byte) 0x9A, (byte) le});
            if (response.sw1() == 0x6C) {
                int exact = response.sw2() == 0 ? 256 : response.sw2();
                response = transmit(new byte[]{0x00, 0x2A, (byte) 0x9E, (byte) 0x9A, (byte) exact});
            }
            if (response.isSuccess() && response.data().length > 0) {
                return response.data();
            }
        }
        String sw = response != null ? response.statusHex() : "----";
        throw new IOException("Błąd podpisu cyfrowego (" + generation.label
                + ", plik " + hexFid(fid) + ", hash=" + hashAlgorithm + "): " + sw);
    }

    /** Wywołaj dopiero po trwałym zapisaniu pliku DDD. */
    public boolean markDownloadCompleted(List<AppGeneration> apps) {
        long epochSeconds = System.currentTimeMillis() / 1000L;
        byte[] time = new byte[]{
                (byte) (epochSeconds >>> 24),
                (byte) (epochSeconds >>> 16),
                (byte) (epochSeconds >>> 8),
                (byte) epochSeconds
        };

        boolean allUpdated = true;
        for (AppGeneration app : apps) {
            try {
                byte[] aid = app == AppGeneration.GEN1 ? AID_GEN1 : AID_GEN2;
                if (!selectAid(aid) || !selectFile(FID_CARD_DOWNLOAD)) {
                    allUpdated = false;
                    continue;
                }
                byte[] apdu = new byte[]{
                        0x00, (byte) 0xD6, 0x00, 0x00, 0x04,
                        time[0], time[1], time[2], time[3]
                };
                ApduResponse response = transmit(apdu);
                if (!response.isSuccess()) {
                    allUpdated = false;
                }
            } catch (Exception ignored) {
                allUpdated = false;
            }
        }
        return allUpdated;
    }

    private ApduResponse transmit(byte[] apdu) throws IOException {
        ApduResponse first = new ApduResponse(session.transmitApdu(apdu));
        if (first.sw1() != 0x61) {
            return first;
        }

        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        byte[] initial = first.data();
        combined.write(initial, 0, initial.length);
        ApduResponse current = first;
        for (int i = 0; i < 8 && current.sw1() == 0x61; i++) {
            int le = current.sw2();
            current = new ApduResponse(session.transmitApdu(
                    new byte[]{0x00, (byte) 0xC0, 0x00, 0x00, (byte) le}
            ));
            byte[] part = current.data();
            combined.write(part, 0, part.length);
        }
        byte[] data = combined.toByteArray();
        byte[] raw = Arrays.copyOf(data, data.length + 2);
        raw[raw.length - 2] = (byte) current.sw1();
        raw[raw.length - 1] = (byte) current.sw2();
        return new ApduResponse(raw);
    }

    private static FileSpec signed(int fid, boolean required) {
        return new FileSpec(fid, true, required);
    }

    private static FileSpec unsigned(int fid, boolean required) {
        return new FileSpec(fid, false, required);
    }

    private static String hexFid(int fid) {
        return String.format("%04X", fid & 0xFFFF);
    }

    private static byte[] hex(String text) {
        int length = text.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(text.substring(i, i + 2), 16);
        }
        return out;
    }

    private static final class FileSpec {
        final int fid;
        final boolean signed;
        final boolean required;

        FileSpec(int fid, boolean signed, boolean required) {
            this.fid = fid;
            this.signed = signed;
            this.required = required;
        }
    }

    public enum AppGeneration {
        GEN1("Gen1"),
        GEN2("Gen2");

        final String label;

        AppGeneration(String label) {
            this.label = label;
        }
    }

    public static final class DownloadResult {
        private final byte[] ddd;
        private final List<AppGeneration> generations;
        private final byte[] atr;

        DownloadResult(byte[] ddd, List<AppGeneration> generations, byte[] atr) {
            this.ddd = Arrays.copyOf(ddd, ddd.length);
            this.generations = new ArrayList<>(generations);
            this.atr = Arrays.copyOf(atr, atr.length);
        }

        public byte[] ddd() {
            return Arrays.copyOf(ddd, ddd.length);
        }

        public List<AppGeneration> generations() {
            return new ArrayList<>(generations);
        }

        public byte[] atr() {
            return Arrays.copyOf(atr, atr.length);
        }
    }
}
