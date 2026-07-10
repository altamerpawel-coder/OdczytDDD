package pl.altamer.odczytddd.share;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Minimalny, tylko-do-odczytu provider dla plików DDD z katalogu files/ddd. */
public final class ShareFileProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    public static Uri uriForFile(String authority, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath("ddd")
                .appendPath(file.getName())
                .build();
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Tylko odczyt");
        }
        File file = resolve(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null || uri.getPathSegments().size() != 2
                || !"ddd".equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException("Nieprawidłowy adres pliku");
        }
        String name = uri.getPathSegments().get(1);
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new FileNotFoundException("Nieprawidłowa nazwa pliku");
        }
        try {
            File base = new File(getContext().getFilesDir(), "ddd").getCanonicalFile();
            File file = new File(base, name).getCanonicalFile();
            if (!file.getParentFile().equals(base) || !file.isFile()) {
                throw new FileNotFoundException("Plik nie istnieje");
            }
            return file;
        } catch (IOException e) {
            throw new FileNotFoundException("Nie można otworzyć pliku");
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Tylko odczyt");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Tylko odczyt");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Tylko odczyt");
    }
}
