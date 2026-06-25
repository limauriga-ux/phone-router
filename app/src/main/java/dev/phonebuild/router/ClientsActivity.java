package dev.phonebuild.router;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private TextView countText;
    private TextView pauseText;
    private TextView macBlocksText;
    private TextView ipBlocksText;
    private TextView rawText;
    private LinearLayout clientsList;
    private LinearLayout rawBody;
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

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        countText = summaryTile(summary, "在线设备", "...");
        pauseText = summaryTile(summary, "上网控制", "...");
        root.addView(summary, marginTop(-1, 12));

        LinearLayout clientsPanel = panel();
        clientsPanel.addView(label("已连接客户端"));
        clientsList = new LinearLayout(this);
        clientsList.setOrientation(LinearLayout.VERTICAL);
        clientsPanel.addView(clientsList, marginTop(-1, 8));
        root.addView(clientsPanel, marginTop(-1, 12));

        LinearLayout blocksPanel = panel();
        blocksPanel.addView(label("黑名单"));
        LinearLayout blockRow = row();
        macBlocksText = smallInfo("MAC\n-");
        ipBlocksText = smallInfo("IP\n-");
        blockRow.addView(macBlocksText);
        blockRow.addView(ipBlocksText);
        blocksPanel.addView(blockRow, marginTop(-1, 8));
        root.addView(blocksPanel, marginTop(-1, 12));

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

        LinearLayout rawPanel = collapsiblePanel("原始详情", false);
        rawText = textPanel("正在加载...");
        rawBody.addView(rawText, marginTop(-1, 8));
        root.addView(rawPanel, marginTop(-1, 12));

        return scroll;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(14));
        panel.setBackground(panelBg(0xffffffff, 0xffdce2dd));
        return panel;
    }

    private LinearLayout collapsiblePanel(String title, boolean expanded) {
        LinearLayout outer = panel();
        TextView header = label((expanded ? "▾ " : "▸ ") + title);
        outer.addView(header);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        outer.addView(body);
        header.setOnClickListener(v -> {
            boolean visible = body.getVisibility() == View.VISIBLE;
            body.setVisibility(visible ? View.GONE : View.VISIBLE);
            header.setText((visible ? "▸ " : "▾ ") + title);
        });
        rawBody = body;
        return outer;
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

    private TextView smallInfo(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(0xff15201b);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(panelBg(0xffeef4f0, 0xffdce2dd));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        view.setLayoutParams(params);
        return view;
    }

    private TextView summaryTile(LinearLayout row, String label, String value) {
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
        valueView.setText(value);
        valueView.setTextSize(20);
        valueView.setTextColor(0xff15201b);
        valueView.setTypeface(null, 1);
        tile.addView(valueView, marginTop(-1, 2));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        row.addView(tile, params);
        return valueView;
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
                renderClients(clients);
                renderBlocks(blocks);
                rawText.setText((clients.isEmpty() ? "暂无客户端信息" : clients) + "\n\n" + (blocks.isEmpty() ? "暂无黑名单信息" : blocks));
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
                renderClients(clients);
                renderBlocks(blocks);
                rawText.setText((clients.isEmpty() ? "暂无客户端信息" : clients) + "\n\n" + (blocks.isEmpty() ? "暂无黑名单信息" : blocks));
                statusText.setText(result.isEmpty() ? "已完成" : firstLine(result));
            });
        });
    }

    private void renderClients(String text) {
        Map<String, ClientInfo> clients = parseClients(text);
        clientsList.removeAllViews();
        countText.setText(clients.size() + " 台");
        if (clients.isEmpty()) {
            clientsList.addView(emptyText("暂无在线客户端"));
            return;
        }
        int index = 1;
        for (ClientInfo client : clients.values()) {
            clientsList.addView(clientCard(index++, client), marginTop(-1, clientsList.getChildCount() == 0 ? 0 : 8));
        }
    }

    private Map<String, ClientInfo> parseClients(String text) {
        Map<String, ClientInfo> clients = new LinkedHashMap<>();
        Set<String> currentClientMacs = new HashSet<>();
        boolean inArp = false;
        boolean inNames = false;
        boolean inTetherClients = false;
        for (String line : text.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("tethering clients:")) {
                inTetherClients = true;
                continue;
            }
            if (trimmed.startsWith("device names:")) {
                inTetherClients = false;
                inNames = true;
                inArp = false;
                continue;
            }
            if (trimmed.startsWith("neighbor table:")) {
                inTetherClients = false;
                inNames = false;
                inArp = false;
            }
            if (trimmed.startsWith("arp table:")) {
                inTetherClients = false;
                inNames = false;
                inArp = true;
                continue;
            }
            if (inTetherClients) {
                parseCurrentClientLine(clients, currentClientMacs, trimmed);
                continue;
            }
            if (inNames && !trimmed.isEmpty()) {
                parseLeaseNameLine(clients, currentClientMacs, trimmed);
                continue;
            }
            if (inArp && trimmed.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+\\s+.*")) {
                String[] parts = trimmed.split("\\s+");
                String mac = parts.length >= 4 ? parts[3].toLowerCase(Locale.US) : "";
                if (parts.length >= 4 && currentClientMacs.contains(mac)) {
                    ClientInfo client = clientFor(clients, parts[3]);
                    client.ipv4 = parts[0];
                    client.state = "0x0".equals(parts[2]) ? "失效" : "在线";
                }
                continue;
            }
            if (trimmed.contains(" lladdr ")) {
                String[] parts = trimmed.split("\\s+");
                String ip = parts.length > 0 ? parts[0] : "";
                String mac = "";
                String state = parts.length > 0 ? parts[parts.length - 1] : "";
                for (int i = 0; i < parts.length - 1; i++) {
                    if ("lladdr".equals(parts[i])) {
                        mac = parts[i + 1];
                        break;
                    }
                }
                String key = mac.toLowerCase(Locale.US);
                if (!mac.isEmpty() && currentClientMacs.contains(key)) {
                    ClientInfo client = clientFor(clients, mac);
                    if (ip.contains(":")) {
                        client.ipv6Count++;
                    } else if (client.ipv4.isEmpty()) {
                        client.ipv4 = ip;
                    }
                    if (!state.isEmpty()) {
                        client.state = translateNeighborState(state);
                    }
                }
            }
        }
        return clients;
    }

    private void parseCurrentClientLine(Map<String, ClientInfo> clients, Set<String> currentClientMacs, String line) {
        int searchFrom = 0;
        while (searchFrom < line.length()) {
            int clientAt = line.indexOf("client: /", searchFrom);
            if (clientAt < 0) {
                break;
            }
            int ipStart = clientAt + "client: /".length();
            int ipEnd = line.indexOf(' ', ipStart);
            int macStart = ipEnd < 0 ? -1 : line.indexOf('(', ipEnd);
            int macEnd = macStart < 0 ? -1 : line.indexOf(')', macStart);
            if (ipEnd > ipStart && macStart >= 0 && macEnd > macStart) {
                String ip = line.substring(ipStart, ipEnd);
                String mac = line.substring(macStart + 1, macEnd).toLowerCase(Locale.US);
                currentClientMacs.add(mac);
                ClientInfo client = clientFor(clients, mac);
                client.ipv4 = ip;
                if ("未知".equals(client.state)) {
                    client.state = "在线";
                }
                searchFrom = macEnd + 1;
            } else {
                searchFrom = clientAt + 1;
            }
        }
    }

    private void parseLeaseNameLine(Map<String, ClientInfo> clients, Set<String> currentClientMacs, String line) {
        String[] parts = line.split("\\s+", 4);
        String mac = parts.length >= 1 ? parts[0].toLowerCase(Locale.US) : "";
        if (parts.length >= 3 && currentClientMacs.contains(mac)) {
            ClientInfo client = clientFor(clients, parts[0]);
            if (client.ipv4.isEmpty() && parts[1].matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                client.ipv4 = parts[1];
            }
            if (!"*".equals(parts[2]) && !"-".equals(parts[2])) {
                client.name = parts[2];
                client.nameSource = parts.length >= 4 && parts[3].contains("dhcp-log") ? "DHCP 日志" : "DHCP 租约";
            }
        }
    }

    private ClientInfo clientFor(Map<String, ClientInfo> clients, String mac) {
        String key = mac.toLowerCase(Locale.US);
        ClientInfo existing = clients.get(key);
        if (existing != null) {
            return existing;
        }
        ClientInfo created = new ClientInfo();
        created.mac = key;
        created.name = "";
        created.nameSource = "";
        created.state = "未知";
        created.ipv4 = "";
        clients.put(key, created);
        return created;
    }

    private View clientCard(int index, ClientInfo client) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(panelBg(0xffeef4f0, 0xffdce2dd));

        TextView title = new TextView(this);
        String name = client.name.isEmpty() ? "未广播设备名" : client.name;
        title.setText(name + " · " + client.state);
        title.setTextSize(15);
        title.setTextColor(0xff15201b);
        title.setTypeface(null, 1);
        card.addView(title);

        TextView detail = new TextView(this);
        String ipv4 = client.ipv4.isEmpty() ? "未分配" : client.ipv4;
        String source = client.nameSource.isEmpty() ? unknownIdentityReason(client.mac) : client.nameSource;
        detail.setText("编号  " + index + "\nIPv4  " + ipv4 + "\nMAC   " + client.mac + "\nIPv6  " + client.ipv6Count + " 个地址\n识别  " + source);
        detail.setTextSize(12);
        detail.setTextColor(0xff526158);
        detail.setTypeface(android.graphics.Typeface.MONOSPACE);
        card.addView(detail, marginTop(-1, 6));
        return card;
    }

    private TextView emptyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(0xff526158);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(panelBg(0xffeef4f0, 0xffdce2dd));
        return view;
    }

    private void renderBlocks(String text) {
        macBlocksText.setText("MAC\n" + parseBlockSection(text, "blocked MACs:"));
        ipBlocksText.setText("IP\n" + parseBlockSection(text, "blocked IPs:"));
        pauseText.setText(text.contains("pause-all: enabled") ? "暂停" : "允许");
    }

    private String parseBlockSection(String text, String header) {
        StringBuilder builder = new StringBuilder();
        boolean capture = false;
        for (String line : text.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.equals(header)) {
                capture = true;
                continue;
            }
            if (capture && (trimmed.endsWith(":") || trimmed.startsWith("pause-all:"))) {
                break;
            }
            if (capture && !trimmed.isEmpty()) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(trimmed);
            }
        }
        return builder.length() == 0 ? "无" : builder.toString();
    }

    private String translateNeighborState(String state) {
        if ("REACHABLE".equals(state)) return "在线";
        if ("STALE".equals(state)) return "最近在线";
        if ("DELAY".equals(state) || "PROBE".equals(state)) return "探测中";
        if ("FAILED".equals(state)) return "失效";
        return state;
    }

    private String unknownIdentityReason(String mac) {
        if (isLocalRandomMac(mac)) {
            return "随机 MAC，且未广播 DHCP 主机名";
        }
        return "未从 DHCP/系统租约获取到名称";
    }

    private boolean isLocalRandomMac(String mac) {
        try {
            int first = Integer.parseInt(mac.substring(0, 2), 16);
            return (first & 0x02) != 0;
        } catch (Exception e) {
            return false;
        }
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

    private static class ClientInfo {
        String mac;
        String name;
        String nameSource;
        String ipv4;
        String state;
        int ipv6Count;
    }
}
