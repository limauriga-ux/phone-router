package dev.phonebuild.router;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NodeConfigActivity extends Activity {
    private static final String BASE_URL = "http://127.0.0.1:19777";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView infoText;
    private GridLayout nodeGrid;
    private EditText pathInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshAll();
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
        title.setText("节点配置管理");
        title.setTextSize(24);
        title.setTextColor(0xff15201b);
        title.setTypeface(null, 1);
        root.addView(title);

        statusText = new TextView(this);
        statusText.setText("正在读取...");
        statusText.setTextSize(13);
        statusText.setTextColor(0xff526158);
        root.addView(statusText, marginTop(-1, 4));

        LinearLayout topRow = row();
        topRow.addView(button("返回", v -> finish(), 0xff526158));
        topRow.addView(button("刷新", v -> refreshAll(), 0xff197c6b));
        root.addView(topRow, marginTop(-1, 12));

        LinearLayout infoPanel = panel();
        infoPanel.addView(label("当前配置"));
        infoText = textPanel("正在加载...");
        infoPanel.addView(infoText, marginTop(-1, 8));
        root.addView(infoPanel, marginTop(-1, 12));

        LinearLayout nodesPanel = panel();
        nodesPanel.addView(label("切换节点"));
        nodeGrid = new GridLayout(this);
        nodeGrid.setColumnCount(3);
        nodesPanel.addView(nodeGrid, marginTop(-1, 8));
        root.addView(nodesPanel, marginTop(-1, 12));

        LinearLayout importPanel = panel();
        importPanel.addView(label("导入完整 sing-box 配置"));
        TextView hint = new TextView(this);
        hint.setText("把 JSON 放到 /sdcard/Download 或 /data/local/tmp 后输入路径。导入会覆盖全局配置，并重新生成规则模式。");
        hint.setTextSize(12);
        hint.setTextColor(0xff526158);
        importPanel.addView(hint, marginTop(-1, 6));
        pathInput = input("/sdcard/Download/sing-box.json");
        importPanel.addView(pathInput, marginTop(-1, 8));
        LinearLayout importRow = row();
        importRow.addView(button("导入配置", v -> confirmImport(), 0xffa15c18));
        importPanel.addView(importRow, marginTop(-1, 8));
        root.addView(importPanel, marginTop(-1, 12));

        return scroll;
    }

    private void refreshAll() {
        statusText.setText("正在读取...");
        executor.execute(() -> {
            String info = request("/singbox-config-info");
            List<String> nodes = parseNodes(info);
            main.post(() -> {
                infoText.setText(info.isEmpty() ? "暂无配置状态" : info);
                populateNodes(nodes);
                statusText.setText("已刷新");
            });
        });
    }

    private List<String> parseNodes(String info) {
        List<String> nodes = new ArrayList<>();
        boolean inNodes = false;
        for (String line : info.split("\\n")) {
            String trimmed = line.trim();
            if ("nodes:".equals(trimmed)) {
                inNodes = true;
                continue;
            }
            if (inNodes && !trimmed.isEmpty() && !trimmed.contains("=")) {
                nodes.add(trimmed);
            }
        }
        return nodes;
    }

    private void populateNodes(List<String> nodes) {
        nodeGrid.removeAllViews();
        if (nodes.isEmpty()) {
            TextView empty = textPanel("没有读到节点");
            nodeGrid.addView(empty);
            return;
        }
        for (String node : nodes) {
            Button button = button(shortNode(node), v -> setNode(node), 0xff197c6b);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = dp(44);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            nodeGrid.addView(button, params);
        }
    }

    private void setNode(String node) {
        statusText.setText("正在切换 " + shortNode(node) + "...");
        executor.execute(() -> {
            String result = request("/singbox-set-node/" + node);
            String info = request("/singbox-config-info");
            List<String> nodes = parseNodes(info);
            main.post(() -> {
                infoText.setText(info.isEmpty() ? result : info);
                populateNodes(nodes);
                statusText.setText(firstLine(result));
            });
        });
    }

    private void confirmImport() {
        String path = pathInput.getText().toString().trim();
        if (path.isEmpty()) {
            Toast.makeText(this, "请先输入配置文件路径", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!path.matches("[A-Za-z0-9:._/ -]+") || path.contains(" ")) {
            Toast.makeText(this, "路径请使用字母、数字、点、横线、下划线和斜杠", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("导入节点配置")
                .setMessage("将导入并校验这个 sing-box JSON，覆盖当前全局配置：" + path)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (dialog, which) -> importConfig(path))
                .show();
    }

    private void importConfig(String path) {
        statusText.setText("正在导入...");
        executor.execute(() -> {
            String result = request("/singbox-import-config" + path);
            String info = request("/singbox-config-info");
            List<String> nodes = parseNodes(info);
            main.post(() -> {
                infoText.setText(info.isEmpty() ? result : info);
                populateNodes(nodes);
                statusText.setText(firstLine(result));
            });
        });
    }

    private String request(String path) {
        try {
            return httpGet(BASE_URL + path);
        } catch (Exception e) {
            return "无法连接本机 Root 服务 127.0.0.1:19777。\n\n"
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private String firstLine(String text) {
        if (text == null || text.isEmpty()) return "已完成";
        int end = text.indexOf('\n');
        return end >= 0 ? text.substring(0, end) : text;
    }

    private String shortNode(String node) {
        return node.replace("-Hysteria2", "-HY2").replace("-AnyTLS", "-TLS");
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
        edit.setTextSize(15);
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
