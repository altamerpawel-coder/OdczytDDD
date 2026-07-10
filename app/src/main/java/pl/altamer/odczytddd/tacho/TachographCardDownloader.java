package pl.altamer.odczytddd.tacho;

import pl.altamer.odczytddd.usb.CcidSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Wersja DIAGNOSTYCZNA downloadera karty tachografu Gen1/Gen2.
 *
 * Do zwykłej wersji dodano:
 *  - pełny log wymiany APDU (każda komenda + odpowiedź karty ze statusem SW),
 *  - sondę Gen2: gdy PSO: Compute Digital Signature zwróci błąd (np. 6700),
 *    aplikacja próbuje kilku wariantów sekwencji (MSE: SET DST, PSO bez Le itd.)
 *    i loguje odpowiedź karty na każdy z nich — to pokazuje, czego karta oczekuje.
 *
 * Log trafia:
 *  - do komunikatu wyjątku (widoczny na czerwonym ekranie błędu — zrób zrzut),
 *  - do pola statycznego {@link #LAST_APDU_LOG} (opcjonalnie do zapisania do pliku).
 *
 * Klasa jest samowystarczalna i ma to samo publiczne API co wersja produkcyjna,
 * więc można ją podmienić 1:1 w repozytorium i zbudować przez GitHub Actions.
 */
public final class TachographCardDownloader {

    /** Pełny log ostatniego odczytu (ustawiany zawsze — także po sukcesie). */
    public static volatile String LAST_APDU_LOG = "";

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
    private final StringBuilder log = new StringBuilder();
    private int apduCount;
    private boolean readProbed;
    private int currentFid;

    public TachographCardDownloader(CcidSession session) {
        this.session = session;
    }

    public DownloadResult download() throws IOException {
        log.setLength(0);
        apduCount = 0;
        logLine("=== ODCZYT KARTY — LOG APDU ===");
        try {
            byte[] atr = session.powerOn();
            logLine("ATR: " + hex(atr) + " (" + atr.length + " B)");
            if (atr.length == 0) {
                throw new IOException("Karta nie odpowiedziała po włączeniu");
            }

            DddWriter writer = new DddWriter();
            List<AppGeneration> downloadedApps = new ArrayList<>();

            appendUnsignedIfPresent(writer, FID_ICC, false);
            appendUnsignedIfPresent(writer, FID_IC, false);

            logLine("-- SELECT AID Gen1 --");
            if (selectAid(AID_GEN1)) {
                downloadApplication(writer, AppGeneration.GEN1, GEN1_FILES);
                downloadedApps.add(AppGeneration.GEN1);
            } else {
                logLine("   Gen1 niedostępna");
            }

            logLine("-- SELECT AID Gen2 --");
            if (selectAid(AID_GEN2)) {
                downloadApplication(writer, AppGeneration.GEN2, GEN2_FILES);
                downloadedApps.add(AppGeneration.GEN2);
            } else {
                logLine("   Gen2 niedostępna");
            }

            if (downloadedApps.isEmpty()) {
                throw new IOException("Nie rozpoznano aplikacji karty tachografowej Gen1 ani Gen2");
            }
            if (writer.blockCount() < 4) {
                throw new IOException("Odczyt karty jest niekompletny");
            }

            byte[] ddd = writer.toByteArray();
            LAST_APDU_LOG = log.toString();
            return new DownloadResult(ddd, downloadedApps, atr);
        } catch (IOException e) {
            // Dołącz log APDU do komunikatu, żeby był widoczny na ekranie błędu.
            LAST_APDU_LOG = log.toString();
            String trace = tail(log.toString(), 3500);
            throw new IOException(safe(e.getMessage()) + "\n\n=== LOG APDU (ostatnie kroki) ===\n" + trace, e);
        }
    }

    private void downloadApplication(DddWriter writer, AppGeneration generation, FileSpec[] files)
            throws IOException {
        for (FileSpec spec : files) {
            logLine("SELECT " + hexFid(spec.fid) + (spec.signed ? " [podpisany]" : " [dane]"));
            if (!selectFile(spec.fid)) {
                logLine("   brak pliku " + hexFid(spec.fid));
                if (spec.required) {
                    throw new IOException("Brak wymaganego pliku karty " + hexFid(spec.fid)
                            + " w " + generation.label);
                }
                continue;
            }

            if (spec.signed) {
                int hashAlgorithm = performHashWithFallback(generation);
                byte[] data = readSelectedFile();
                logLine("   READ " + hexFid(spec.fid) + " -> " + data.length + " B");
                if (data.length == 0 && spec.required) {
                    throw new IOException("Pusty wymagany plik " + hexFid(spec.fid));
                }
                byte[] signature = computeDigitalSignature(generation, hashAlgorithm, spec.fid);
                writer.append(spec.fid, DddWriter.TYPE_DATA, data);
                writer.append(spec.fid, DddWriter.TYPE_SIGNATURE, signature);
            } else {
                byte[] data = readSelectedFile();
                logLine("   READ " + hexFid(spec.fid) + " -> " + data.length + " B");
                writer.append(spec.fid, DddWriter.TYPE_DATA, data);
            }
        }
    }

    private void appendUnsignedIfPresent(DddWriter writer, int fid, boolean required) throws IOException {
        logLine("SELECT " + hexFid(fid) + " [MF/dane]");
        if (!selectFile(fid)) {
            logLine("   brak pliku " + hexFid(fid));
            if (required) {
                throw new IOException("Brak wymaganego pliku " + hexFid(fid));
            }
            return;
        }
        byte[] data = readSelectedFile();
        logLine("   READ " + hexFid(fid) + " -> " + data.length + " B");
        writer.append(fid, DddWriter.TYPE_DATA, data);
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
        currentFid = fid;
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
        final int preferredChunk = 0xFE;

        while (offset <= 0x7FFF) {
            ApduResponse response = readBinary(offset, preferredChunk);

            if (response.sw1() == 0x6C) {
                int exact = response.sw2() == 0 ? 256 : response.sw2();
                response = readBinary(offset, exact);
            }

            if (response.isSuccess()) {
                byte[] data = response.data();
                out.write(data, 0, data.length);
                offset += data.length;
                if (data.length < preferredChunk) {
                    break;
                }
                continue;
            }

            if (response.statusWord() == 0x6282) {
                byte[] data = response.data();
                out.write(data, 0, data.length);
                break;
            }

            if ((response.statusWord() == 0x6B00 || response.statusWord() == 0x6A86)
                    && offset > 0) {
                break;
            }

            if (offset == 0 && !readProbed) {
                readProbed = true;
                probeReadBinary();
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
        return transmitQuiet(apdu); // READ BINARY nie zaśmieca logu (dużo chunków)
    }

    /**
     * Sonda: gdy READ BINARY padnie na pierwszym bajcie (np. 6700), sprawdź
     * jaką długość odczytu akceptuje karta oraz jaki jest realny rozmiar pliku.
     * Wynik w logu pozwoli ustalić poprawny sposób odczytu.
     */
    private void probeReadBinary() {
        logLine("   === SONDA READ BINARY (plik " + hexFid(currentFid) + ") ===");
        int[] les = {0x00, 0x01, 0x04, 0x08, 0x10, 0x18, 0x1A, 0x20, 0x40, 0x80};
        for (int le : les) {
            try {
                logLine("   [read Le=" + le + " (0x" + String.format("%02X", le) + ")]");
                ApduResponse r = transmit(new byte[]{0x00, (byte) 0xB0, 0x00, 0x00, (byte) le});
                if (r.isSuccess()) {
                    logLine("   >>> DZIALA Le=" + le + " -> odczytano " + r.data().length + " B <<<");
                }
            } catch (Exception e) {
                logLine("   [read Le=" + le + "] blad: " + safe(e.getMessage()));
            }
        }
        // Zapytaj kartę o rozmiar/strukturę pliku (FCP / FCI).
        try {
            logLine("   [SELECT P2=04 -> FCP (rozmiar pliku)]");
            transmit(new byte[]{0x00, (byte) 0xA4, 0x02, 0x04, 0x02,
                    (byte) (currentFid >>> 8), (byte) currentFid});
        } catch (Exception e) {
            logLine("   [SELECT P2=04] blad: " + safe(e.getMessage()));
        }
        try {
            logLine("   [SELECT P2=00 -> FCI]");
            transmit(new byte[]{0x00, (byte) 0xA4, 0x02, 0x00, 0x02,
                    (byte) (currentFid >>> 8), (byte) currentFid});
        } catch (Exception e) {
            logLine("   [SELECT P2=00] blad: " + safe(e.getMessage()));
        }
        logLine("   === koniec sondy READ ===");
    }

    private int performHashWithFallback(AppGeneration generation) throws IOException {
        int[] candidates = generation == AppGeneration.GEN1
                ? new int[]{0x00}
                : new int[]{0x01, 0x02, 0x03, 0x00};

        IOException last = null;
        for (int algorithm : candidates) {
            logLine("   PERFORM HASH (P2=" + algorithm + ")");
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
        logLine("   PSO: COMPUTE DIGITAL SIGNATURE (Le=00)");
        ApduResponse response = transmit(new byte[]{0x00, 0x2A, (byte) 0x9E, (byte) 0x9A, 0x00});
        if (response.sw1() == 0x6C) {
            int le = response.sw2();
            logLine("   -> 6C" + String.format("%02X", le) + ", ponawiam z Le=" + le);
            response = transmit(new byte[]{0x00, 0x2A, (byte) 0x9E, (byte) 0x9A, (byte) le});
        }
        if (!response.isSuccess()) {
            // Sonda: sprawdź co odblokowuje podpis na tej karcie Gen2.
            if (generation == AppGeneration.GEN2) {
                probeGen2Signature();
            }
            throw new IOException("Błąd podpisu cyfrowego (" + generation.label
                    + ", plik " + hexFid(fid) + ", hash=" + hashAlgorithm
                    + "): " + response.statusHex());
        }
        byte[] signature = response.data();
        if (signature.length == 0) {
            throw new IOException("Karta zwróciła pusty podpis cyfrowy");
        }
        return signature;
    }

    /**
     * Próbuje po kolei kilku sekwencji, które MOGĄ być wymagane przez kartę Gen2
     * przed policzeniem podpisu. Loguje odpowiedź karty na każdą — dzięki temu widać,
     * którą sekwencję karta akceptuje (SW=9000 + dane = znaleziona poprawka).
     */
    private void probeGen2Signature() {
        logLine("   === SONDA Gen2: szukam poprawnej sekwencji podpisu ===");
        byte[][] probes = new byte[][]{
                // PSO bez pola Le (case 1) — karta może odpowiedzieć 61xx (dane dostępne).
                hex("002A9E9A"),
                // MSE: SET DST — sam klucz (tag 84): 00 22 41 B6 03 84 01 01
                hex("002241B603840101"),
                // MSE: SET DST — sam algorytm (tag 80): 00 22 41 B6 03 80 01 01
                hex("002241B603800101"),
                // MSE: SET DST — algorytm + klucz: 00 22 41 B6 06 80 01 01 84 01 01
                hex("002241B606800101840101"),
                // MSE: SET DST z referencją klucza podpisu karty = 0x00: 00 22 41 B6 03 84 01 00
                hex("002241B603840100")
        };
        String[] labels = {
                "PSO bez Le (case 1)",
                "MSE:SET DST key=01, potem PSO",
                "MSE:SET DST algo=01, potem PSO",
                "MSE:SET DST algo=01+key=01, potem PSO",
                "MSE:SET DST key=00, potem PSO"
        };
        for (int i = 0; i < probes.length; i++) {
            try {
                logLine("   [sonda] " + labels[i]);
                ApduResponse r1 = transmit(probes[i]);
                // Jeśli to była komenda MSE (INS 0x22), po niej spróbuj PSO.
                if (probes[i].length >= 2 && (probes[i][1] & 0xFF) == 0x22 && r1.isSuccess()) {
                    ApduResponse r2 = transmit(new byte[]{0x00, 0x2A, (byte) 0x9E, (byte) 0x9A, 0x00});
                    if (r2.sw1() == 0x6C) {
                        r2 = transmit(new byte[]{0x00, 0x2A, (byte) 0x9E, (byte) 0x9A, (byte) r2.sw2()});
                    }
                    if (r2.isSuccess()) {
                        logLine("   >>> SUKCES tą sekwencją! podpis=" + r2.data().length + " B <<<");
                    }
                } else if (r1.sw1() == 0x61) {
                    logLine("   (karta zgłasza 61" + String.format("%02X", r1.sw2()) + " — dane dostępne)");
                }
            } catch (Exception e) {
                logLine("   [sonda] błąd: " + safe(e.getMessage()));
            }
        }
        logLine("   === koniec sondy ===");
    }

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
        return transmit(apdu, true);
    }

    private ApduResponse transmitQuiet(byte[] apdu) throws IOException {
        return transmit(apdu, false);
    }

    private ApduResponse transmit(byte[] apdu, boolean verbose) throws IOException {
        apduCount++;
        if (verbose) {
            logLine("> " + hex(apdu));
        }
        ApduResponse first = new ApduResponse(session.transmitApdu(apdu));
        if (first.sw1() != 0x61) {
            if (verbose) {
                logResponse(first);
            }
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
        ApduResponse joined = new ApduResponse(raw);
        if (verbose) {
            logResponse(joined);
        }
        return joined;
    }

    private void logResponse(ApduResponse r) {
        byte[] d = r.data();
        String dataHex = d.length == 0 ? "" : (" data[" + d.length + "]=" + hex(preview(d, 24)));
        logLine("< SW=" + r.statusHex() + dataHex);
    }

    private void logLine(String s) {
        log.append(s).append('\n');
    }

    private static byte[] preview(byte[] b, int max) {
        return b.length <= max ? b : Arrays.copyOf(b, max);
    }

    private static String tail(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return "…(początek logu pominięty)…\n" + s.substring(s.length() - max);
    }

    private static String safe(String s) {
        return s == null ? "(bez opisu)" : s;
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

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
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
