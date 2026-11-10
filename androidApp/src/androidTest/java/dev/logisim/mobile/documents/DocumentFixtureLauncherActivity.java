package dev.logisim.mobile.documents;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;

/** A visible user action launches the external intent under Android's background-launch rules. */
public final class DocumentFixtureLauncherActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    String source = getIntent().getStringExtra("fixtureUri");
    Uri sourceUri = Uri.parse(source == null
        ? "content://" + getPackageName() + ".documents/document/writable.circ" : source);
    String document = sourceUri.getLastPathSegment();
    if (!"content".equals(sourceUri.getScheme())
        || !(getPackageName() + ".documents").equals(sourceUri.getAuthority())
        || document == null || !document.matches("(?:writable|e2e-[a-f0-9]{32})\\.circ")
        || !("/document/" + document).equals(sourceUri.getPath())
        || sourceUri.getQuery() != null || sourceUri.getFragment() != null) {
      throw new IllegalArgumentException("Only an owned circuit fixture URI may be opened");
    }
    boolean share = "share".equals(getIntent().getStringExtra("action"));
    Button button = new Button(this);
    button.setText(share ? "Share fixture" : "Open fixture");
    button.setOnClickListener(view -> {
      Uri uri = sourceUri;
      Intent open;
      if (share) open = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri);
      else open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain");
      open.setPackage(getPackageName().replaceFirst("\\.test$", ""));
      open.setClipData(ClipData.newRawUri("Fixture circuit", uri));
      open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(open);
      finish();
    });
    setContentView(button);
  }
}
