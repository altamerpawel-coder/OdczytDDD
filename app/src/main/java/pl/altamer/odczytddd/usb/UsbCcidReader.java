package pl.altamer.odczytddd.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Collection;

/** Wyszukuje standardowy czytnik kart klasy USB Smart Card / CCID (0x0B). */
public final class UsbCcidReader {
    private static final int USB_CLASS_SMART_CARD = 0x0B;

    private UsbCcidReader() {
    }

    public static UsbDevice findCompatibleDevice(UsbManager manager) {
        Collection<UsbDevice> devices = manager.getDeviceList().values();
        for (UsbDevice device : devices) {
            if (findCcidInterface(device) != null) {
                return device;
            }
        }
        return null;
    }

    public static CcidSession open(UsbManager manager, UsbDevice device) throws IOException {
        UsbInterface intf = findCcidInterface(device);
        if (intf == null) {
            throw new IOException("Podłączone urządzenie nie jest czytnikiem kart CCID");
        }

        UsbEndpoint endpointOut = null;
        UsbEndpoint endpointIn = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint endpoint = intf.getEndpoint(i);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }
            if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                endpointOut = endpoint;
            } else if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                endpointIn = endpoint;
            }
        }
        if (endpointOut == null || endpointIn == null) {
            throw new IOException("Czytnik nie udostępnia wymaganych złączy USB Bulk");
        }

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            throw new IOException("Nie udało się otworzyć czytnika. Sprawdź zgodę USB.");
        }
        if (!connection.claimInterface(intf, true)) {
            connection.close();
            throw new IOException("Nie udało się przejąć interfejsu czytnika USB");
        }
        return new CcidSession(connection, intf, endpointOut, endpointIn);
    }

    private static UsbInterface findCcidInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == USB_CLASS_SMART_CARD) {
                return intf;
            }
        }
        return null;
    }
}
