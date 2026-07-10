package pl.altamer.odczytddd;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
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
    private static final int BLUE = Color.rgb(23, 74, 126);
    private static final int GREEN = Color.rgb(31, 122, 70);
    private static final int RED = Color.rgb(176, 45, 45);
    private static final int TEXT = Color.rgb(31, 41, 55);

    private UsbManager usbManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView statusText;
    private TextView detailText;
    private Button readButton;
    private Button shareButton;
    private File latestFile;
    private boolean busy;

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
                    showError("Brak zgody na czytnik USB", "Naciśnij ODCZYTAJ KARTĘ i zaakceptuj zgodę.");
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
        setContentView(buildUi());
        registerUsbReceiver();
        refreshReaderStatus();
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

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 247, 250));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("ODCZYT KARTY KIEROWCY", 26, Typeface.BOLD, BLUE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(dp(8)));

        TextView steps = text(
                "1. Podłącz czytnik USB-C\n\n"
                        + "2. Włóż kartę kierowcy\n\n"
                        + "3. Naciśnij przycisk poniżej",
                21, Typeface.BOLD, TEXT
        );
        steps.setGravity(Gravity.START);
        steps.setPadding(dp(20), dp(20), dp(20), dp(20));
        steps.setBackground(rounded(Color.WHITE, dp(16)));
        root.addView(steps, matchWrap(dp(18)));

        statusText = text("Sprawdzam czytnik…", 24, Typeface.BOLD, TEXT);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, matchWrap(dp(6)));

        detailText = text("", 17, Typeface.NORMAL, Color.DKGRAY);
        detailText.setGravity(Gravity.CENTER);
        root.addView(detailText, matchWrap(dp(18)));

        readButton = bigButton("ODCZYTAJ KARTĘ", BLUE);
        readButton.setOnClickListener(v -> onReadClicked());
        root.addView(readButton, fixedHeight(dp(82), dp(14)));

        shareButton = bigButton("WYŚLIJ PLIK DDD", GREEN);
        shareButton.setEnabled(false);
        shareButton.setAlpha(0.45f);
        shareButton.setOnClickListener(v -> shareLatestFile());
        root.addView(shareButton, fixedHeight(dp(82), dp(18)));

        TextView footer = text(
                "Po odczycie wybierzesz, gdzie wysłać plik: e-mail, WhatsApp, Dysk itp.",
                16, Typeface.NORMAL, Color.DKGRAY
        );
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, matchWrap(0));

        return scroll;
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
        shareButton.setEnabled(false);
        shareButton.setAlpha(0.45f);

        if (!usbManager.hasPermission(device)) {
            setBusy(true);
            showStatus("Potrzebna zgoda USB", "Za chwilę pojawi się okno. Naciśnij OK / Zezwól.", BLUE);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                pendingFlags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    pendingFlags
            );
            usbManager.requestPermission(device, permissionIntent);
            return;
        }
        startRead(device);
    }

    private void startRead(UsbDevice device) {
        setBusy(true);
        showStatus("Odczytuję kartę…", "Nie wyjmuj karty ani czytnika.", BLUE);

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
            boolean lastDownloadUpdated
    ) {
        latestFile = file;
        setBusy(false);
        shareButton.setEnabled(true);
        shareButton.setAlpha(1f);

        String gen = generationLabel(result.generations());
        if (lastDownloadUpdated) {
            showStatus("GOTOWE ✓", "Plik DDD zapisany. Karta: " + gen + "\nNaciśnij WYŚLIJ PLIK DDD.", GREEN);
        } else {
            showStatus(
                    "PLIK ZAPISANY ✓",
                    "Karta: " + gen + "\nUwaga: karta nie potwierdziła zapisu daty ostatniego odczytu.\nMożesz wysłać plik DDD.",
                    Color.rgb(176, 110, 20)
            );
        }
    }

    private File saveDdd(byte[] data) throws Exception {
        File dir = new File(getFilesDir(), "ddd");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("Nie można utworzyć katalogu na pliki DDD");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, "KARTA_" + stamp + ".DDD");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
            out.flush();
        }
        return file;
    }

    private void shareLatestFile() {
        if (latestFile == null || !latestFile.isFile()) {
            Toast.makeText(this, "Najpierw odczytaj kartę", Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = ShareFileProvider.uriForFile(getPackageName() + ".files", latestFile);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/octet-stream");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, "Plik DDD karty kierowcy");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Wyślij plik DDD"));
    }

    private void refreshReaderStatus() {
        if (busy) {
            return;
        }
        UsbDevice device = UsbCcidReader.findCompatibleDevice(usbManager);
        if (device == null) {
            showStatus("Czytnik niepodłączony", "Podłącz czytnik USB-C.", RED);
        } else {
            showStatus("Czytnik podłączony ✓", "Włóż kartę i naciśnij ODCZYTAJ KARTĘ.", GREEN);
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
        readButton.setEnabled(!value);
        readButton.setAlpha(value ? 0.55f : 1f);
    }

    private void showError(String title, String detail) {
        showStatus(title, detail, RED);
    }

    private void showStatus(String title, String detail, int color) {
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
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = bottomMargin;
        return params;
    }

    private LinearLayout.LayoutParams fixedHeight(int height, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        );
        params.bottomMargin = bottomMargin;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
