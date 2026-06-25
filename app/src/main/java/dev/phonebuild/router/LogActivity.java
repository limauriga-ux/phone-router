package dev.phonebuild.router;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LogActivity extends Activity {
    private static final String BASE_URL = "http://127.0.0.1:19777";
    private static final long REFRESH_MS = 2000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshLoop = new Runnable() {
        @Override
        public void run() {
            refreshLog();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private TextView statusText;
    private TextView logText;
    private ScrollView logScroll;
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
        refreshLog();
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(0xfff7f8f4);

        TextView title = new TextView(this);
        title.setText("代理日志");
        title.setTextSize(24);
        title.setTextColor(0xff15201b);
        title.setTypeface(null, 1);
        root.addView(title);

        statusText = new TextView(this);
        statusText.setText("正在刷新...");
        statusText.setTextSize(13);
        statusText.setTextColor(0xff526158);
        root.addView(statusText, marginTop(-1, 4));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(button("返回", v -> finish(), 0xff526158));
        row.addView(button("立即刷新", v -> refreshLog(), 0xff197c6b));
        root.addView(row, marginTop(-1, 12));

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackgroundColor(0xff15201b);
        logText = new TextView(this);
        logText.setText("暂无日志");
        logText.setTextSize(11);
        logText.setTextColor(0xffeef4f0);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setPadding(dp(12), dp(12), dp(12), dp(12));
        logScroll.addView(logText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(logScroll, marginTop(-1, 12, 1f));

        return root;
    }

    private void refreshLog() {
        statusText.setText("正在刷新...");
        executor.execute(() -> {
            String result;
            try {
                result = httpGet(BASE_URL + "/singbox-log");
            } catch (Exception e) {
                result = "无法连接本机 Root 服务 127.0.0.1:19777。\n\n"
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            String finalResult = result;
            main.post(() -> {
                if (!active) {
                    return;
                }
                logText.setText(finalResult.isEmpty() ? "暂无日志" : finalResult);
                statusText.setText("自动刷新：2 秒");
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            });
        });
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
        return marginTop(width, top, 0f);
    }

    private LinearLayout.LayoutParams marginTop(int width, int top, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                width < 0 ? LinearLayout.LayoutParams.MATCH_PARENT : width,
                weight > 0f ? 0 : LinearLayout.LayoutParams.WRAP_CONTENT,
                weight);
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
