package tw.nekomimi.nekogram.nekomod.ocr;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.List;

/**
 * NekoMod: hoja inferior con las líneas detectadas por OCR; tocar una línea la copia.
 */
public final class OcrResultSheet {

    private static final int ACCENT = 0xFF3E88F7;

    private OcrResultSheet() {
    }

    public static void show(Context context, List<TextRecognizerHelper.Line> lines) {
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1F1F1F);
        float r = AndroidUtilities.dp(16);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NekoModOcrTitle));
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setPadding(AndroidUtilities.dp(4), 0, 0, AndroidUtilities.dp(8));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final StringBuilder all = new StringBuilder();
        for (TextRecognizerHelper.Line line : lines) {
            if (all.length() > 0) {
                all.append('\n');
            }
            all.append(line.text);
            final String text = line.text;
            TextView tv = new TextView(context);
            tv.setText(text);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(16);
            tv.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
            GradientDrawable lb = new GradientDrawable();
            lb.setColor(0x2E3E88F7); // rgba(62,136,247,0.18)
            lb.setStroke(AndroidUtilities.dp(1), ACCENT);
            lb.setCornerRadius(AndroidUtilities.dp(6));
            tv.setBackground(lb);
            tv.setOnClickListener(v -> copy(context, text));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = AndroidUtilities.dp(6);
            list.addView(tv, lp);
        }
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button copyAll = new Button(context);
        copyAll.setText(LocaleController.getString(R.string.NekoModOcrCopyAll));
        copyAll.setOnClickListener(v -> copy(context, all.toString()));
        Button close = new Button(context);
        close.setText(LocaleController.getString(R.string.NekoModOcrClose));
        close.setOnClickListener(v -> dialog.dismiss());
        bar.addView(copyAll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(close, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y * 0.6f));
            w.setGravity(Gravity.BOTTOM);
        }
        dialog.show();
    }

    private static void copy(Context context, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("label", text));
            Toast.makeText(context, LocaleController.getString(R.string.NekoModOcrCopied), Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }
}
