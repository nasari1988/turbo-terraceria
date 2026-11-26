package tw.nekomimi.nekogram.nekomod.fake;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.LaunchActivity;

import java.math.BigDecimal;
import java.math.RoundingMode;

import tw.nekomimi.nekogram.NekoModConfig;

// NekoMod: calculadora funcional que sirve de disfraz; el código de acceso abre la app
public class CalculatorActivity extends Activity {

    private static final int COLOR_BG = Color.parseColor("#000000");
    private static final int COLOR_BTN = Color.parseColor("#232E3C");
    private static final int COLOR_OP = Color.parseColor("#F5A623");

    private TextView display;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPressOpen = this::openApp;

    private double acc = 0;
    private boolean hasAcc = false;
    private char pendingOp = 0;
    private String entry = "";
    private boolean justEvaluated = false;
    private final StringBuilder typed = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        int pad = dp(12);
        root.setPadding(pad, dp(24), pad, pad);

        display = new TextView(this);
        display.setTextColor(Color.WHITE);
        display.setTextSize(56);
        display.setGravity(Gravity.BOTTOM | Gravity.END);
        display.setSingleLine(true);
        display.setText("0");
        display.setPadding(dp(8), 0, dp(8), dp(12));
        root.addView(display, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f));

        addRow(root, new String[]{"C", "⌫", "÷", "×"}, new float[]{1, 1, 1, 1});
        addRow(root, new String[]{"7", "8", "9", "−"}, new float[]{1, 1, 1, 1});
        addRow(root, new String[]{"4", "5", "6", "+"}, new float[]{1, 1, 1, 1});
        addRow(root, new String[]{"1", "2", "3", "="}, new float[]{1, 1, 1, 1});
        addRow(root, new String[]{"0", "."}, new float[]{3, 1});

        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(longPressOpen);
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void addRow(LinearLayout parent, String[] labels, float[] weights) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            row.addView(makeButton(labels[i]), buttonParams(weights[i]));
        }
        parent.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private LinearLayout.LayoutParams buttonParams(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private View makeButton(final String label) {
        boolean isOp = label.equals("÷") || label.equals("×") || label.equals("−") || label.equals("+") || label.equals("=");
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(26);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(isOp ? COLOR_OP : COLOR_BTN);
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        b.setClickable(true);
        b.setOnClickListener(v -> onKey(label));
        if (label.equals("=")) {
            b.setOnTouchListener((v, ev) -> {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    if (codeHash().length() == 0) {
                        handler.postDelayed(longPressOpen, 3000);
                    }
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    handler.removeCallbacks(longPressOpen);
                }
                return false;
            });
        }
        return b;
    }

    private String codeHash() {
        SharedPreferences sp = getSharedPreferences("nekomodconfig", Context.MODE_PRIVATE);
        String h = sp.getString("calcCodeHash", "");
        return h == null ? "" : h;
    }

    private void openApp() {
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private void onKey(String k) {
        char c = k.charAt(0);
        if (c >= '0' && c <= '9') {
            if (justEvaluated) {
                entry = "";
                justEvaluated = false;
            }
            if (entry.length() >= 15) return;
            entry = entry.equals("0") ? k : entry + k;
            typed.append(k);
            display.setText(entry);
        } else if (k.equals(".")) {
            if (justEvaluated) {
                entry = "";
                justEvaluated = false;
            }
            if (entry.length() == 0) entry = "0.";
            else if (!entry.contains(".")) entry += ".";
            display.setText(entry);
        } else if (k.equals("C")) {
            acc = 0;
            hasAcc = false;
            pendingOp = 0;
            entry = "";
            justEvaluated = false;
            typed.setLength(0);
            display.setText("0");
        } else if (k.equals("⌫")) {
            if (entry.length() > 0 && !justEvaluated) {
                char last = entry.charAt(entry.length() - 1);
                entry = entry.substring(0, entry.length() - 1);
                if (last >= '0' && last <= '9' && typed.length() > 0) typed.setLength(typed.length() - 1);
                display.setText(entry.length() == 0 ? "0" : entry);
            }
        } else if (k.equals("=")) {
            onEquals();
        } else {
            onOperator(c);
        }
    }

    private void onOperator(char op) {
        if (entry.length() > 0) {
            double v = Double.parseDouble(entry);
            if (hasAcc && pendingOp != 0) {
                if (!applyOrError(v)) return;
            } else {
                acc = v;
            }
            hasAcc = true;
            entry = "";
        } else if (!hasAcc) {
            acc = 0;
            hasAcc = true;
        }
        pendingOp = op;
        justEvaluated = false;
        display.setText(format(acc) + " " + op);
    }

    private boolean applyOrError(double v) {
        if (pendingOp == '÷' && v == 0) {
            resetAll();
            display.setText("Error");
            return false;
        }
        acc = compute(acc, pendingOp, v);
        return true;
    }

    private void resetAll() {
        acc = 0;
        hasAcc = false;
        pendingOp = 0;
        entry = "";
        justEvaluated = false;
        typed.setLength(0);
    }

    private static double compute(double a, char op, double b) {
        switch (op) {
            case '+': return a + b;
            case '−': return a - b;
            case '×': return a * b;
            case '÷': return a / b;
            default: return b;
        }
    }

    private void onEquals() {
        String hash = codeHash();
        if (hash.length() > 0 && typed.length() > 0 && hash.equals(NekoModConfig.sha256(typed.toString()))) {
            typed.setLength(0);
            openApp();
            return;
        }
        double result;
        if (entry.length() > 0) {
            double v = Double.parseDouble(entry);
            if (hasAcc && pendingOp != 0) {
                if (!applyOrError(v)) return;
                result = acc;
            } else {
                result = v;
            }
        } else {
            result = acc;
        }
        acc = 0;
        hasAcc = false;
        pendingOp = 0;
        typed.setLength(0);
        entry = format(result);
        justEvaluated = true;
        display.setText(entry);
    }

    private static String format(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "Error";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        String s = new BigDecimal(d).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return s;
    }
}
