package app.fieldvault;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Field Vault runs entirely on the phone: the app's page is bundled inside, notes and photos are saved on the device.
public class MainActivity extends Activity {
  private static final int REQ_CAM = 1, REQ_GAL = 2, REQ_FILE = 3, REQ_LOC = 10, REQ_STORE = 11, REQ_CAMPERM = 12;
  private static final int BIG = 1600, THUMB = 320;

  private WebView web;
  private Uri camUri;            // where the camera is writing the photo
  private String photoDoc;       // which note or vault page the photo belongs to
  private ValueCallback<Uri[]> fileCb;
  private String geoOrigin;
  private GeolocationPermissions.Callback geoCb;
  private BufferedWriter out;    // file being saved (backup / export)
  private Uri lastUri;
  private String lastMime;

  @Override protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    WebView.setWebContentsDebuggingEnabled(false); // nobody can plug in and inspect the app's code
    if (saved != null) {
      String u = saved.getString("camUri");
      if (u != null) camUri = Uri.parse(u);
      photoDoc = saved.getString("photoDoc");
    }
    web = new WebView(this);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setAllowFileAccess(true);
    s.setGeolocationEnabled(true);
    s.setTextZoom(100);
    web.addJavascriptInterface(new Bridge(), "FVNative");
    web.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        Uri u = r.getUrl();
        if ("file".equals(u.getScheme())) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException e) { jsToast("No maps app found on this phone"); }
        return true;
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
        if (hasLocation()) { cb.invoke(origin, true, false); return; }
        geoOrigin = origin; geoCb = cb;
        requestPermissions(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }, REQ_LOC);
      }
      @Override public void onPermissionRequest(PermissionRequest r) {
        runOnUiThread(() -> {
          boolean cam = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
          if (cam) r.grant(new String[]{ PermissionRequest.RESOURCE_VIDEO_CAPTURE }); else r.deny();
        });
      }
      @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
        if (fileCb != null) fileCb.onReceiveValue(null);
        fileCb = cb;
        try { startActivityForResult(p.createIntent(), REQ_FILE); }
        catch (Exception e) { fileCb = null; cb.onReceiveValue(null); jsToast("Could not open your files"); }
        return true;
      }
    });
    setContentView(web);
    if (saved != null) web.restoreState(saved);
    if (web.getUrl() == null) web.loadUrl("file:///android_asset/index.html");
  }

  @Override protected void onSaveInstanceState(Bundle o) {
    super.onSaveInstanceState(o);
    web.saveState(o);
    if (camUri != null) o.putString("camUri", camUri.toString());
    if (photoDoc != null) o.putString("photoDoc", photoDoc);
  }

  @Override public void onBackPressed() {
    web.evaluateJavascript("window.fvBack ? window.fvBack() : false", v -> {
      if (!"true".equals(v)) MainActivity.super.onBackPressed();
    });
  }

  private boolean hasLocation() {
    return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  // Android 9 and older need storage permission to save into Pictures / Downloads.
  private boolean needsStorage() {
    if (Build.VERSION.SDK_INT >= 29 || Build.VERSION.SDK_INT < 23) return false;
    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;
    requestPermissions(new String[]{ Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQ_STORE);
    return true;
  }

  @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
    if (req == REQ_LOC && geoCb != null) {
      geoCb.invoke(geoOrigin, hasLocation(), false);
      geoCb = null;
    } else if (req == REQ_CAMPERM) {
      if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startCamera(photoDoc);
      else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
        jsToast("Camera is blocked. Opening settings: tap Permissions, then Camera, then Allow.");
        try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
      } else jsToast("Field Vault needs the camera to take photos. Tap Photo again and choose Allow.");
    } else if (req == REQ_STORE) {
      jsToast("Thanks. Tap the button again.");
    }
  }

  private void jsToast(String msg) {
    runOnUiThread(() -> web.evaluateJavascript("window.fvToast && fvToast(" + JSONObject.quote(msg) + ")", null));
  }

  // ── Photos ──
  private File photoDir() { File d = new File(getFilesDir(), "photos"); d.mkdirs(); return d; }
  private static boolean safeId(String id) { return id != null && id.matches("[a-z0-9]{3,40}"); }
  private static String newId() { return Long.toString(System.currentTimeMillis(), 36) + Long.toString((long) (Math.random() * 1e9), 36); }

  private void startCamera(String doc) {
    photoDoc = doc;
    // Ask for the camera the first time Photo is tapped; the camera opens right after Allow.
    if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{ Manifest.permission.CAMERA }, REQ_CAMPERM);
      return;
    }
    if (needsStorage()) return;
    try {
      ContentValues cv = new ContentValues();
      cv.put(MediaStore.Images.Media.DISPLAY_NAME, "FieldVault_" + System.currentTimeMillis() + ".jpg");
      cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
      if (Build.VERSION.SDK_INT >= 29) cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Field Vault");
      camUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
      Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
      if (i.resolveActivity(getPackageManager()) == null) { dropCamUri(); inAppCamera(); return; }
      i.putExtra(MediaStore.EXTRA_OUTPUT, camUri);
      i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivityForResult(i, REQ_CAM);
    } catch (Exception e) {
      dropCamUri();
      inAppCamera();
    }
  }

  // This phone's camera app won't take photos for other apps, so use Field Vault's own camera screen.
  private void inAppCamera() {
    runOnUiThread(() -> web.evaluateJavascript("window.fvCamera && fvCamera(" + JSONObject.quote(photoDoc == null ? "" : photoDoc) + ")", null));
  }

  private void startGallery(String doc) {
    photoDoc = doc;
    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
    i.setType("image/*");
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    try { startActivityForResult(Intent.createChooser(i, "Choose photos"), REQ_GAL); }
    catch (Exception e) { jsToast("Could not open your photos"); }
  }

  private void dropCamUri() {
    if (camUri != null) { try { getContentResolver().delete(camUri, null, null); } catch (Exception ignored) {} }
    camUri = null;
  }

  @Override protected void onActivityResult(int req, int code, Intent data) {
    super.onActivityResult(req, code, data);
    if (req == REQ_FILE) {
      if (fileCb != null) fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(code, data));
      fileCb = null;
    } else if (req == REQ_CAM) {
      if (code == RESULT_OK && camUri != null) { List<Uri> l = new ArrayList<>(); l.add(camUri); importPhotos(l, photoDoc); camUri = null; }
      else dropCamUri();
    } else if (req == REQ_GAL && code == RESULT_OK && data != null) {
      List<Uri> l = new ArrayList<>();
      ClipData c = data.getClipData();
      if (c != null) for (int i = 0; i < c.getItemCount(); i++) l.add(c.getItemAt(i).getUri());
      else if (data.getData() != null) l.add(data.getData());
      importPhotos(l, photoDoc);
    }
  }

  // Shrinks each photo (keeps the phone's memory free), saves it inside the app, then tells the page.
  private void importPhotos(List<Uri> uris, String doc) {
    if (uris.isEmpty()) return;
    jsToast(uris.size() == 1 ? "Saving photo…" : "Saving " + uris.size() + " photos…");
    new Thread(() -> {
      int bad = 0;
      for (Uri u : uris) {
        String id = newId();
        if (saveScaled(u, id)) addPending(doc, id); else bad++;
      }
      if (bad > 0) jsToast(bad == 1 ? "One photo could not be read" : bad + " photos could not be read");
      runOnUiThread(() -> web.evaluateJavascript("window.fvPull && fvPull()", null));
    }).start();
  }

  private synchronized void addPending(String doc, String id) {
    SharedPreferences p = getSharedPreferences("fv", MODE_PRIVATE);
    try {
      JSONArray a = new JSONArray(p.getString("pending", "[]"));
      a.put(new JSONObject().put("doc", doc == null ? "" : doc).put("id", id).put("t", System.currentTimeMillis()));
      p.edit().putString("pending", a.toString()).commit();
    } catch (Exception ignored) {}
  }

  private synchronized String takePending() {
    SharedPreferences p = getSharedPreferences("fv", MODE_PRIVATE);
    String s = p.getString("pending", "[]");
    p.edit().remove("pending").commit();
    return s;
  }

  private boolean saveScaled(Uri u, String id) {
    ContentResolver cr = getContentResolver();
    try {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      try (InputStream in = cr.openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
      if (o.outWidth <= 0) return false;
      int rot = 0;
      if (Build.VERSION.SDK_INT >= 24) {
        try (InputStream in = cr.openInputStream(u)) {
          int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
          rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) {}
      }
      Bitmap b;
      BitmapFactory.Options o2 = new BitmapFactory.Options();
      o2.inSampleSize = sample(o.outWidth, o.outHeight, BIG);
      try (InputStream in = cr.openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o2); }
      if (b == null) return false;
      return writeBoth(b, rot, id);
    } catch (Throwable e) { return false; }
  }

  private static int sample(int w, int h, int max) {
    int s = 1;
    while (Math.max(w, h) / (s * 2) >= max) s *= 2;
    return s;
  }

  private boolean writeBoth(Bitmap b, int rot, String id) throws Exception {
    Bitmap big = fit(b, BIG, rot);
    if (big != b) b.recycle();
    writeJpeg(big, new File(photoDir(), id + ".jpg"), 80);
    Bitmap th = fit(big, THUMB, 0);
    writeJpeg(th, new File(photoDir(), id + "_t.jpg"), 70);
    if (th != big) th.recycle();
    big.recycle();
    return true;
  }

  private static Bitmap fit(Bitmap b, int max, int rot) {
    float k = Math.min(1f, (float) max / Math.max(b.getWidth(), b.getHeight()));
    if (k >= 1f && rot == 0) return b;
    Matrix m = new Matrix();
    m.postScale(k, k);
    if (rot != 0) m.postRotate(rot);
    return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
  }

  private static void writeJpeg(Bitmap b, File f, int q) throws Exception {
    try (OutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, q, o); }
  }

  private static byte[] readAll(File f) throws Exception {
    try (InputStream in = new FileInputStream(f); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
      byte[] buf = new byte[16384]; int n;
      while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
      return o.toByteArray();
    }
  }

  // ── Saving files to Downloads/Field Vault ──
  private String beginFile(String name, String mime) {
    closeOut();
    try {
      OutputStream os = openDownload(name, mime, "");
      out = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), 65536);
      return "";
    } catch (Exception e) {
      return "Could not save the file: " + e.getMessage();
    }
  }

  // Saves a picture (or any binary file) into Downloads/Field Vault/<sub>.
  private String saveBytes(String name, String mime, String b64, String sub) {
    try (OutputStream os = openDownload(name, mime, sub)) {
      os.write(Base64.decode(b64, Base64.DEFAULT));
      return "Downloads › Field Vault" + (sub.isEmpty() ? "" : " › " + sub);
    } catch (Exception e) {
      return "!Could not save the picture: " + e.getMessage();
    }
  }

  private OutputStream openDownload(String name, String mime, String sub) throws Exception {
    if (!name.matches("[A-Za-z0-9._\\- ]{1,80}") || !sub.matches("[A-Za-z ]{0,20}")) throw new Exception("bad file name");
    String folder = "Field Vault" + (sub.isEmpty() ? "" : "/" + sub);
    {
      OutputStream os;
      if (Build.VERSION.SDK_INT >= 29) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, mime);
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder);
        lastUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        os = getContentResolver().openOutputStream(lastUri);
      } else {
        if (needsStorage()) throw new Exception("allow storage access, then try again");
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder);
        d.mkdirs();
        os = new FileOutputStream(new File(d, name));
        lastUri = null;
      }
      lastMime = mime;
      return os;
    }
  }

  private void closeOut() {
    if (out != null) { try { out.close(); } catch (Exception ignored) {} out = null; }
  }

  private void shareLast() {
    if (lastUri == null) { jsToast("Open your Downloads folder, Field Vault, to send the file."); return; }
    Intent i = new Intent(Intent.ACTION_SEND);
    i.setType(lastMime == null ? "*/*" : lastMime);
    i.putExtra(Intent.EXTRA_STREAM, lastUri);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try { startActivity(Intent.createChooser(i, "Send a copy")); } catch (Exception e) { jsToast("No app found to send it with"); }
  }

  // Everything the page can ask the app to do (window.FVNative in the page).
  class Bridge {
    @JavascriptInterface public String photoUrl(String id, boolean full) {
      if (!safeId(id)) return "";
      File f = new File(photoDir(), id + (full ? ".jpg" : "_t.jpg"));
      if (!f.exists()) f = new File(photoDir(), id + ".jpg");
      return f.exists() ? Uri.fromFile(f).toString() : "";
    }
    @JavascriptInterface public String photoData(String id) {
      if (!safeId(id)) return "";
      try { return Base64.encodeToString(readAll(new File(photoDir(), id + ".jpg")), Base64.NO_WRAP); } catch (Exception e) { return ""; }
    }
    @JavascriptInterface public boolean putPhoto(String id, String b64) {
      if (!safeId(id)) return false;
      try {
        byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
        try (OutputStream o = new FileOutputStream(new File(photoDir(), id + ".jpg"))) { o.write(bytes); }
        BitmapFactory.Options op = new BitmapFactory.Options();
        op.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, op);
        op.inJustDecodeBounds = false;
        op.inSampleSize = sample(op.outWidth, op.outHeight, THUMB);
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, op);
        if (b != null) { Bitmap th = fit(b, THUMB, 0); writeJpeg(th, new File(photoDir(), id + "_t.jpg"), 70); if (th != b) th.recycle(); b.recycle(); }
        return true;
      } catch (Throwable e) { return false; }
    }
    @JavascriptInterface public void delPhoto(String id) {
      if (!safeId(id)) return;
      new File(photoDir(), id + ".jpg").delete();
      new File(photoDir(), id + "_t.jpg").delete();
    }
    @JavascriptInterface public String pendingPhotos() { return takePending(); }
    @JavascriptInterface public void takePhoto(String doc) { runOnUiThread(() -> startCamera(doc)); }
    @JavascriptInterface public void pickPhotos(String doc) { runOnUiThread(() -> startGallery(doc)); }
    @JavascriptInterface public String fileBegin(String name, String mime) { return beginFile(name, mime); }
    @JavascriptInterface public void fileWrite(String s) {
      try { if (out != null) out.write(s); } catch (Exception e) { closeOut(); }
    }
    @JavascriptInterface public String saveB64(String name, String mime, String b64, String sub) { return saveBytes(name, mime, b64, sub == null ? "" : sub); }
    @JavascriptInterface public String fileEnd() {
      if (out == null) return "!Could not finish saving the file";
      try { out.close(); out = null; return "Downloads › Field Vault"; } catch (Exception e) { out = null; return "!Could not finish saving the file"; }
    }
    @JavascriptInterface public void shareLast() { runOnUiThread(MainActivity.this::shareLast); }
  }
}
