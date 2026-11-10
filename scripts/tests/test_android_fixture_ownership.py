from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "androidApp/src/androidTest/java/dev/logisim/mobile/documents"

# These stubs let the real receiver and provider execute their boundary logic on
# the host. Android UID enforcement and activity delivery still need device proof.
STUBS = {
    "android/app/Activity.java": """package android.app;
public class Activity { public static final int RESULT_OK=-1, RESULT_CANCELED=0; }
""",
    "android/os/Bundle.java": """package android.os;
public class Bundle {
  private final java.util.Map<String,Object> values=new java.util.HashMap<>();
  public void putString(String key,String value){values.put(key,value);}
  public String getString(String key){return (String)values.get(key);}
  public void putByteArray(String key,byte[] value){values.put(key,value);}
  public byte[] getByteArray(String key){return (byte[])values.get(key);}
  public void putInt(String key,int value){values.put(key,value);}
  public int getInt(String key,int fallback){Object value=values.get(key);return value==null?fallback:(Integer)value;}
  public void putParcelable(String key,Object value){values.put(key,value);}
  @SuppressWarnings("unchecked") public <T> T getParcelable(String key){return (T)values.get(key);}
}
""",
    "android/os/Binder.java": """package android.os;
public class Binder { public static int caller=1001; public static int getCallingUid(){return caller;} }
""",
    "android/os/Process.java": """package android.os;
public class Process { public static int myUid(){return 1001;} }
""",
    "android/os/CancellationSignal.java": "package android.os; public class CancellationSignal {}\n",
    "android/os/ParcelFileDescriptor.java": """package android.os;
public class ParcelFileDescriptor {
  public static int parseMode(String mode){return 0;}
  public static ParcelFileDescriptor open(java.io.File file,int mode) throws java.io.FileNotFoundException {
    if(!file.isFile())throw new java.io.FileNotFoundException(file.toString());
    return new ParcelFileDescriptor();
  }
  public static class AutoCloseInputStream extends java.io.InputStream {
    public AutoCloseInputStream(ParcelFileDescriptor descriptor){}
    public int read(){return -1;}
  }
}
""",
    "android/net/Uri.java": """package android.net;
public final class Uri {
  private final String value; private Uri(String value){this.value=value;}
  public static Uri parse(String value){return new Uri(value);}
  public String toString(){return value;}
}
""",
    "android/content/Intent.java": """package android.content;
public class Intent {
  public static final int FLAG_GRANT_READ_URI_PERMISSION=1,FLAG_GRANT_WRITE_URI_PERMISSION=2;
  private final java.util.Map<String,String> extras=new java.util.HashMap<>();
  private String action;
  public Intent(){} public Intent(String action){this.action=action;}
  public String getAction(){return action;}
  public Intent putExtra(String key,String value){extras.put(key,value);return this;}
  public String getStringExtra(String key){return extras.get(key);}
}
""",
    "android/content/BroadcastReceiver.java": """package android.content;
public abstract class BroadcastReceiver {
  private int code=0;private String data;private android.os.Bundle extras;
  public abstract void onReceive(Context context,Intent intent);
  public void setResultCode(int code){this.code=code;} public int getResultCode(){return code;}
  public void setResultData(String data){this.data=data;}public String getResultData(){return data;}
  public void setResultExtras(android.os.Bundle extras){this.extras=extras;}
  public android.os.Bundle getResultExtras(boolean create){return extras;}
}
""",
    "android/content/Context.java": """package android.content;
public class Context {
  public final String name;public final java.io.File files;public final int uid;
  public final ContentResolver resolver=new ContentResolver(this);
  public final java.util.List<Grant> grants=new java.util.ArrayList<>();
  public final java.util.List<String> revoked=new java.util.ArrayList<>();
  public Context(String name,java.io.File files,int uid){this.name=name;this.files=files;this.uid=uid;}
  public String getPackageName(){return name;}public java.io.File getFilesDir(){return files;}
  public java.io.File getCacheDir(){return files;}
  public final java.util.List<String> enforced=new java.util.ArrayList<>();
  public void enforceCallingUriPermission(android.net.Uri uri,int flags,String message){enforced.add(uri+":"+flags);}
  public ContentResolver getContentResolver(){return resolver;}
  public void grantUriPermission(String target,android.net.Uri uri,int flags){grants.add(new Grant(target,uri.toString(),flags));}
  public void revokeUriPermission(android.net.Uri uri,int flags){revoked.add(uri+":"+flags);}
  public static final class Grant {
    public final String target,uri;public final int flags;
    public Grant(String target,String uri,int flags){this.target=target;this.uri=uri;this.flags=flags;}
  }
}
""",
    "android/content/ContentResolver.java": """package android.content;
public class ContentResolver {
  public final Context context;public android.provider.DocumentsProvider provider;
  public final java.util.List<String> calls=new java.util.ArrayList<>();
  public String returnedUri;
  public ContentResolver(Context context){this.context=context;}
  public android.os.Bundle call(android.net.Uri uri,String method,String arg,android.os.Bundle values){
    calls.add(context.name+":"+method+":"+arg);
    int before=android.os.Binder.caller;android.os.Binder.caller=context.uid;
    try {
      android.os.Bundle result=provider.call(method,arg,values);
      if(returnedUri!=null && method.equals("fixture:prepare-readable"))result.putString("uri",returnedUri);
      return result;
    } finally {android.os.Binder.caller=before;}
  }
}
""",
    "android/database/Cursor.java": "package android.database; public interface Cursor {String[] getColumnNames();}\n",
    "android/database/MatrixCursor.java": """package android.database;
public class MatrixCursor implements Cursor {
  private final String[] names;public MatrixCursor(String[] names){this.names=names;}
  public String[] getColumnNames(){return names;}public RowBuilder newRow(){return new RowBuilder();}
  public static class RowBuilder {public RowBuilder add(Object value){return this;}}
}
""",
    "android/provider/DocumentsContract.java": """package android.provider;
public class DocumentsContract {
  public static android.net.Uri buildDocumentUri(String authority,String id){
    try{return android.net.Uri.parse(new java.net.URI("content",authority,"/document/"+id,null).toASCIIString());}
    catch(java.net.URISyntaxException error){throw new IllegalArgumentException(error);}
  }
  public static class Document {
    public static final String COLUMN_DOCUMENT_ID="id",COLUMN_DISPLAY_NAME="name",COLUMN_MIME_TYPE="mime",
      COLUMN_SIZE="size",COLUMN_FLAGS="flags",COLUMN_LAST_MODIFIED="modified",MIME_TYPE_DIR="dir";
    public static final int FLAG_DIR_SUPPORTS_CREATE=1,FLAG_SUPPORTS_WRITE=2,FLAG_SUPPORTS_DELETE=4;
  }
  public static class Root {
    public static final String COLUMN_ROOT_ID="root",COLUMN_DOCUMENT_ID="id",COLUMN_TITLE="title",
      COLUMN_FLAGS="flags",COLUMN_MIME_TYPES="mimes",COLUMN_AVAILABLE_BYTES="available";
    public static final int FLAG_SUPPORTS_CREATE=1,FLAG_SUPPORTS_IS_CHILD=2;
  }
}
""",
    "android/provider/DocumentsProvider.java": """package android.provider;
public abstract class DocumentsProvider {
  private android.content.Context context;
  public void attachContext(android.content.Context context){this.context=context;}
  public android.content.Context getContext(){return context;}
  public abstract boolean onCreate();
  public abstract android.database.Cursor queryRoots(String[] projection);
  public abstract android.database.Cursor queryDocument(String id,String[] projection) throws java.io.FileNotFoundException;
  public abstract android.database.Cursor queryChildDocuments(String parent,String[] projection,String order) throws java.io.FileNotFoundException;
  public abstract android.os.ParcelFileDescriptor openDocument(String id,String mode,android.os.CancellationSignal signal) throws java.io.FileNotFoundException;
  public String createDocument(String parent,String mime,String name){return null;}
  public void deleteDocument(String id){}
  public boolean isChildDocument(String parent,String id){return false;}
  public android.os.Bundle call(String method,String arg,android.os.Bundle extras){return null;}
}
""",
}

PROBE = r"""
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.provider.DocumentsContract;
import dev.logisim.mobile.documents.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class FixtureOwnershipProbe {
  static final String OWNER="thalesmms.logisim.mobile.e2e.test";
  static final String TARGET="thalesmms.logisim.mobile.e2e";
  static final String XML="<project e2e-import-token=\"Ω-71\"><main name=\"mixed_deadbeef\"/></project>";
  static final class Setup {
    final Context context;final DocumentFixtureProvider provider;
    Setup() throws Exception {
      context=new Context(OWNER,Files.createTempDirectory("fixture-owner").toFile(),1001);
      provider=new DocumentFixtureProvider();provider.attachContext(context);context.resolver.provider=provider;
      Binder.caller=1001;if(!provider.onCreate())throw new AssertionError("Provider initialization failed");
      context.grants.clear();context.resolver.calls.clear();
    }
    File directory(){return new File(context.files,"document-fixtures");}
  }
  static DocumentFixtureBootstrapReceiver prepare(Setup setup,String name,String xml) {
    DocumentFixtureBootstrapReceiver receiver=new DocumentFixtureBootstrapReceiver();
    receiver.onReceive(setup.context,new Intent(DocumentFixtureBootstrapReceiver.ACTION_PREPARE_CIRCUIT)
      .putExtra(DocumentFixtureBootstrapReceiver.EXTRA_DISPLAY_NAME,name).putExtra("xml",xml));
    return receiver;
  }
  static void expect(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  static void denied(Setup setup) throws Exception {
    setup.provider.call("fixture:deny",null,null);setup.context.grants.clear();
    try{setup.provider.openDocument("writable.circ","r",null);throw new AssertionError("Expected denied provider");}
    catch(SecurityException expected){}
  }
  static String id(String uri) throws Exception {
    String path=new java.net.URI(uri).getPath();expect(path.startsWith("/document/"),"Document path");
    return path.substring("/document/".length());
  }
  static String valid(Setup setup,DocumentFixtureBootstrapReceiver receiver,String name) throws Exception {
    expect(receiver.getResultCode()==Activity.RESULT_OK,"Owner preparation did not succeed");
    String uri=receiver.getResultData();String id=id(uri);
    expect(uri.startsWith("content://"+OWNER+".documents/document/"),"Foreign source URI");
    expect(id.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}-"+java.util.regex.Pattern.quote(name)),"Unique display name not preserved");
    expect(Arrays.equals(Files.readAllBytes(new File(setup.directory(),id).toPath()),XML.getBytes(StandardCharsets.UTF_8)),"XML or nonce changed");
    expect(setup.context.resolver.calls.size()==1 && setup.context.resolver.calls.get(0).equals(OWNER+":fixture:prepare-readable:"+id),"The owner must perform one preparation call");
    expect(setup.context.grants.size()==1,"Unexpected root or writable grants");
    Context.Grant grant=setup.context.grants.get(0);
    expect(grant.target.equals(TARGET)&&grant.uri.equals(uri)&&grant.flags==Intent.FLAG_GRANT_READ_URI_PERMISSION,"Expected exact read-only source grant");
    return uri;
  }
  public static void main(String[] args) throws Exception {
    Setup setup=new Setup();String scenario=args[0];
    if(scenario.equals("prepare")) {
      byte[] old=Files.readAllBytes(new File(setup.directory(),"writable.circ").toPath());
      valid(setup,prepare(setup,"mixed_deadbeef.circ",XML),"mixed_deadbeef.circ");
      expect(Arrays.equals(old,Files.readAllBytes(new File(setup.directory(),"writable.circ").toPath())),"Legacy writable fixture changed");
    } else if(scenario.equals("denied")) {
      denied(setup);String uri=valid(setup,prepare(setup,"mixed_deadbeef.circ",XML),"mixed_deadbeef.circ");
      setup.provider.openDocument(id(uri),"r",null);
    } else if(scenario.equals("other-uid")) {
      denied(setup);int before=setup.directory().list().length;Binder.caller=2002;
      Bundle values=new Bundle();values.putByteArray("bytes",XML.getBytes(StandardCharsets.UTF_8));
      try{setup.provider.call("fixture:prepare-readable","new.circ",values);throw new AssertionError("Expected owner UID rejection");}
      catch(SecurityException expected){}
      expect(setup.directory().list().length==before && setup.context.grants.isEmpty(),"Rejected caller mutated provider");
      try{setup.provider.openDocument("writable.circ","r",null);throw new AssertionError("Rejected caller cleared denied");}
      catch(SecurityException expected){}
    } else if(scenario.equals("write-failure")) {
      denied(setup);for(File file:setup.directory().listFiles())expect(file.delete(),"Delete fixture");
      expect(setup.directory().delete(),"Delete directory");Files.write(setup.directory().toPath(),new byte[]{1});
      DocumentFixtureBootstrapReceiver receiver=prepare(setup,"mixed_deadbeef.circ",XML);
      expect(receiver.getResultCode()==Activity.RESULT_CANCELED && receiver.getResultData()==null && setup.context.grants.isEmpty(),"Failed write exposed a source");
      expect(receiver.getResultExtras(false).getString(DocumentFixtureBootstrapReceiver.RESULT_ERROR).startsWith("IllegalStateException:"),"Failure lost diagnostic");
      try{setup.provider.openDocument("writable.circ","r",null);throw new AssertionError("Failed write cleared denied");}
      catch(SecurityException expected){}
    } else if(scenario.equals("invalid-path") || scenario.equals("missing-xml")) {
      int before=setup.directory().list().length;
      DocumentFixtureBootstrapReceiver receiver=prepare(setup,scenario.equals("invalid-path")?"../mixed.circ":"mixed.circ",scenario.equals("missing-xml")?null:XML);
      expect(receiver.getResultCode()==Activity.RESULT_CANCELED && receiver.getResultData()==null,"Invalid request succeeded");
      expect(setup.context.resolver.calls.isEmpty()&&setup.context.grants.isEmpty()&&setup.directory().list().length==before,"Invalid request mutated owner");
    } else if(scenario.equals("wrong-owner")) {
      Context normal=new Context("thalesmms.logisim.mobile.test",setup.context.files,1001);normal.resolver.provider=setup.provider;
      DocumentFixtureBootstrapReceiver receiver=new DocumentFixtureBootstrapReceiver();
      receiver.onReceive(normal,new Intent(DocumentFixtureBootstrapReceiver.ACTION_PREPARE_CIRCUIT).putExtra("xml",XML).putExtra(DocumentFixtureBootstrapReceiver.EXTRA_DISPLAY_NAME,"mixed.circ"));
      expect(receiver.getResultCode()==Activity.RESULT_CANCELED&&receiver.getResultData()==null&&normal.resolver.calls.isEmpty()&&normal.grants.isEmpty(),"Normal package used isolated protocol");
    } else if(scenario.equals("foreign-response")) {
      setup.context.resolver.returnedUri="content://another.provider/document/source";
      DocumentFixtureBootstrapReceiver receiver=prepare(setup,"mixed.circ",XML);
      expect(receiver.getResultCode()==Activity.RESULT_CANCELED&&receiver.getResultData()==null&&setup.context.grants.isEmpty(),"Foreign provider URI granted");
    } else if(scenario.equals("duplicate")) {
      String first=valid(setup,prepare(setup,"mixed_deadbeef.circ",XML),"mixed_deadbeef.circ");
      setup.context.grants.clear();setup.context.resolver.calls.clear();
      String second=valid(setup,prepare(setup,"mixed_deadbeef.circ",XML),"mixed_deadbeef.circ");
      expect(!first.equals(second)&&new File(setup.directory(),id(first)).isFile(),"Sources were reused or reset");
    } else if(scenario.equals("legacy-xml")) {
      DocumentFixtureBootstrapReceiver receiver=new DocumentFixtureBootstrapReceiver();receiver.onReceive(setup.context,new Intent().putExtra("xml",XML));
      expect(receiver.getResultCode()==Activity.RESULT_OK&&receiver.getResultData()==null,"Legacy result contract changed");
      expect(new String(Files.readAllBytes(new File(setup.directory(),"writable.circ").toPath()),StandardCharsets.UTF_8).equals(XML),"Legacy replacement changed");
      expect(setup.context.grants.isEmpty()&&setup.context.resolver.calls.get(0).equals(OWNER+":fixture:replace:writable.circ"),"Legacy replacement grant or routing changed");
    } else if(scenario.equals("legacy-reset")) {
      denied(setup);DocumentFixtureBootstrapReceiver receiver=new DocumentFixtureBootstrapReceiver();receiver.onReceive(setup.context,new Intent());
      expect(receiver.getResultCode()==Activity.RESULT_OK&&receiver.getResultData()==null,"Legacy reset result changed");
      expect(new String(Files.readAllBytes(new File(setup.directory(),"writable.circ").toPath()),StandardCharsets.UTF_8).equals(DocumentFixtureProvider.XML),"Legacy reset bytes changed");
      setup.provider.openDocument("writable.circ","r",null);
    } else if(scenario.equals("revoke-grant")) {
      File source=new File(setup.directory(),"writable.circ");byte[] before=Files.readAllBytes(source.toPath());
      DocumentFixtureBootstrapReceiver receiver=new DocumentFixtureBootstrapReceiver();
      Intent request=new Intent(DocumentFixtureBootstrapReceiver.ACTION_REVOKE_CIRCUIT_GRANT);
      Context normal=new Context("thalesmms.logisim.mobile.test",setup.context.files,1001);
      receiver.onReceive(normal,request);
      expect(receiver.getResultCode()==Activity.RESULT_CANCELED&&normal.revoked.isEmpty(),"Normal package revoked a fixture");
      receiver.onReceive(setup.context,request);
      String expected="content://"+OWNER+".documents/document/writable.circ";
      expect(receiver.getResultCode()==Activity.RESULT_OK&&expected.equals(receiver.getResultData()),"Fixture grant result missing");
      expect(setup.context.revoked.equals(java.util.List.of(expected+":3")),"Wrong URI or permissions revoked");
      expect(setup.context.resolver.calls.isEmpty()&&setup.context.grants.isEmpty(),"Revocation touched the provider or made new grants");
      expect(Arrays.equals(before,Files.readAllBytes(source.toPath())),"Fixture bytes changed");
      setup.provider.openDocument("writable.circ","r",null);
    } else throw new IllegalArgumentException(scenario);
  }
}
"""


class FixtureOwnershipTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        stubs = []
        for name, source in STUBS.items():
            path = cls.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            stubs.append(path)
        probe = cls.root / "FixtureOwnershipProbe.java"
        probe.write_text(PROBE)
        cls.classes = cls.root / "classes"
        subprocess.run(["javac", "--release", "17", "-d", str(cls.classes),
                        *map(str, stubs), str(SOURCES / "DocumentFixtureProvider.java"),
                        str(SOURCES / "DocumentFixtureBootstrapReceiver.java"), str(probe)],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def run_scenario(self, scenario):
        subprocess.run(["java", "-cp", str(self.classes), "FixtureOwnershipProbe", scenario],
                       check=True, capture_output=True, text=True)

    def test_owner_writes_and_grants_only_its_new_source_for_reading(self):
        self.run_scenario("prepare")

    def test_owner_preparation_does_not_depend_on_previous_denied_state(self):
        self.run_scenario("denied")

    def test_foreign_uid_cannot_prepare_or_clear_denied(self):
        self.run_scenario("other-uid")

    def test_failed_write_does_not_clear_denied_or_publish_a_uri(self):
        self.run_scenario("write-failure")

    def test_path_escape_rejected_before_provider_mutation(self):
        self.run_scenario("invalid-path")

    def test_missing_xml_rejected_before_provider_mutation(self):
        self.run_scenario("missing-xml")

    def test_new_protocol_rejects_normal_application_package(self):
        self.run_scenario("wrong-owner")

    def test_foreign_provider_response_is_not_granted(self):
        self.run_scenario("foreign-response")

    def test_same_named_requests_have_fresh_uris_and_unchanged_xml(self):
        self.run_scenario("duplicate")

    def test_legacy_xml_broadcast_contract_is_preserved(self):
        self.run_scenario("legacy-xml")

    def test_legacy_reset_broadcast_contract_is_preserved(self):
        self.run_scenario("legacy-reset")

    def test_owner_revokes_only_the_fixture_uri_without_disabling_the_provider(self):
        self.run_scenario("revoke-grant")


if __name__ == "__main__":
    unittest.main()
