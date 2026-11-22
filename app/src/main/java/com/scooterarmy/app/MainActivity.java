package com.scooterarmy.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

public class MainActivity extends Activity {

    private WebView webView;
    private LocationManager locMgr;
    private boolean listening = false;
    private GnssStatus.Callback gnssCallback;

    // Satellite count (used in fix / in view) pushed to the page for the signal bars.
    private void pushSatCount(GnssStatus s) {
        int used = 0, total = s.getSatelliteCount();
        for (int i = 0; i < total; i++) if (s.usedInFix(i)) used++;
        final int u = used, tot = total;
        js(String.format(Locale.US, "window.onSatCount&&window.onSatCount(%d,%d)", u, tot));
    }

    // Raw GPS-provider fixes (no fused-provider smoothing) pushed straight into the page.
    // Assisted (network) fixes: coarse position from cell/Wi-Fi while GPS is still locking.
    // Never used for speed or timing — only to show an assisted status instantly.
    private final LocationListener netListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            double acc = l.hasAccuracy() ? l.getAccuracy() : 999;
            js(String.format(Locale.US,
                "window.onNetworkFix&&window.onNetworkFix(%.7f,%.7f,%.1f)",
                l.getLatitude(), l.getLongitude(), acc));
        }
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
        @Override public void onStatusChanged(String p, int status, Bundle extras) {}
    };

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { pushFix(l); }
        @Override public void onProviderEnabled(String p)  { js("window.onNativeProvider&&window.onNativeProvider(true)"); }
        @Override public void onProviderDisabled(String p) { js("window.onNativeProvider&&window.onNativeProvider(false)"); }
        @Override public void onStatusChanged(String p, int status, Bundle extras) {}
    };

    /** Marker so the page knows native GPS is available. */
    private static class GpsBridge {
        @JavascriptInterface public boolean available() { return true; }
    }

    private void js(String code) {
        if (webView != null) webView.evaluateJavascript(code, null);
    }

    private void pushFix(Location l) {
        double speed    = l.hasSpeed() ? l.getSpeed() : -1;
        double speedAcc = l.hasSpeedAccuracy() ? l.getSpeedAccuracyMetersPerSecond() : -1;
        double acc      = l.hasAccuracy() ? l.getAccuracy() : 999;
        double tMs      = l.getElapsedRealtimeNanos() / 1e6;   // GPS fix time, monotonic clock
        js(String.format(Locale.US,
            "window.onNativeFix&&window.onNativeFix(%.7f,%.7f,%.3f,%.1f,%.3f,%.3f)",
            l.getLatitude(), l.getLongitude(), speed, acc, speedAcc, tMs));
    }

    private void startLocation() {
        if (listening) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            locMgr = (LocationManager) getSystemService(LOCATION_SERVICE);
            locMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locListener);
            try { locMgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, netListener); } catch (Exception ignored) {}
            gnssCallback = new GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(GnssStatus s) { pushSatCount(s); }
            };
            locMgr.registerGnssStatusCallback(gnssCallback);
            listening = true;
            if (!locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER))
                js("window.onNativeProvider&&window.onNativeProvider(false)");
        } catch (SecurityException e) {
            listening = false;
        }
    }

    private void stopLocation() {
        if (listening && locMgr != null) {
            locMgr.removeUpdates(locListener);
            try { locMgr.removeUpdates(netListener); } catch (Exception ignored) {}
            if (gnssCallback != null) locMgr.unregisterGnssStatusCallback(gnssCallback);
            listening = false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startLocation();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setGeolocationDatabasePath(getFilesDir().getPath());
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false);
            }
        });

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            }, 1);
        }

        webView.addJavascriptInterface(new GpsBridge(), "AndroidGPS");
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        startLocation();
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        stopLocation();
    }
}
