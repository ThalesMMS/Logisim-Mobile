package dev.logisim.mobile.documents;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.Button;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Reads copied XML only after an explicit action in a foreground test activity. */
public final class DocumentClipboardReaderActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    String marker = getIntent().getStringExtra("prepareCopy");
    if (marker != null && !marker.matches("[a-f0-9]{32}")) {
      throw new IllegalArgumentException("Invalid clipboard copy marker");
    }
    Button button = new Button(this);
    button.setText(marker == null ? "Read copied XML" : "Prepare XML copy");
    button.setOnClickListener(view -> {
      ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
      if (marker != null) {
        clipboard.setPrimaryClip(ClipData.newPlainText("E2E pending XML copy", "E2E waiting for XML copy " + marker));
        File receipt = new File(getFilesDir(), "clipboard-project.circ");
        if (receipt.exists() && !receipt.delete()) {
          throw new IllegalStateException("Could not clear the previous copied XML receipt");
        }
        finish();
        return;
      }
      ClipData clip = clipboard.getPrimaryClip();
      if (clip == null || clip.getItemCount() != 1 || clip.getItemAt(0).getText() == null) {
        throw new IllegalStateException("The application did not copy project text");
      }
      byte[] bytes = clip.getItemAt(0).getText().toString().getBytes(StandardCharsets.UTF_8);
      try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "clipboard-project.circ"))) {
        output.write(bytes);
      } catch (java.io.IOException error) {
        throw new IllegalStateException("Could not retain copied XML", error);
      }
      finish();
    });
    setContentView(button);
  }
}
