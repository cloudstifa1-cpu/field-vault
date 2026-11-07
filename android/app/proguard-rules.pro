# Keep the methods the app page calls (window.FVNative.*).
-keepclassmembers class app.fieldvault.MainActivity$Bridge { @android.webkit.JavascriptInterface <methods>; }
-keepattributes JavascriptInterface
