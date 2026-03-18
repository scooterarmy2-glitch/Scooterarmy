package com.scooterarmy.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int LOC_REQ = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            setContentView(R.layout.activity_main);

            webView = findViewById(R.id.webview);
            if (webView == null) return;

            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setLoadsImagesAutomatically(true);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);

            try { s.setGeolocationEnabled(true); s.setGeolocationDatabasePath(getFilesDir().getPath()); } catch (Exception e) {}
            try { s.setAllowFileAccessFromFileURLs(true); s.setAllowUniversalAccessFromFileURLs(true); } catch (Exception e) {}
            try { s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); } catch (Exception e) {}

            webView.setWebViewClient(new WebViewClient());
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                    try { cb.invoke(origin, true, false); } catch (Exception e) {}
                }
            });

            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{ Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }, LOC_REQ);
                }
            } catch (Exception e) {}

            webView.loadUrl("file:///android_asset/index.html");

        } catch (Exception e) {
            try {
                if (webView != null) {
                    webView.getSettings().setJavaScriptEnabled(true);
                    webView.getSettings().setDomStorageEnabled(true);
                    webView.loadUrl("file:///android_asset/index.html");
                }
            } catch (Exception e2) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (webView != null) webView.onResume();
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        } catch (Exception e) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { if (webView != null) webView.onPause(); } catch (Exception e) {}
    }

    @Override
    public void onBackPressed() {}
}
