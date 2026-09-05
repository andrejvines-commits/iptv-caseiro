package br.com.iptvcaseiro;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "iptv_caseiro";
    private static final String SERVER_URL = "server_url";
    private static final String APK_NAME = "iptv-caseiro.apk";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private FrameLayout root;
    private LinearLayout browserLayout;
    private WebView webView;
    private View fullScreenView;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private long downloadId = -1;
    private File pendingApk;
    private boolean waitingInstallPermission;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (completedId != downloadId) return;
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId))) {
                if (cursor != null && cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        installDownloadedApk();
                    } else {
                        message("Não foi possível baixar a atualização.");
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        createInterface();
        registerDownloadReceiver();

        String savedUrl = preferences.getString(SERVER_URL, "");
        if (savedUrl.isEmpty()) {
            showServerDialog(true);
        } else {
            loadServer(savedUrl);
        }
    }

    private void createInterface() {
        root = new FrameLayout(this);
        browserLayout = new LinearLayout(this);
        browserLayout.setOrientation(LinearLayout.VERTICAL);
        browserLayout.setBackgroundColor(Color.rgb(16, 19, 21));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(6), dp(8), dp(6));
        toolbar.setBackgroundColor(Color.rgb(16, 19, 21));

        TextView title = new TextView(this);
        title.setText("IPTV Caseiro");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, 1);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button serverButton = toolbarButton("Servidor");
        serverButton.setOnClickListener(view -> showServerDialog(false));
        toolbar.addView(serverButton);

        Button updateButton = toolbarButton("Atualizar");
        updateButton.setOnClickListener(view -> checkForUpdates(updateButton));
        toolbar.addView(updateButton);

        webView = new WebView(this);
        configureWebView();
        browserLayout.addView(toolbar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        browserLayout.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(browserLayout, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private Button toolbarButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.rgb(48, 56, 61));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        params.setMarginStart(dp(6));
        button.setLayoutParams(params);
        return button;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setBuiltInZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                Uri server = Uri.parse(preferences.getString(SERVER_URL, ""));
                String scheme = target.getScheme();
                if (("http".equals(scheme) || "https".equals(scheme)) &&
                    server.getHost() != null && server.getHost().equalsIgnoreCase(target.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, target));
                } catch (Exception error) {
                    message("Nenhum aplicativo conseguiu abrir este link.");
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullScreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullScreenView = view;
                fullScreenCallback = callback;
                browserLayout.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                hideFullScreenVideo();
            }
        });
    }

    private void showServerDialog(boolean required) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("http://192.168.1.20:5000");
        input.setText(preferences.getString(SERVER_URL, ""));
        int padding = dp(20);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(padding, 0, padding, 0);
        container.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Endereço do servidor")
            .setMessage("Informe o endereço mostrado pelo Flask no computador. Os dois aparelhos devem estar na mesma rede.")
            .setView(container)
            .setPositiveButton("Conectar", null)
            .setNegativeButton(required ? "Sair" : "Cancelar", (value, which) -> {
                if (required) finish();
            })
            .create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String normalized = normalizeServerUrl(input.getText().toString());
            if (normalized == null) {
                input.setError("Use um endereço HTTP ou HTTPS válido.");
                return;
            }
            preferences.edit().putString(SERVER_URL, normalized).apply();
            dialog.dismiss();
            loadServer(normalized);
        }));
        dialog.setCanceledOnTouchOutside(!required);
        dialog.show();
    }

    private String normalizeServerUrl(String value) {
        String trimmed = value.trim();
        if (!trimmed.matches("(?i)^https?://.+")) return null;
        Uri uri = Uri.parse(trimmed);
        if (uri.getHost() == null) return null;
        return trimmed.replaceAll("/+$", "") + "/";
    }

    private void loadServer(String url) {
        webView.loadUrl(url);
    }

    private void checkForUpdates(Button button) {
        String repository = BuildConfig.GITHUB_REPOSITORY;
        if (repository.startsWith("SEU_USUARIO/")) {
            new AlertDialog.Builder(this)
                .setTitle("Repositório não configurado")
                .setMessage("Publique o projeto pelo GitHub Actions para gravar automaticamente o nome do repositório no APK.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        button.setEnabled(false);
        button.setText("Verificando…");
        executor.execute(() -> {
            try {
                URL api = new URL("https://api.github.com/repos/" + repository + "/releases/latest");
                HttpURLConnection connection = (HttpURLConnection) api.openConnection();
                connection.setConnectTimeout(12_000);
                connection.setReadTimeout(12_000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "IPTV-Caseiro-Android");
                if (connection.getResponseCode() != 200) {
                    throw new IllegalStateException("GitHub respondeu " + connection.getResponseCode());
                }
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject release = new JSONObject(body.toString());
                String latestVersion = release.getString("tag_name").replaceFirst("^[vV]", "");
                String apkUrl = findApkUrl(release.getJSONArray("assets"));
                runOnUiThread(() -> showUpdateResult(latestVersion, apkUrl));
            } catch (Exception error) {
                runOnUiThread(() -> message("Não foi possível verificar atualizações. Verifique a internet e tente novamente."));
            } finally {
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    button.setText("Atualizar");
                });
            }
        });
    }

    private String findApkUrl(JSONArray assets) throws Exception {
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.getJSONObject(index);
            if (APK_NAME.equalsIgnoreCase(asset.optString("name"))) {
                return asset.getString("browser_download_url");
            }
        }
        return "";
    }

    private void showUpdateResult(String latestVersion, String apkUrl) {
        if (!isNewer(latestVersion, BuildConfig.VERSION_NAME)) {
            new AlertDialog.Builder(this)
                .setTitle("Aplicativo atualizado")
                .setMessage("Você já usa a versão mais recente (" + BuildConfig.VERSION_NAME + ").")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        if (apkUrl.isEmpty()) {
            message("A versão " + latestVersion + " existe, mas não contém " + APK_NAME + ".");
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Atualização disponível")
            .setMessage("Versão instalada: " + BuildConfig.VERSION_NAME + "\nNova versão: " + latestVersion)
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Baixar e instalar", (dialog, which) -> downloadUpdate(apkUrl))
            .show();
    }

    private boolean isNewer(String candidate, String current) {
        List<Integer> candidateParts = versionParts(candidate);
        List<Integer> currentParts = versionParts(current);
        int size = Math.max(candidateParts.size(), currentParts.size());
        for (int index = 0; index < size; index++) {
            int left = index < candidateParts.size() ? candidateParts.get(index) : 0;
            int right = index < currentParts.size() ? currentParts.get(index) : 0;
            if (left != right) return left > right;
        }
        return false;
    }

    private List<Integer> versionParts(String version) {
        List<Integer> result = new ArrayList<>();
        for (String part : version.split("[^0-9]+")) {
            if (!part.isEmpty()) result.add(Integer.parseInt(part));
        }
        return result;
    }

    private void downloadUpdate(String apkUrl) {
        pendingApk = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME);
        if (pendingApk.exists() && !pendingApk.delete()) {
            message("Não foi possível substituir o instalador anterior.");
            return;
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Atualização do IPTV Caseiro")
            .setDescription("Baixando nova versão")
            .setMimeType(MimeTypeMap.getSingleton().getMimeTypeFromExtension("apk"))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, APK_NAME);
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        downloadId = manager.enqueue(request);
        message("Download iniciado. A instalação será aberta quando terminar.");
    }

    private void installDownloadedApk() {
        if (pendingApk == null || !pendingApk.isFile()) {
            message("O arquivo da atualização não foi encontrado.");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !getPackageManager().canRequestPackageInstalls()) {
            waitingInstallPermission = true;
            new AlertDialog.Builder(this)
                .setTitle("Permitir instalação")
                .setMessage("Na próxima tela, autorize o IPTV Caseiro a instalar aplicativos desconhecidos e volte para continuar.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Abrir configuração", (dialog, which) -> startActivity(
                    new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()))))
                .show();
            return;
        }
        waitingInstallPermission = false;
        Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".files", pendingApk);
        Intent install = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(install);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingInstallPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            getPackageManager().canRequestPackageInstalls()) {
            installDownloadedApk();
        }
    }

    private void registerDownloadReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    private void hideFullScreenVideo() {
        if (fullScreenView == null) return;
        root.removeView(fullScreenView);
        fullScreenView = null;
        browserLayout.setVisibility(View.VISIBLE);
        if (fullScreenCallback != null) fullScreenCallback.onCustomViewHidden();
        fullScreenCallback = null;
    }

    private void message(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (fullScreenView != null) {
            hideFullScreenVideo();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(downloadReceiver);
        webView.destroy();
        executor.shutdownNow();
        super.onDestroy();
    }
}
