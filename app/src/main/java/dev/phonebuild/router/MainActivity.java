package dev.phonebuild.router;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BASE_URL = "http://127.0.0.1:19777";
    private static final String PREFS = "phone_router_settings";
    private static final String DEFAULT_AP_SSID = "PhoneRouter";
    private static final String DEFAULT_AP_PASS = "phonebuild888";
    private static final String DEFAULT_AP_BAND = "2";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView outputText;
    private TextView apConfigText;
    private TextView rootValue;
    private TextView apValue;
    private TextView proxyValue;
    private TextView clientsValue;
    private TextView nodeValue;
    private TextView accessValue;
    private String apSsid = DEFAULT_AP_SSID;
    private String apPass = DEFAULT_AP_PASS;
    private String apBand = DEFAULT_AP_BAND;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadApSettings();
        setContentView(buildUi());
        call("/summary", true);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xfff7f8f4);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        hero.setBackground(panelBg(0xff15201b, 0xff15201b));
        root.addView(hero);

        TextView title = new TextView(this);
        title.setText("手机软路由");
        title.setTextSize(28);
        title.setTextColor(0xffffffff);
        title.setGravity(Gravity.START);
        title.setTypeface(null, 1);
        hero.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("AP / 代理 / 客户端控制");
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xffc8d6d0);
        hero.addView(subtitle, marginTop(-1, 2));

        statusText = new TextView(this);
        statusText.setText("正在检查服务...");
        statusText.setTextSize(12);
        statusText.setTextColor(0xffdfe8e4);
        hero.addView(statusText, marginTop(-1, 10));

        LinearLayout dashboard = new LinearLayout(this);
        dashboard.setOrientation(LinearLayout.VERTICAL);
        root.addView(dashboard, marginTop(-1, 12));

        LinearLayout statusRowOne = row();
        rootValue = addStatusTile(statusRowOne, "ROOT", "...");
        apValue = addStatusTile(statusRowOne, "热点", "...");
        dashboard.addView(statusRowOne);

        LinearLayout statusRowTwo = row();
        proxyValue = addStatusTile(statusRowTwo, "代理", "...");
        accessValue = addStatusTile(statusRowTwo, "上网", "...");
        dashboard.addView(statusRowTwo, marginTop(-1, 8));

        LinearLayout statusRowThree = row();
        clientsValue = addStatusTile(statusRowThree, "客户端", "...");
        nodeValue = addStatusTile(statusRowThree, "节点", "...");
        dashboard.addView(statusRowThree, marginTop(-1, 8));

        LinearLayout quick = section(root, "常用控制", "状态刷新、Root 服务和上网总开关", true);
        LinearLayout quickRow = row();
        quickRow.addView(button("刷新状态", v -> call("/summary", true), 0xff526158));
        quickRow.addView(button("启动 Root", v -> startRootService(), 0xff197c6b));
        quick.addView(quickRow, marginTop(-1, 8));
        LinearLayout quickStopRow = row();
        quickStopRow.addView(button("暂停上网", v -> call("/pause-all", true), 0xffa15c18));
        quickStopRow.addView(button("恢复上网", v -> call("/resume-all", true), 0xff197c6b));
        quick.addView(quickStopRow, marginTop(-1, 8));

        LinearLayout proxy = section(root, "代理", "模式、内核状态和日志", true);
        LinearLayout proxyModeRow = row();
        proxyModeRow.addView(button("规则模式", v -> call("/proxy-rule", true), 0xff197c6b));
        proxyModeRow.addView(button("全局模式", v -> call("/proxy-global", true), 0xff0e4e44));
        proxy.addView(proxyModeRow, marginTop(-1, 8));
        LinearLayout proxyRow = row();
        proxyRow.addView(button("停止代理", v -> call("/proxy-stop", true), 0xff8d2f2f));
        proxyRow.addView(button("代理状态", v -> call("/singbox-status", true), 0xff526158));
        proxy.addView(proxyRow, marginTop(-1, 8));
        LinearLayout proxyLogRow = row();
        proxyLogRow.addView(button("查看日志", v -> startActivity(new Intent(this, LogActivity.class)), 0xff526158));
        proxy.addView(proxyLogRow, marginTop(-1, 8));

        LinearLayout nodeSection = section(root, "代理节点", "切换三台 VPS 的协议节点", false);
        LinearLayout nodeManageRow = row();
        nodeManageRow.addView(button("节点配置管理", v -> startActivity(new Intent(this, NodeConfigActivity.class)), 0xff526158));
        nodeSection.addView(nodeManageRow, marginTop(-1, 8));
        GridLayout nodeGrid = new GridLayout(this);
        nodeGrid.setColumnCount(3);
        nodeSection.addView(nodeGrid, marginTop(-1, 8));
        addNodeAction(nodeGrid, "DE-HY2", "DE-Hysteria2");
        addNodeAction(nodeGrid, "DE-TUIC", "DE-TUIC");
        addNodeAction(nodeGrid, "DE-TLS", "DE-AnyTLS");
        addNodeAction(nodeGrid, "US-HY2", "US-Hysteria2");
        addNodeAction(nodeGrid, "US-TUIC", "US-TUIC");
        addNodeAction(nodeGrid, "US-TLS", "US-AnyTLS");
        addNodeAction(nodeGrid, "ISP-HY2", "ISP-Hysteria2");
        addNodeAction(nodeGrid, "ISP-TUIC", "ISP-TUIC");
        addNodeAction(nodeGrid, "ISP-TLS", "ISP-AnyTLS");

        LinearLayout hotspot = section(root, "热点与共享", "热点设置、SoftAP 和上游共享", true);
        apConfigText = new TextView(this);
        apConfigText.setTextSize(14);
        apConfigText.setTextColor(0xff15201b);
        apConfigText.setPadding(dp(14), dp(10), dp(14), dp(10));
        apConfigText.setBackground(panelBg(0xffeef4f0, 0xffdce2dd));
        hotspot.addView(apConfigText, marginTop(-1, 8));
        updateApConfigText();
        LinearLayout apRow = row();
        apRow.addView(button("热点设置", v -> showApSettings()));
        apRow.addView(button("开启热点", v -> startAp()));
        hotspot.addView(apRow, marginTop(-1, 8));
        LinearLayout apStopRow = row();
        apStopRow.addView(button("关闭热点", v -> call("/stop-ap", true), 0xff8d2f2f));
        apStopRow.addView(button("共享状态", v -> call("/tether-status", true), 0xff526158));
        hotspot.addView(apStopRow, marginTop(-1, 8));
        LinearLayout tetherRow = row();
        tetherRow.addView(button("开启共享", v -> call("/enable-tether", true), 0xff197c6b));
        tetherRow.addView(button("关闭共享", v -> call("/disable-tether", true), 0xff8d2f2f));
        hotspot.addView(tetherRow, marginTop(-1, 8));

        LinearLayout access = section(root, "连接管理", "客户端查看、黑名单和上网控制", true);
        LinearLayout clientRow = row();
        clientRow.addView(button("客户端管理", v -> startActivity(new Intent(this, ClientsActivity.class)), 0xff526158));
        access.addView(clientRow, marginTop(-1, 8));

        LinearLayout advanced = section(root, "高级", "底层转发规则，排障时再用", false);
        LinearLayout advancedRow = row();
        advancedRow.addView(button("开启 Redir", v -> call("/enable-redir", true), 0xff526158));
        advancedRow.addView(button("开启 TProxy", v -> call("/enable-tproxy", true), 0xff526158));
        advanced.addView(advancedRow, marginTop(-1, 8));
        LinearLayout advancedStopRow = row();
        advancedStopRow.addView(button("关闭规则", v -> call("/disable", true), 0xff8d2f2f));
        advanced.addView(advancedStopRow, marginTop(-1, 8));

        LinearLayout result = section(root, "最近执行", "命令返回和错误信息", false);
        outputText = new TextView(this);
        outputText.setText("执行结果会显示在这里。");
        outputText.setTextSize(12);
        outputText.setTextColor(0xff15201b);
        outputText.setTypeface(android.graphics.Typeface.MONOSPACE);
        outputText.setPadding(dp(14), dp(12), dp(14), dp(12));
        outputText.setBackground(panelBg(0xffeef4f0, 0xffdce2dd));
        result.addView(outputText, marginTop(-1, 8));

        return scroll;
    }

    private void addAction(GridLayout grid, String text, String path, boolean updateStatus) {
        Button button = button(text, v -> call(path, updateStatus));
        addGridButton(grid, button);
    }

    private void addGridButton(GridLayout grid, Button button) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(48);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        grid.addView(button, params);
    }

    private void addNodeAction(GridLayout grid, String text, String node) {
        Button button = button(text, v -> call("/singbox-set-node/" + node, true));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(44);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        grid.addView(button, params);
    }

    private TextView addStatusTile(LinearLayout row, String label, String initial) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        tile.setBackground(panelBg(0xffffffff, 0xffdce2dd));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(11);
        labelView.setTextColor(0xff526158);
        labelView.setTypeface(null, 1);
        tile.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(initial);
        valueView.setTextSize(17);
        valueView.setTextColor(0xff15201b);
        valueView.setTypeface(null, 1);
        valueView.setMinLines(1);
        tile.addView(valueView, marginTop(-1, 2));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        row.addView(tile, params);
        return valueView;
    }

    private LinearLayout section(LinearLayout root, String title, String subtitle, boolean expanded) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(14), dp(12), dp(14), dp(14));
        outer.setBackground(panelBg(0xffffffff, 0xffdce2dd));
        root.addView(outer, marginTop(-1, 12));

        TextView header = new TextView(this);
        header.setText((expanded ? "▾ " : "▸ ") + title);
        header.setTextSize(17);
        header.setTextColor(0xff15201b);
        header.setTypeface(null, 1);
        outer.addView(header);

        TextView hint = new TextView(this);
        hint.setText(subtitle);
        hint.setTextSize(12);
        hint.setTextColor(0xff526158);
        outer.addView(hint, marginTop(-1, 2));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        outer.addView(body);

        header.setOnClickListener(v -> {
            boolean nowVisible = body.getVisibility() == View.VISIBLE;
            body.setVisibility(nowVisible ? View.GONE : View.VISIBLE);
            header.setText((nowVisible ? "▸ " : "▾ ") + title);
        });
        hint.setOnClickListener(v -> header.performClick());
        return body;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(0xff526158);
        view.setTypeface(null, 1);
        return view;
    }

    private EditText input(String hint) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(16);
        edit.setHint(hint);
        edit.setPadding(dp(12), 0, dp(12), 0);
        return edit;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private Button button(String text, View.OnClickListener listener) {
        return button(text, listener, 0xff197c6b);
    }

    private Button button(String text, View.OnClickListener listener, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(0xffffffff);
        button.setTextSize(14);
        button.setBackgroundColor(color);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);
        return button;
    }

    private GradientDrawable panelBg(int color, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams marginTop(int width, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                width < 0 ? LinearLayout.LayoutParams.MATCH_PARENT : width,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private void loadApSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        apSsid = prefs.getString("ap_ssid", DEFAULT_AP_SSID);
        apPass = prefs.getString("ap_pass", DEFAULT_AP_PASS);
        apBand = prefs.getString("ap_band", DEFAULT_AP_BAND);
    }

    private void saveApSettings(String ssid, String pass, String band) {
        apSsid = ssid;
        apPass = pass;
        apBand = band;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString("ap_ssid", apSsid)
                .putString("ap_pass", apPass)
                .putString("ap_band", apBand)
                .apply();
        updateApConfigText();
    }

    private void updateApConfigText() {
        if (apConfigText == null) {
            return;
        }
        apConfigText.setText("名称：" + apSsid + "\n频段：" + bandLabel(apBand) + "\n密码：已隐藏");
    }

    private void showApSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);

        TextView ssidLabel = label("热点名称");
        form.addView(ssidLabel);
        EditText ssid = input(DEFAULT_AP_SSID);
        ssid.setText(apSsid);
        form.addView(ssid, marginTop(-1, 4));

        TextView passLabel = label("热点密码");
        form.addView(passLabel, marginTop(-1, 12));
        EditText pass = input(DEFAULT_AP_PASS);
        pass.setText(apPass);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(pass, marginTop(-1, 4));

        TextView bandLabel = label("频段：2 = 2.4G，5 = 5G，any = 自动");
        form.addView(bandLabel, marginTop(-1, 12));
        EditText band = input(DEFAULT_AP_BAND);
        band.setText(apBand);
        form.addView(band, marginTop(-1, 4));

        new AlertDialog.Builder(this)
                .setTitle("热点设置")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String newSsid = ssid.getText().toString().trim();
                    String newPass = pass.getText().toString().trim();
                    String newBand = band.getText().toString().trim();
                    if (validateApSettings(newSsid, newPass, newBand)) {
                        saveApSettings(newSsid, newPass, newBand);
                        Toast.makeText(this, "热点设置已保存", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private boolean validateApSettings(String ssid, String pass, String band) {
        if (ssid.isEmpty() || pass.length() < 8) {
            Toast.makeText(this, "需要填写热点名称，密码至少 8 位", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!ssid.matches("[A-Za-z0-9:._-]+") || !pass.matches("[A-Za-z0-9:._-]+") || !band.matches("[A-Za-z0-9._-]+")) {
            Toast.makeText(this, "请使用字母、数字、冒号、点、横线和下划线", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!("2".equals(band) || "5".equals(band) || "6".equals(band) || "any".equals(band))) {
            Toast.makeText(this, "频段只能填 2、5、6 或 any", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private String bandLabel(String band) {
        if ("2".equals(band)) return "2.4G";
        if ("5".equals(band)) return "5G";
        if ("6".equals(band)) return "6G";
        if ("any".equals(band)) return "自动";
        return band;
    }

    private void withValue(EditText input, String prefix) {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this, "请先输入内容", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!value.matches("[A-Za-z0-9:._-]+")) {
            Toast.makeText(this, "只能使用字母、数字、冒号、点、横线和下划线", Toast.LENGTH_LONG).show();
            return;
        }
        call(prefix + value, true);
    }

    private void startAp() {
        if (!validateApSettings(apSsid, apPass, apBand)) {
            return;
        }
        call("/start-ap/" + apSsid + "/" + apPass + "/" + apBand, true);
    }

    private void call(String path, boolean updateStatus) {
        statusText.setText("正在执行 " + path + " ...");
        executor.execute(() -> {
            String result;
            try {
                result = httpGet(BASE_URL + path);
            } catch (Exception e) {
                result = "无法连接本机 Root 服务 127.0.0.1:19777。\n\n"
                        + "如果手机已经有 Magisk/KernelSU/APatch su，请点“启动 Root”。\n"
                        + "也可以从电脑启动：\n"
                        + "bin/phone-router start-api\n\n"
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            String finalResult = result;
            main.post(() -> {
                outputText.setText(finalResult);
                if (updateStatus) {
                    statusText.setText(summarize(finalResult));
                    refreshSummary();
                }
            });
        });
    }

    private void refreshSummary() {
        executor.execute(() -> {
            try {
                String summary = httpGet(BASE_URL + "/summary");
                main.post(() -> updateState(summary));
            } catch (Exception ignored) {
            }
        });
    }

    private void startRootService() {
        statusText.setText("正在请求 su 权限...");
        executor.execute(() -> {
            String result;
            try {
                result = installScriptsWithSu();
            } catch (Exception e) {
                result = "su 启动失败。\n\n"
                        + "这台手机需要 Magisk、KernelSU 或 APatch 这类 Root 管理器。\n"
                        + "如果当前只有 adb root，可以继续从电脑启动：\n"
                        + "bin/phone-router start-api\n\n"
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            String finalResult = result;
            main.post(() -> {
                outputText.setText(finalResult);
                statusText.setText(summarize(finalResult));
                call("/summary", true);
            });
        });
    }

    private String installScriptsWithSu() throws Exception {
        File dir = new File(getFilesDir(), "router");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create " + dir);
        }

        File routerd = copyAsset("phone-routerd.sh", dir);
        File api = copyAsset("phone-router-api.sh", dir);
        File handler = copyAsset("phone-router-api-handler.sh", dir);
        File geositeCn = copyAsset("rules/geosite-cn.srs", dir);
        File geoipCn = copyAsset("rules/geoip-cn.srs", dir);

        String command = "cp " + quote(routerd.getAbsolutePath()) + " /data/local/tmp/phone-routerd.sh"
                + " && cp " + quote(api.getAbsolutePath()) + " /data/local/tmp/phone-router-api.sh"
                + " && cp " + quote(handler.getAbsolutePath()) + " /data/local/tmp/phone-router-api-handler.sh"
                + " && mkdir -p /data/local/phone-router/rules"
                + " && cp " + quote(geositeCn.getAbsolutePath()) + " /data/local/phone-router/rules/geosite-cn.srs"
                + " && cp " + quote(geoipCn.getAbsolutePath()) + " /data/local/phone-router/rules/geoip-cn.srs"
                + " && chmod 755 /data/local/tmp/phone-routerd.sh /data/local/tmp/phone-router-api.sh /data/local/tmp/phone-router-api-handler.sh"
                + " && chmod 644 /data/local/phone-router/rules/geosite-cn.srs /data/local/phone-router/rules/geoip-cn.srs"
                + " && /data/local/tmp/phone-router-api.sh start";

        return runSu(command);
    }

    private File copyAsset(String name, File dir) throws Exception {
        File out = new File(dir, name);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create " + parent);
        }
        try (InputStream input = getAssets().open(name);
             OutputStream output = new FileOutputStream(out)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        if (!out.setExecutable(true, true)) {
            throw new IllegalStateException("Cannot make executable: " + out);
        }
        return out;
    }

    private String runSu(String command) throws Exception {
        String su = findSu();
        Process process = new ProcessBuilder(su, "-c", command)
                .redirectErrorStream(true)
                .start();
        String output = readAll(process.getInputStream());
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("su exited " + code + "\n" + output);
        }
        return output.trim().isEmpty() ? "su 命令执行完成" : output.trim();
    }

    private String findSu() {
        String[] candidates = {
                "/debug_ramdisk/su",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/ap/bin/su",
                "su"
        };
        for (String candidate : candidates) {
            if ("su".equals(candidate) || new File(candidate).canExecute()) {
                return candidate;
            }
        }
        return "su";
    }

    private String readAll(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String httpGet(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod("GET");
        StringBuilder builder = new StringBuilder();
        try {
            InputStream input = connection.getResponseCode() >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            readLines(input, builder);
        } catch (IOException e) {
            if (builder.length() == 0) {
                throw e;
            }
        } finally {
            connection.disconnect();
        }
        return builder.toString().trim();
    }

    private void readLines(InputStream input, StringBuilder builder) throws IOException {
        if (input == null) {
            return;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
    }

    private String summarize(String text) {
        if (text.contains("root=") || text.contains("proxy=") || text.contains("ap=")) {
            return "状态已更新";
        }
        String[] lines = text.split("\\n");
        StringBuilder summary = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("device:")
                    || line.startsWith("hotspot interface:")
                    || line.startsWith("enabled ")
                    || line.startsWith("disabled ")
                    || line.startsWith("phone-router API ")
                    || line.startsWith("su command ")
                    || line.startsWith("blocked ")
                    || line.startsWith("unblocked ")
                    || line.startsWith("pause-all")) {
                if (summary.length() > 0) {
                    summary.append('\n');
                }
                summary.append(line);
            }
            if (summary.length() > 180) {
                break;
            }
        }
        return summary.length() == 0 ? "已完成" : summary.toString();
    }

    private void updateState(String text) {
        String root = valueOf(text, "root");
        String ap = valueOf(text, "ap");
        String iface = valueOf(text, "iface");
        String proxy = valueOf(text, "proxy");
        String routeMode = valueOf(text, "route_mode");
        String paused = valueOf(text, "paused");
        String clients = valueOf(text, "clients");
        String node = valueOf(text, "node");

        rootValue.setText(translate(root));
        apValue.setText(translate(ap) + "\n" + iface);
        proxyValue.setText(proxyLabel(proxy, routeMode));
        accessValue.setText(accessLabel(paused));
        clientsValue.setText(clients + " 台");
        nodeValue.setText(shortNode(node));
    }

    private String translate(String value) {
        if ("off".equals(value)) return "关闭";
        if ("on".equals(value)) return "开启";
        if ("enabled".equals(value)) return "已开启";
        if ("disabled".equals(value)) return "已关闭";
        if ("tethered".equals(value)) return "共享中";
        if ("local-only".equals(value)) return "仅本地";
        if ("manual".equals(value)) return "手动";
        if ("android".equals(value)) return "系统";
        if ("no-upstream".equals(value)) return "无上游";
        if ("stopped".equals(value)) return "未监听";
        if ("running".equals(value)) return "运行中";
        if ("missing".equals(value)) return "未安装";
        if ("listening".equals(value)) return "监听中";
        if ("global".equals(value)) return "全局";
        if ("rule".equals(value)) return "规则";
        if ("unknown".equals(value)) return "未知";
        if ("1".equals(value)) return "开";
        if ("0".equals(value)) return "关";
        return value;
    }

    private String accessLabel(String paused) {
        if ("enabled".equals(paused)) return "暂停";
        if ("disabled".equals(paused)) return "允许";
        return translate(paused);
    }

    private String proxyLabel(String proxy, String routeMode) {
        if ("off".equals(proxy)) {
            return "关闭";
        }
        return translate(routeMode);
    }

    private String shortNode(String node) {
        if (node == null || node.isEmpty() || "?".equals(node)) return "?";
        return node.replace("-Hysteria2", "-HY2").replace("-AnyTLS", "-TLS");
    }

    private String valueOf(String text, String key) {
        String prefix = key + "=";
        for (String line : text.split("\\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "?";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
