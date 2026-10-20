package app.fieldvault;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// Field Vault runs entirely on the phone: the app's page is bundled inside, and notes are saved on the device.
public class MainActivity extends Activity {
  private WebView web;

  @Override protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    web = new WebView(this);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setAllowFileAccess(true);
    s.setTextZoom(100);
    web.setWebViewClient(new WebViewClient());
    setContentView(web);
    if (saved != null) web.restoreState(saved); else web.loadUrl("file:///android_asset/index.html");
  }

  @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

  @Override public void onBackPressed() {
    web.evaluateJavascript("window.fvBack ? window.fvBack() : false", v -> {
      if (!"true".equals(v)) MainActivity.super.onBackPressed();
    });
  }
}
