package dev.logisim.mobile.documents;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Uses only Android/Java classes because it runs outside the instrumented app process. */
public final class DocumentFixtureProvider extends DocumentsProvider {
  public static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><project version=\"1.0\"><main name=\"Árvore\"/><circuit name=\"Árvore\"/></project>";
  private static final int GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
  private static final String ATOMIC_ID = "atomic.circ";
  private static final int MAX_BYTES = 16 * 1024 * 1024;
  private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
      Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES};
  private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
      Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED};
  private volatile boolean denied;
  private String nextAtomicResult;

  private File directory() { return new File(getContext().getFilesDir(), "document-fixtures"); }
  private String authority() { return getContext().getPackageName() + ".documents"; }
  private String targetPackage() { return getContext().getPackageName().replaceFirst("\\.test$", ""); }

  @Override public boolean onCreate() {
    if (!directory().isDirectory() && !directory().mkdirs()) throw new IllegalStateException("Fixture directory");
    if (!file("writable.circ").exists()) reset();
    grant("root"); grant("writable.circ"); grant("readonly.circ"); grant("missing.circ"); grant(ATOMIC_ID);
    getContext().grantUriPermission(targetPackage(), Uri.parse("content://" + authority()), GRANTS);
    return true;
  }

  @Override public Cursor queryRoots(String[] projection) {
    MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
    MatrixCursor.RowBuilder row = cursor.newRow();
    for (String name : cursor.getColumnNames()) {
      Object value = null;
      if (name.equals(Root.COLUMN_ROOT_ID) || name.equals(Root.COLUMN_DOCUMENT_ID)) value = "root";
      else if (name.equals(Root.COLUMN_TITLE)) value = "Document test provider: " + getContext().getPackageName();
      else if (name.equals(Root.COLUMN_FLAGS)) value = Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD;
      else if (name.equals(Root.COLUMN_MIME_TYPES)) value = "*/*";
      else if (name.equals(Root.COLUMN_AVAILABLE_BYTES)) value = 1024L * 1024L * 1024L;
      row.add(value);
    }
    return cursor;
  }

  @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
    MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
    add(cursor, id);
    return cursor;
  }

  @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) throws FileNotFoundException {
    if (!parent.equals("root")) throw new FileNotFoundException(parent);
    MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
    File[] files = directory().listFiles();
    if (files != null) for (File item : files) if (item.isFile()) add(cursor, item.getName());
    return cursor;
  }

  @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
    if (denied) throw new SecurityException("Fixture permission revoked");
    if ((id.equals("readonly.circ") || id.equals(ATOMIC_ID)) && !mode.equals("r")) {
      throw new SecurityException("This document does not accept generic stream writes");
    }
    return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
  }

  @Override public String createDocument(String parent, String mime, String displayName) {
    if (!parent.equals("root")) throw new IllegalArgumentException("Fixture parent");
    String id = UUID.randomUUID() + "-" + new File(displayName).getName();
    write(id, new byte[0]);
    grant(id);
    return id;
  }

  @Override public synchronized void deleteDocument(String id) {
    if (!file(id).delete()) throw new IllegalStateException("Fixture delete");
  }

  @Override public boolean isChildDocument(String parent, String id) {
    return parent.equals("root") && !id.equals("root") && file(id).isFile();
  }

  @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
    switch (method) {
      case "org.logisim.documents.atomic-replace-capabilities": {
        if (!isAtomicDocument(arg)) return null;
        requireAtomicGrant(arg);
        Bundle capability = new Bundle();
        capability.putInt("version", 1);
        return capability;
      }
      case "org.logisim.documents.atomic-replace-v1":
        if (!isAtomicDocument(arg)) return null;
        requireAtomicGrant(arg);
        return replaceAtomically(arg, extras);
      case "fixture:next-atomic-result": nextAtomicResult = arg; return new Bundle();
      case "fixture:prepare-readable": {
        if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Only the fixture owner may prepare a source");
        if (arg == null || extras == null || extras.getByteArray("bytes") == null) {
          throw new IllegalArgumentException("A fixture document and bytes are required");
        }
        write(arg, extras.getByteArray("bytes"));
        denied = false;
        Bundle prepared = new Bundle();
        prepared.putString("uri", DocumentsContract.buildDocumentUri(authority(), arg).toString());
        return prepared;
      }
      case "fixture:reset": reset(); return new Bundle();
      case "fixture:deny":
        denied = true;
        getContext().revokeUriPermission(DocumentsContract.buildDocumentUri(authority(), "writable.circ"), GRANTS);
        getContext().revokeUriPermission(DocumentsContract.buildDocumentUri(authority(), ATOMIC_ID), GRANTS);
        return new Bundle();
      case "fixture:allow": denied = false; grant("writable.circ"); grant(ATOMIC_ID); return new Bundle();
      case "fixture:replace": write(arg, extras.getByteArray("bytes")); return new Bundle();
      default: return super.call(method, arg, extras);
    }
  }

  private boolean isAtomicDocument(String uri) {
    return DocumentsContract.buildDocumentUri(authority(), ATOMIC_ID).toString().equals(uri);
  }

  private void requireAtomicGrant(String uri) {
    if (denied) throw new SecurityException("Fixture permission revoked");
    getContext().enforceCallingUriPermission(Uri.parse(uri), GRANTS, "The original document requires read/write access");
  }

  private Bundle replaceAtomically(String uri, Bundle extras) {
    Bundle result = new Bundle();
    String outcome = nextAtomicResult;
    nextAtomicResult = null;
    if (extras == null) throw new IllegalArgumentException("Missing atomic payload");
    ParcelFileDescriptor payload = extras.getParcelable("payload");
    if (payload == null) throw new IllegalArgumentException("Missing atomic payload");
    try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(payload)) {
      int count = extras.getInt("byteCount", -1);
      if (count < 0 || count > MAX_BYTES) throw new IllegalArgumentException("Invalid payload size");
      File document = file(ATOMIC_ID);
      if ("race".equals(outcome)) write(ATOMIC_ID, "concurrent edit".getBytes(StandardCharsets.UTF_8));
      if (!sha256(Files.readAllBytes(document.toPath())).equals(extras.getString("expectedSha256"))) {
        result.putString("result", "conflict");
        return result;
      }
      File staged = File.createTempFile("atomic-circuit-", ".pending", getContext().getCacheDir());
      try {
        try (FileOutputStream output = new FileOutputStream(staged)) {
          byte[] buffer = new byte[8192];
          int remaining = count;
          while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Incomplete atomic payload");
            output.write(buffer, 0, read);
            remaining -= read;
            if ("interrupt".equals(outcome)) throw new IOException("Interrupted before publication");
          }
          if (input.read() != -1) throw new IOException("Unexpected payload bytes");
          output.getFD().sync();
        }
        requireAtomicGrant(uri);
        Files.move(staged.toPath(), document.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(staged.toPath());
      }
      if ("lost-reply".equals(outcome)) return null;
      result.putString("result", "saved");
    } catch (IOException error) {
      result.putString("result", "failed");
    }
    return result;
  }

  private static String sha256(byte[] bytes) {
    try {
      StringBuilder hash = new StringBuilder();
      for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", value));
      return hash.toString();
    } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
  }

  private void add(MatrixCursor cursor, String id) throws FileNotFoundException {
    if (denied) throw new SecurityException("Fixture permission revoked");
    File target = id.equals("root") ? directory() : file(id);
    if (!target.exists()) throw new FileNotFoundException(id);
    MatrixCursor.RowBuilder row = cursor.newRow();
    for (String name : cursor.getColumnNames()) {
      Object value = null;
      if (name.equals(Document.COLUMN_DOCUMENT_ID) || name.equals(Document.COLUMN_DISPLAY_NAME)) value = id;
      else if (name.equals(Document.COLUMN_MIME_TYPE)) value = id.equals("root") ? Document.MIME_TYPE_DIR : mimeType(id);
      else if (name.equals(Document.COLUMN_SIZE)) value = target.isFile() ? target.length() : null;
      else if (name.equals(Document.COLUMN_LAST_MODIFIED)) value = target.lastModified();
      else if (name.equals(Document.COLUMN_FLAGS)) value = id.equals("root") ? Document.FLAG_DIR_SUPPORTS_CREATE :
          id.equals("readonly.circ") ? 0 : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE;
      row.add(value);
    }
  }

  private File file(String id) {
    if (!id.equals(new File(id).getName()) || id.equals(".") || id.equals("..")) throw new IllegalArgumentException("Fixture id");
    return new File(directory(), id);
  }

  private String mimeType(String id) {
    String name = id.toLowerCase(java.util.Locale.ROOT);
    if (name.endsWith(".png")) return "image/png";
    if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
    if (name.endsWith(".gif")) return "image/gif";
    if (name.endsWith(".svg")) return "image/svg+xml";
    if (name.endsWith(".pdf")) return "application/pdf";
    if (name.endsWith(".bin") || name.endsWith(".elf")) return "application/octet-stream";
    if (name.endsWith(".lsebdl") || name.endsWith(".zip")) return "application/zip";
    return "text/plain";
  }

  private synchronized void write(String id, byte[] bytes) {
    try (FileOutputStream stream = new FileOutputStream(file(id))) { stream.write(bytes); }
    catch (IOException error) { throw new IllegalStateException(error); }
  }

  private void reset() {
    denied = false;
    nextAtomicResult = null;
    File[] files = directory().listFiles();
    if (files != null) for (File item : files) item.delete();
    write("writable.circ", XML.getBytes(StandardCharsets.UTF_8));
    write("readonly.circ", XML.getBytes(StandardCharsets.UTF_8));
    write(ATOMIC_ID, XML.getBytes(StandardCharsets.UTF_8));
    grant("root"); grant("writable.circ"); grant("readonly.circ"); grant("missing.circ"); grant(ATOMIC_ID);
  }

  private void grant(String id) {
    getContext().grantUriPermission(targetPackage(), DocumentsContract.buildDocumentUri(authority(), id), GRANTS);
  }
}
