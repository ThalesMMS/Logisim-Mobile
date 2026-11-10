package dev.logisim.mobile.documents;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** A separate UID verifies the temporary share grant without sending data to another service. */
public final class DocumentShareReceiverActivity extends Activity {
  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    JSONObject result = new JSONObject();
    try {
      Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
      if (uri == null || !"content".equals(uri.getScheme())) throw new IllegalArgumentException("Missing content URI");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream input = getContentResolver().openInputStream(uri)) {
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) >= 0) {
          if ((long) bytes.size() + length > 64L * 1024L * 1024L) throw new IllegalArgumentException("Shared test document exceeds 64 MiB");
          bytes.write(buffer, 0, length);
        }
      }
      byte[] payload = bytes.toByteArray();
      int count = 0;
      if ("application/x-logisim-circuit".equals(getIntent().getType())) {
        String xml = bytes.toString(StandardCharsets.UTF_8.name());
        Matcher circuits = Pattern.compile("<circuit\\s").matcher(xml);
        while (circuits.find()) count++;
      }
      String name = null;
      try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
      }
      result.put("uri", uri.toString());
      result.put("displayName", name);
      result.put("circuits", count);
      result.put("bytes", bytes.size());
      result.put("mimeType", getIntent().getType());
      result.put("providerMimeType", getContentResolver().getType(uri));
      result.put("prefixBase64", Base64.encodeToString(Arrays.copyOf(payload, Math.min(32, payload.length)), Base64.NO_WRAP));
      StringBuilder digest = new StringBuilder();
      for (byte value : MessageDigest.getInstance("SHA-256").digest(payload)) digest.append(String.format("%02x", value & 255));
      result.put("sha256", digest.toString());
      result.put("readable", true);
    } catch (Exception error) {
      try { result.put("error", error.toString()); } catch (Exception ignored) { }
    }
    try (FileOutputStream stream = new FileOutputStream(new File(getFilesDir(), "share-result.json"))) {
      stream.write(result.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Exception error) { throw new IllegalStateException(error); }
    finish();
  }
}
