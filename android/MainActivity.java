package local.codex.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;

public final class MainActivity extends Activity {
    private String token;
    private String base;
    private static final String RUN = "com.termux.permission.RUN_COMMAND";
    private static final int PICK = 1;
    private static final int TERMUX_PERMISSION = 2;
    private final Handler handler = new Handler();
    private WebView web;
    private ValueCallback<Uri[]> picker;
    private TextView status;
    private boolean checking;
    private boolean loaded;
    private int attempts;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        token = localToken();
        base = "http://127.0.0.1:41739/" + token + "/";
        getWindow().setStatusBarColor(0xff14171d);
        getWindow().setNavigationBarColor(0xff111318);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 24, 32, 24);
        layout.setBackgroundColor(0xff111318);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(17);
        status.setText("Conectando ao Codex no Ubuntu…");
        Button retry = new Button(this);
        retry.setText("Iniciar ambiente");
        retry.setOnClickListener(view -> startBackend());
        layout.addView(status);
        layout.addView(retry);
        setContentView(layout);
        probe(true);
    }

    private String localToken() {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String value = prefs.getString("token", null);
        if (value != null) return value;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder encoded = new StringBuilder(64);
        for (byte valueByte : bytes) {
            encoded.append(Character.forDigit((valueByte & 255) >>> 4, 16));
            encoded.append(Character.forDigit(valueByte & 15, 16));
        }
        value = encoded.toString();
        prefs.edit().putString("token", value).apply();
        return value;
    }

    private void startBackend() {
        if (checkSelfPermission(RUN) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{RUN}, TERMUX_PERMISSION);
            return;
        }
        Intent intent = new Intent("com.termux.RUN_COMMAND");
        intent.setClassName("com.termux", "com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", LocalConfig.LAUNCHER);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{token});
        try {
            if (startService(intent) == null) throw new IllegalStateException();
            status.setText("Iniciando o Ubuntu…");
            attempts = 0;
            probe(false);
        } catch (RuntimeException error) {
            status.setText("Não foi possível iniciar pelo Termux. Confira a permissão Executar comandos.");
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != TERMUX_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startBackend();
        else status.setText("Autorize Executar comandos nas permissões do Codex Mobile e tente de novo.");
    }

    private void probe(boolean launchOnFailure) {
        if (checking || loaded || isFinishing()) return;
        checking = true;
        new Thread(() -> {
            boolean ok = false;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(base + "api/state").openConnection();
                connection.setConnectTimeout(650);
                connection.setReadTimeout(650);
                ok = connection.getResponseCode() == 200;
            } catch (Exception ignored) {
                // The service may still be starting.
            } finally {
                if (connection != null) connection.disconnect();
            }
            final boolean reachable = ok;
            runOnUiThread(() -> {
                checking = false;
                if (isFinishing() || isDestroyed()) return;
                if (reachable) openChat();
                else if (launchOnFailure) startBackend();
                else if (++attempts < 24) handler.postDelayed(() -> probe(false), 750);
                else status.setText("O Ubuntu ainda não respondeu. Verifique a instalação no Termux e tente de novo.");
            });
        }, "codex-mobile-probe").start();
    }

    private void openChat() {
        loaded = true;
        web = new WebView(this);
        web.setBackgroundColor(0xff111318);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.addJavascriptInterface(new ClipboardBridge(), "AndroidApp");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return route(Uri.parse(url));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return route(request.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (picker != null) picker.onReceiveValue(null);
                picker = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(intent, PICK); }
                catch (RuntimeException error) {
                    picker.onReceiveValue(null);
                    picker = null;
                }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl(base);
    }

    private boolean route(Uri uri) {
        if (uri.toString().equals(base)) return false;
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
            catch (RuntimeException ignored) { }
        }
        return true;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || picker == null) return;
        Uri[] uris = null;
        if (result == RESULT_OK && data != null) {
            ClipData clips = data.getClipData();
            if (clips != null) {
                uris = new Uri[clips.getItemCount()];
                for (int i = 0; i < uris.length; i++) uris[i] = clips.getItemAt(i).getUri();
            } else if (data.getData() != null) uris = new Uri[]{data.getData()};
        }
        picker.onReceiveValue(uris);
        picker = null;
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (picker != null) picker.onReceiveValue(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private final class ClipboardBridge {
        @JavascriptInterface public void restart() {
            runOnUiThread(() -> startBackend());
        }

        @JavascriptInterface public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("Codex", text));
            });
        }
    }
}
