package dev.logisim.mobile.documents;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class DocumentFixtureBootstrapReceiver extends BroadcastReceiver {
  public static final String ACTION_PREPARE_CIRCUIT = "dev.logisim.mobile.documents.PREPARE_CIRCUIT_FIXTURE";
  public static final String ACTION_REVOKE_CIRCUIT_GRANT = "dev.logisim.mobile.documents.REVOKE_CIRCUIT_FIXTURE_GRANT";
  public static final String EXTRA_DISPLAY_NAME = "fixtureDisplayName";
  public static final String RESULT_ERROR = "fixtureError";

  @Override public void onReceive(Context context, Intent intent) {
    Uri provider = Uri.parse("content://" + context.getPackageName() + ".documents");
    if (ACTION_PREPARE_CIRCUIT.equals(intent.getAction())) {
      prepareCircuit(context, intent, provider);
      return;
    }
    if (ACTION_REVOKE_CIRCUIT_GRANT.equals(intent.getAction())) {
      revokeCircuitGrant(context, intent);
      return;
    }
    String replacement = intent.getStringExtra("xml");
    if (replacement == null) context.getContentResolver().call(provider, "fixture:reset", null, null);
    else {
      Bundle values = new Bundle();
      values.putByteArray("bytes", replacement.getBytes(StandardCharsets.UTF_8));
      context.getContentResolver().call(provider, "fixture:replace", "writable.circ", values);
    }
    setResultCode(android.app.Activity.RESULT_OK);
  }

  private void revokeCircuitGrant(Context context, Intent intent) {
    setResultCode(android.app.Activity.RESULT_CANCELED);
    setResultData(null);
    try {
      String owner = context.getPackageName();
      if (!owner.endsWith(".e2e.test")) throw new IllegalArgumentException("An isolated fixture owner is required");
      String name = intent.getStringExtra(EXTRA_DISPLAY_NAME);
      if (name == null) name = "writable.circ";
      if (!name.equals("writable.circ") && !name.equals("atomic.circ")) {
        throw new IllegalArgumentException("A revocable circuit fixture is required");
      }
      if (!new File(context.getFilesDir(), "document-fixtures/" + name).isFile()) {
        throw new IllegalStateException("The circuit fixture must still exist");
      }
      Uri uri = DocumentsContract.buildDocumentUri(owner + ".documents", name);
      context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      setResultData(uri.toString());
      setResultCode(android.app.Activity.RESULT_OK);
    } catch (RuntimeException error) {
      Bundle failure = new Bundle();
      failure.putString(RESULT_ERROR, error.getClass().getSimpleName() + ": " + error.getMessage());
      setResultExtras(failure);
    }
  }

  private void prepareCircuit(Context context, Intent intent, Uri provider) {
    setResultCode(android.app.Activity.RESULT_CANCELED);
    setResultData(null);
    try {
      String owner = context.getPackageName();
      if (!owner.endsWith(".e2e.test")) throw new IllegalArgumentException("An isolated fixture owner is required");
      String name = intent.getStringExtra(EXTRA_DISPLAY_NAME);
      String xml = intent.getStringExtra("xml");
      if (name == null || !name.endsWith(".circ") || !name.equals(new File(name).getName())
          || name.indexOf('\\') >= 0 || xml == null) {
        throw new IllegalArgumentException("A circuit filename and XML are required");
      }
      String id = UUID.randomUUID() + "-" + name;
      Bundle values = new Bundle();
      values.putByteArray("bytes", xml.getBytes(StandardCharsets.UTF_8));
      Bundle prepared = context.getContentResolver().call(provider, "fixture:prepare-readable", id, values);
      Uri uri = DocumentsContract.buildDocumentUri(owner + ".documents", id);
      if (prepared == null || !uri.toString().equals(prepared.getString("uri"))) {
        throw new IllegalStateException("The fixture owner did not return its prepared document");
      }
      String target = owner.substring(0, owner.length() - ".test".length());
      context.grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      setResultData(uri.toString());
      setResultCode(android.app.Activity.RESULT_OK);
    } catch (RuntimeException error) {
      Bundle failure = new Bundle();
      failure.putString(RESULT_ERROR, error.getClass().getSimpleName() + ": " + error.getMessage());
      setResultExtras(failure);
    }
  }
}
