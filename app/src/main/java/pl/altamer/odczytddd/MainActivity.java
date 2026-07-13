package pl.altamer.odczytddd;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pl.altamer.odczytddd.share.ShareFileProvider;
import pl.altamer.odczytddd.tacho.TachographCardDownloader;
import pl.altamer.odczytddd.usb.CcidSession;
import pl.altamer.odczytddd.usb.UsbCcidReader;

public final class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "pl.altamer.odczytddd.USB_PERMISSION";
    private static final String PREFS = "config";
    private static final String KEY_WHATSAPP = "whatsapp_number";

    private static final int BLUE = Color.rgb(23, 74, 126);
    private static final int GREEN = Color.rgb(31, 122, 70);
    private static final int RED = Color.rgb(176, 45, 45);
    private static final int TEXT = Color.rgb(31, 41, 55);

    private UsbManager usbManager;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView statusText;
    private TextView detailText;
    private Button readButton;
    private File latestFile;
    private boolean busy;
    private boolean awaitingReturn;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = getUsbDeviceExtra(intent);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    startRead(device);
                } else {
                    setBusy(false);
                    showError("Brak zgody na czytnik USB", "Naciśnij ODCZYTAJ I WYŚLIJ i zaakceptuj zgodę.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                refreshReaderStatus();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        registerUsbReceiver();
        if (whatsappNumber().isEmpty()) {
            setContentView(buildSetupUi());
        } else {
            setContentView(buildMainUi());
            refreshReaderStatus();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Powrót do aplikacji po otwarciu WhatsAppa — pokaż potwierdzenie.
        if (awaitingReturn && statusText != null) {
            awaitingReturn = false;
            showStatus("GOTOWE ✓", "Plik DDD wysłany.\nMożesz odczytać kolejną kartę.", GREEN);
        }
    }

    private String whatsappNumber() {
        return prefs.getString(KEY_WHATSAPP, "").trim();
    }

    // --- Ekran ustawień (pierwsze uruchomienie / zmiana numeru) ---

    private View buildSetupUi() {
        statusText = null;
        detailText = null;
        readButton = null;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 247, 250));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("USTAW WYSYŁKĘ", 26, Typeface.BOLD, BLUE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(dp(14)));

        TextView info = text(
                "Podaj numer WhatsApp, na który mają trafiać odczytane pliki karty."
                        + "\n\nWpisz z numerem kierunkowym, np. +48 123 456 789.",
                18, Typeface.NORMAL, TEXT);
        info.setPadding(dp(18), dp(18), dp(18), dp(18));
        info.setBackground(rounded(Color.WHITE, dp(16)));
        root.addView(info, matchWrap(dp(18)));

        final EditText input = new EditText(this);
        input.setHint("+48...");
        input.setTextSize(22);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setText(whatsappNumber());
        input.setPadding(dp(16), dp(16), dp(16), dp(16));
        input.setBackground(rounded(Color.WHITE, dp(14)));
        root.addView(input, matchWrap(dp(18)));

        Button save = bigButton("ZAPISZ", GREEN);
        save.setOnClickListener(v -> {
            String number = input.getText().toString().trim();
            String digits = number.replaceAll("[^0-9]", "");
            if (digits.length() < 6) {
                Toast.makeText(this, "Podaj poprawny numer telefonu", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString(KEY_WHATSAPP, number).apply();
            setContentView(buildMainUi());
            refreshReaderStatus();
        });
        root.addView(save, fixedHeight(dp(82), dp(8)));

        return scroll;
    }

    // --- Ekran główny (jeden przycisk: odczytaj i wyślij) ---

    private View buildMainUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 247, 250));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        View leftSpacer = new View(this);
        header.addView(leftSpacer, new LinearLayout.LayoutParams(dp(52), dp(1)));

        TextView title = text("ODCZYT KARTY KIEROWCY", 22, Typeface.BOLD, BLUE);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button menu = new Button(this);
        menu.setText("⋮");
        menu.setTextSize(26);
        menu.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        menu.setTextColor(BLUE);
        menu.setBackground(rounded(Color.WHITE, dp(12)));
        menu.setPadding(0, 0, 0, 0);
        menu.setOnClickListener(this::showOverflowMenu);
        header.addView(menu, new LinearLayout.LayoutParams(dp(52), dp(52)));

        root.addView(header, matchWrap(dp(10)));

        TextView steps = text(
                "1. Podłącz czytnik USB-C\n\n"
                        + "2. Włóż kartę kierowcy\n\n"
                        + "3. Naciśnij ODCZYTAJ I WYŚLIJ",
                20, Typeface.BOLD, TEXT);
        steps.setGravity(Gravity.START);
        steps.setPadding(dp(20), dp(20), dp(20), dp(20));
        steps.setBackground(rounded(Color.WHITE, dp(16)));
        root.addView(steps, matchWrap(dp(14)));

        TextView target = text(
                "WYSYŁANIE DO: WhatsApp\n" + whatsappNumber(),
                18, Typeface.BOLD, GREEN);
        target.setGravity(Gravity.CENTER);
        target.setPadding(dp(14), dp(12), dp(14), dp(12));
        target.setBackground(rounded(Color.rgb(233, 240, 247), dp(14)));
        root.addView(target, matchWrap(dp(6)));

        statusText = text("Sprawdzam czytnik…", 22, Typeface.BOLD, TEXT);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, matchWrap(dp(6)));

        detailText = text("", 17, Typeface.NORMAL, Color.DKGRAY);
        detailText.setGravity(Gravity.CENTER);
        root.addView(detailText, matchWrap(dp(16)));

        readButton = bigButton("ODCZYTAJ I WYŚLIJ", BLUE);
        readButton.setOnClickListener(v -> onReadClicked());
        root.addView(readButton, fixedHeight(dp(88), dp(8)));

        return scroll;
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("Zmień numer WhatsApp");
        popup.setOnMenuItemClickListener(item -> {
            setContentView(buildSetupUi());
            return true;
        });
        popup.show();
    }

    private void onReadClicked() {
        if (busy) {
            return;
        }
        UsbDevice device = UsbCcidReader.findCompatibleDevice(usbManager);
        if (device == null) {
            showError("Nie wykryto czytnika", "Podłącz czytnik kart do USB-C i spróbuj ponownie.");
            return;
        }

        latestFile = null;

        if (!usbManager.hasPermission(device)) {
            setBusy(true);
            showStatus("Potrzebna zgoda USB", "Za chwilę pojawi się okno. Naciśnij OK / Zezwól.", BLUE);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                pendingFlags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    pendingFlags);
            usbManager.requestPermission(device, permissionIntent);
            return;
        }
        startRead(device);
    }

    private void startRead(UsbDevice device) {
        setBusy(true);
        showStatus("Odczytuję kartę…", "Nie wyjmuj karty ani czytnika. Duże pliki chwilę trwają.", BLUE);

        executor.execute(() -> {
            try (CcidSession session = UsbCcidReader.open(usbManager, device)) {
                TachographCardDownloader downloader = new TachographCardDownloader(session);
                TachographCardDownloader.DownloadResult result = downloader.download();
                File file = saveDdd(result.ddd());
                boolean lastDownloadUpdated = downloader.markDownloadCompleted(result.generations());
                runOnUiThread(() -> onReadSuccess(file, result, lastDownloadUpdated));
            } catch (Exception e) {
                String message = friendlyError(e);
                runOnUiThread(() -> {
                    setBusy(false);
                    showError("Nie udało się odczytać karty", message);
                });
            }
        });
    }

    private void onReadSuccess(
            File file,
            TachographCardDownloader.DownloadResult result,
            boolean lastDownloadUpdated) {
        latestFile = file;
        setBusy(false);

        String gen = generationLabel(result.generations());
        if (lastDownloadUpdated) {
            showStatus("GOTOWE ✓", "Karta: " + gen + ". Otwieram WhatsApp…", GREEN);
        } else {
            showStatus("PLIK ZAPISANY ✓",
                    "Karta: " + gen + ".\nUwaga: karta nie potwierdziła zapisu daty odczytu.\nOtwieram WhatsApp…",
                    Color.rgb(176, 110, 20));
        }
        sendToWhatsApp(file);
    }

    private void sendToWhatsApp(File file) {
        awaitingReturn = true; // po powrocie z WhatsAppa pokażemy "GOTOWE"
        Uri uri = ShareFileProvider.uriForFile(getPackageName() + ".files", file);
        Intent base = new Intent(Intent.ACTION_SEND);
        base.setType("application/octet-stream");
        base.putExtra(Intent.EXTRA_STREAM, uri);
        base.putExtra(Intent.EXTRA_SUBJECT, "Plik DDD karty kierowcy");
        base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        String digits = whatsappNumber().replaceAll("[^0-9]", "");
        String[] packages = {"com.whatsapp", "com.whatsapp.w4b"};
        for (String pkg : packages) {
            try {
                Intent wa = new Intent(base);
                wa.setPackage(pkg);
                if (!digits.isEmpty()) {
                    wa.putExtra("jid", digits + "@s.whatsapp.net");
                }
                startActivity(wa);
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        // WhatsApp niedostępny — pokaż systemowe okno udostępniania.
        startActivity(Intent.createChooser(base, "Wyślij plik DDD"));
        Toast.makeText(this, "Nie znaleziono WhatsApp — wybierz aplikację do wysyłki",
                Toast.LENGTH_LONG).show();
    }

    private File saveDdd(byte[] data) throws Exception {
        File dir = new File(getFilesDir(), "ddd");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("Nie można utworzyć katalogu na pliki DDD");
        }
        File file = new File(dir, buildDddFileName(data));
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
            out.flush();
        }
        return file;
    }

    /**
     * Standardowa nazwa pliku pobrania karty kierowcy:
     * C_RRRRMMDD_GGMM_&lt;inicjał imienia&gt;_&lt;Nazwisko&gt;_&lt;numer karty 14 znaków&gt;.DDD
     */
    private String buildDddFileName(byte[] ddd) {
        String dateTime = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        try {
            byte[] id = findDddBlock(ddd, 0x0520);
            if (id != null && id.length >= 137) {
                String cardNumber = new String(id, 1, 16, "ISO-8859-1").trim();
                String surname = new String(id, 66, 35, "ISO-8859-1").trim();
                String firstNames = new String(id, 102, 35, "ISO-8859-1").trim();
                String card14 = cardNumber.length() >= 14 ? cardNumber.substring(0, 14) : cardNumber;
                card14 = card14.replaceAll("[^A-Za-z0-9]", "");
                String sur = surname.replaceAll("[^A-Za-z0-9]", "");
                String ini = firstNames.isEmpty() ? "X"
                        : firstNames.substring(0, 1).toUpperCase(Locale.ROOT);
                if (!sur.isEmpty() && !card14.isEmpty()) {
                    return "C_" + dateTime + "_" + ini + "_" + sur + "_" + card14 + ".DDD";
                }
            }
        } catch (Exception ignored) {
        }
        return "KARTA_" + dateTime + ".DDD";
    }

    private static byte[] findDddBlock(byte[] d, int wantedFid) {
        int p = 0;
        while (p + 5 <= d.length) {
            int fid = ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
            int type = d[p + 2] & 0xFF;
            int len = ((d[p + 3] & 0xFF) << 8) | (d[p + 4] & 0xFF);
            if (p + 5 + len > d.length) {
                break;
            }
            if (fid == wantedFid && type == 0) {
                byte[] value = new byte[len];
                System.arraycopy(d, p + 5, value, 0, len);
                return value;
            }
            p += 5 + len;
        }
        return null;
    }

    private void refreshReaderStatus() {
        if (statusText == null || busy) {
            return;
        }
        UsbDevice device = UsbCcidReader.findCompatibleDevice(usbManager);
        if (device == null) {
            showStatus("Czytnik niepodłączony", "Podłącz czytnik USB-C.", RED);
        } else {
            showStatus("Czytnik podłączony ✓", "Włóż kartę i naciśnij ODCZYTAJ I WYŚLIJ.", GREEN);
        }
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    @SuppressWarnings("deprecation")
    private UsbDevice getUsbDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private void setBusy(boolean value) {
        busy = value;
        if (readButton != null) {
            readButton.setEnabled(!value);
            readButton.setAlpha(value ? 0.55f : 1f);
        }
    }

    private void showError(String title, String detail) {
        showStatus(title, detail, RED);
    }

    private void showStatus(String title, String detail, int color) {
        if (statusText == null) {
            return;
        }
        statusText.setText(title);
        statusText.setTextColor(color);
        detailText.setText(detail);
    }

    private String friendlyError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Sprawdź czytnik, kartę i spróbuj ponownie.";
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("brak karty")) {
            return "Włóż kartę kierowcy do czytnika i spróbuj ponownie.";
        }
        if (lower.contains("permission") || lower.contains("zgod")) {
            return "Telefon nie zezwolił na dostęp do czytnika USB.";
        }
        return message;
    }

    private static String generationLabel(List<TachographCardDownloader.AppGeneration> generations) {
        if (generations.size() == 2) {
            return "Gen1 + Gen2";
        }
        return generations.isEmpty() ? "nieznana" : generations.get(0).name().replace("GEN", "Gen");
    }

    private TextView text(String value, int sp, int style, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.08f);
        return view;
    }

    private Button bigButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(22);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(color, dp(16)));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private LinearLayout.LayoutParams fixedHeight(int height, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
