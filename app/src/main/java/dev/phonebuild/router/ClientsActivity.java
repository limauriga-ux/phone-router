package dev.phonebuild.router;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientsActivity extends Activity {
    private static final String BASE_URL = "http://127.0.0.1:19777";
    private static final long REFRESH_MS = 3000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshLoop = new Runnable() {
        @Override
        public void run() {
            refreshAll();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private TextView statusText;
    private TextView clientsText;
    private TextView blocksText;
    private EditText macInput;
    private EditText ipInput;
    private boolean active;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        refreshAll();
        main.postDelayed(refreshLoop, REFRESH_MS);
    }

    @Override
    protected void onPause() {
        active = false;
        main.removeCallbacks(refreshLoop);
        super.onPause();
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

        TextView title = new TextView(this);
        title.setText("客户端管理");
        title.setTextSize(24);
        title.setTextColor(0xff15201b);
        title.setTypeface(null, 1);
        root.addView(title);

        statusText = new TextView(this);
        statusText.setText("正在刷新...");
        statusText.setTextSize(13);
        statusText.setTextColor(0xff526158);
        root.addView(statusText, marginTop(-1, 4));

        LinearLayout topRow = row();
        topRow.addView(button("返回", v -> finish(), 0xff526158));
        topRow.addView(button("立即刷新", v -> refreshAll(), 0xff197c6b));
        root.addView(topRow, marginTop(-1, 12));

        clientsText = textPanel("正在加载客户端...");
        root.addView(section("已连接客户端", clientsText), marginTop(-1, 12));

        blocksText = textPanel("正在加载黑名单...");
        root.addView(section("黑名单", blocksText), marginTop(-1, 12));

        LinearLayout macPanel = panel();
        macPanel.addView(label("按 MAC 管控"));
        macInput = input("aa:bb:cc:dd:ee:ff");
        macPanel.addView(macInput, marginTop(-1, 6));
        LinearLayout macRow = row();
        macRow.addView(button("拉黑 MAC", v -> withValue(macInput, "/block-mac/"), 0xff8d2f2f));
        macRow.addView(button("移除 MAC", v -> withValue(macInput, "/unblock-mac/"), 0xff197c6b));
        macPanel.addView(macRow, marginTop(-1, 8));
        root.addView(macPanel, marginTop(-1, 12));

        LinearLayout ipPanel = panel();
        ipPanel.addView(label("按 IP 管控"));
        ipInput = input("192.168.43.123");
        ipInput.setInputType(InputType.TYPE_CLASS_PHONE);
        ipPanel.addView(ipInput, marginTop(-1, 6));
        LinearLayout ipRow = row();
        ipRow.addView(button("拉黑 IP", v -> withValue(ipInput, "/block-ip/"), 0xff8d2f2f));
        ipRow.addView(button("移除 IP", v -> withValue(ipInput, "/unblock-ip/"), 0xff197c6b));
        ipPanel.addView(ipRow, marginTop(-1, 8));
        root.addView(ipPanel, marginTop(-1, 12));

        return scroll;
    }

    private LinearLayout section(String title, TextView content) {
        LinearLayout panel = panel();
        panel.addView(label(title));
        panel.addView(content, marginTop(-1, 8));
        return panel;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(14));
        panel.setBackgroundColor(0xffffffff);
        return panel;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(0xff15201b);
        view.setTypeface(null, 1);
        return view;
    }

    private TextView textPanel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(0xff15201b);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackgroundColor(0xffeef4f0);
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

    private void refreshAll() {
        statusText.setText("正在刷新...");
        executor.execute(() -> {
            String clients = request("/clients");
            String blocks = request("/blocks");
            main.post(() -> {
                if (!active) {
                    return;
                }
                clientsText.setText(clients.isEmpty() ? "暂无客户端信息" : clients);
                blocksText.setText(blocks.isEmpty() ? "暂无黑名单信息" : blocks);
                statusText.setText("自动刷新：3 秒");
            });
        });
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
        statusText.setText("正在执行...");
        executor.execute(() -> {
            String result = request(prefix + value);
            String clients = request("/clients");
            String blocks = request("/blocks");
            main.post(() -> {
                if (!active) {
                    return;
                }
                clientsText.setText(clients.isEmpty() ? "暂无客户端信息" : clients);
                blocksText.setText(blocks.isEmpty() ? "暂无黑名单信息" : blocks);
                statusText.setText(result.isEmpty() ? "已完成" : firstLine(result));
            });
        });
    }

    private String firstLine(String text) {
        int end = text.indexOf('\n');
        return end >= 0 ? text.substring(0, end) : text;
    }

    private String request(String path) {
        try {
            return httpGet(BASE_URL + path);
        } catch (Exception e) {
            return "无法连接本机 Root 服务 127.0.0.1:19777。\n\n"
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
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

    private LinearLayout.LayoutParams marginTop(int width, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                width < 0 ? LinearLayout.LayoutParams.MATCH_PARENT : width,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
