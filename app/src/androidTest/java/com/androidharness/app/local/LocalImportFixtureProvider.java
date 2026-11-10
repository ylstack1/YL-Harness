package com.androidharness.app.local;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Runs in the standalone test APK, which does not inherit the target APK's Kotlin runtime. */
public final class LocalImportFixtureProvider extends ContentProvider {
    private static final String[] FILES = {"config.json", "tokenizer.json", "tokenizer_config.json", "model.safetensors"};
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS};
    private File root;

    @Override public boolean onCreate() {
        root = new File(getContext().getCacheDir(), "local-import-fixture");
        if (!root.isDirectory() && !root.mkdirs()) return false;
        try {
            for (String name : FILES) {
                try (InputStream input = getContext().getAssets().open("local-import-fixture/" + name);
                     FileOutputStream output = new FileOutputStream(new File(root, name))) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
            }
            return true;
        } catch (IOException error) { return false; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        if ("children".equals(uri.getLastPathSegment())) return queryChildDocuments("fixture", projection, sortOrder);
        return queryDocument(android.provider.DocumentsContract.getDocumentId(uri), projection);
    }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        return openDocument(android.provider.DocumentsContract.getDocumentId(uri), mode, null);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only fixture"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read-only fixture"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only fixture"); }

    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS});
        cursor.newRow().add(Root.COLUMN_ROOT_ID, "fixture").add(Root.COLUMN_DOCUMENT_ID, "fixture")
            .add(Root.COLUMN_TITLE, "Local import test").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD);
        return cursor;
    }

    public Cursor queryDocument(String documentId, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        addDocument(cursor, documentId);
        return cursor;
    }

    public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) {
        if (!"fixture".equals(parentId)) throw new IllegalArgumentException("Unknown fixture");
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        for (String name : FILES) addDocument(cursor, name);
        return cursor;
    }

    public boolean isChildDocument(String parent, String id) {
        return "fixture".equals(parent) && Arrays.asList(FILES).contains(id);
    }

    public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
        throws java.io.FileNotFoundException {
        if (!"r".equals(mode) || !Arrays.asList(FILES).contains(id)) throw new IllegalArgumentException("Read-only fixture");
        return ParcelFileDescriptor.open(new File(root, id), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static void addDocument(MatrixCursor cursor, String id) {
        if (!"fixture".equals(id) && !Arrays.asList(FILES).contains(id)) throw new IllegalArgumentException("Unknown document");
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, id)
            .add(Document.COLUMN_MIME_TYPE, "fixture".equals(id) ? Document.MIME_TYPE_DIR : "application/octet-stream")
            .add(Document.COLUMN_SIZE, null).add(Document.COLUMN_FLAGS, 0);
    }
}
