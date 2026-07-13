package pl.altamer.odczytddd.usb;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

/**
 * Minimalna sesja USB CCID: zasilenie karty i wymiana APDU przez XfrBlock.
 * Bez bibliotek zewnętrznych.
 */
public final class CcidSession implements Closeable {
    private static final int PC_TO_RDR_ICC_POWER_ON = 0x62;
    private static final int PC_TO_RDR_XFR_BLOCK = 0x6F;
    private static final int RDR_TO_PC_DATA_BLOCK = 0x80;
    private static final int CCID_HEADER_SIZE = 10;
    private static final int TIMEOUT_MS = 15_000;

    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint endpointOut;
    private final UsbEndpoint endpointIn;
    private int sequence = 0;
    private boolean closed;

    CcidSession(
            UsbDeviceConnection connection,
            UsbInterface usbInterface,
            UsbEndpoint endpointOut,
            UsbEndpoint endpointIn
    ) {
        this.connection = connection;
        this.usbInterface = usbInterface;
        this.endpointOut = endpointOut;
        this.endpointIn = endpointIn;
    }

    public byte[] powerOn() throws IOException {
        return exchange(PC_TO_RDR_ICC_POWER_ON, new byte[0], 0, 0, 0);
    }

    public byte[] transmitApdu(byte[] apdu) throws IOException {
        if (apdu == null || apdu.length == 0) {
            throw new IllegalArgumentException("Puste APDU");
        }
        return exchange(PC_TO_RDR_XFR_BLOCK, apdu, 0, 0, 0);
    }

    private byte[] exchange(int messageType, byte[] payload, int specific0, int specific1, int specific2)
            throws IOException {
        ensureOpen();
        int seq = sequence++ & 0xFF;
        byte[] command = new byte[CCID_HEADER_SIZE + payload.length];
        command[0] = (byte) messageType;
        putLe32(command, 1, payload.length);
        command[5] = 0; // slot 0
        command[6] = (byte) seq;
        command[7] = (byte) specific0;
        command[8] = (byte) specific1;
        command[9] = (byte) specific2;
        System.arraycopy(payload, 0, command, CCID_HEADER_SIZE, payload.length);

        int sent = connection.bulkTransfer(endpointOut, command, command.length, TIMEOUT_MS);
        if (sent != command.length) {
            throw new IOException("Nie udało się wysłać polecenia do czytnika USB (" + sent + "/" + command.length + ")");
        }

        byte[] frame = readFrame();
        if ((frame[0] & 0xFF) != RDR_TO_PC_DATA_BLOCK) {
            throw new IOException(String.format("Nieoczekiwana odpowiedź CCID: 0x%02X", frame[0] & 0xFF));
        }
        if ((frame[6] & 0xFF) != seq) {
            throw new IOException("Niezgodny numer sekwencji odpowiedzi czytnika");
        }

        int status = frame[7] & 0xFF;

        // bmCommandStatus (bity 7:6 bajtu status): 0 = OK, 1 = błąd (bError),
        // 2 (0x80) = karta prosi o więcej czasu (WTX — np. liczenie hasha dużego
        // pliku aktywności kierowcy). Wtedy czekamy na kolejną ramkę zamiast
        // przerywać. Poprzednio każdy status != 0 był traktowany jako błąd.
        int guard = 0;
        while ((status & 0xC0) == 0x80) {
            if (++guard > 120) {
                throw new IOException("Czytnik zbyt długo przetwarza polecenie karty");
            }
            frame = readFrame();
            if ((frame[0] & 0xFF) != RDR_TO_PC_DATA_BLOCK) {
                throw new IOException(String.format("Nieoczekiwana odpowiedź CCID: 0x%02X", frame[0] & 0xFF));
            }
            if ((frame[6] & 0xFF) != seq) {
                throw new IOException("Niezgodny numer sekwencji odpowiedzi czytnika");
            }
            status = frame[7] & 0xFF;
        }

        int commandStatus = status & 0xC0;
        int iccStatus = status & 0x03;
        if (commandStatus != 0) {
            int error = frame[8] & 0xFF;
            throw new IOException(String.format("Błąd czytnika CCID (status=%02X, error=%02X)", status, error));
        }
        if (iccStatus == 2) {
            throw new IOException("Brak karty w czytniku");
        }

        int length = getLe32(frame, 1);
        if (length < 0 || CCID_HEADER_SIZE + length > frame.length) {
            throw new IOException("Nieprawidłowa długość odpowiedzi CCID");
        }
        return Arrays.copyOfRange(frame, CCID_HEADER_SIZE, CCID_HEADER_SIZE + length);
    }

    private byte[] readFrame() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int expected = -1;
        byte[] buffer = new byte[Math.max(512, endpointIn.getMaxPacketSize() * 8)];

        for (int tries = 0; tries < 32; tries++) {
            int read = connection.bulkTransfer(endpointIn, buffer, buffer.length, TIMEOUT_MS);
            if (read <= 0) {
                throw new IOException("Brak odpowiedzi z czytnika USB");
            }
            out.write(buffer, 0, read);
            byte[] current = out.toByteArray();
            if (expected < 0 && current.length >= CCID_HEADER_SIZE) {
                int payloadLength = getLe32(current, 1);
                if (payloadLength < 0 || payloadLength > 1_000_000) {
                    throw new IOException("Nieprawidłowa długość ramki CCID");
                }
                expected = CCID_HEADER_SIZE + payloadLength;
            }
            if (expected >= 0 && current.length >= expected) {
                return Arrays.copyOf(current, expected);
            }
        }
        throw new IOException("Odpowiedź czytnika USB jest niekompletna");
    }

    private static void putLe32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static int getLe32(byte[] source, int offset) {
        return (source[offset] & 0xFF)
                | ((source[offset + 1] & 0xFF) << 8)
                | ((source[offset + 2] & 0xFF) << 16)
                | ((source[offset + 3] & 0xFF) << 24);
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Sesja czytnika jest zamknięta");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.releaseInterface(usbInterface);
        } catch (Exception ignored) {
        }
        connection.close();
    }
}
