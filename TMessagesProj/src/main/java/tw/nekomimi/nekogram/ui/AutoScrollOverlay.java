package tw.nekomimi.nekogram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;

/**
 * NekoMod: tarjeta con cuenta regresiva para pasar al siguiente video en PhotoViewer.
 */
public class AutoScrollOverlay extends FrameLayout {

    private final TextView numberView;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();
    private Runnable onFinish;
    private Runnable onCancel;
    private int secondsLeft;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            secondsLeft--;
            if (secondsLeft <= 0) {
                running = false;
                if (onFinish != null) {
                    onFinish.run();
                }
                return;
            }
            numberView.setText(String.valueOf(secondsLeft));
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    public AutoScrollOverlay(Context context) {
        super(context);
        setClickable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(240, 23, 33, 43));
        bg.setCornerRadius(AndroidUtilities.dp(16));
        setBackground(bg);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(AndroidUtilities.dp(3));
        ringPaint.setColor(0xFF3E88F7);

        // círculo con el número
        FrameLayout circle = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                float inset = ringPaint.getStrokeWidth() / 2f;
                ringRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
                canvas.drawOval(ringRect, ringPaint);
                super.dispatchDraw(canvas);
            }
        };
        circle.setWillNotDraw(false);
        numberView = new TextView(context);
        numberView.setTextColor(Color.WHITE);
        numberView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30);
        numberView.setTypeface(AndroidUtilities.bold());
        numberView.setGravity(Gravity.CENTER);
        circle.addView(numberView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        addView(circle, LayoutHelper.createFrame(72, 72, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 20, 0, 0));

        TextView title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setText(LocaleController.getString(R.string.NekoModVideoNext));
        addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 16, 102, 16, 0));

        TextView cancel = createButton(context, LocaleController.getString(R.string.NekoModVideoCancel), 0xFFB0BEC9);
        cancel.setOnClickListener(v -> {
            stop();
            if (onCancel != null) {
                onCancel.run();
            }
        });
        addView(cancel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 40, Gravity.BOTTOM | Gravity.LEFT, 12, 0, 0, 10));

        TextView watch = createButton(context, LocaleController.getString(R.string.NekoModVideoWatchNow), 0xFF3E88F7);
        watch.setOnClickListener(v -> {
            stop();
            if (onFinish != null) {
                onFinish.run();
            }
        });
        addView(watch, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 40, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 12, 10));
    }

    private TextView createButton(Context context, String text, int color) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextColor(color);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        return button;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(260), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(196), MeasureSpec.EXACTLY));
    }

    /** onFinish: al llegar a 0 o pulsar "Ver ahora". onCancel: al pulsar "Cancelar". */
    public void start(int seconds, Runnable onFinish, Runnable onCancel) {
        stop();
        this.onFinish = onFinish;
        this.onCancel = onCancel;
        this.secondsLeft = Math.max(1, seconds);
        numberView.setText(String.valueOf(secondsLeft));
        running = true;
        AndroidUtilities.runOnUIThread(tick, 1000);
    }

    /** Detiene la cuenta regresiva sin llamar a ningún callback. */
    public void stop() {
        running = false;
        AndroidUtilities.cancelRunOnUIThread(tick);
    }
}
