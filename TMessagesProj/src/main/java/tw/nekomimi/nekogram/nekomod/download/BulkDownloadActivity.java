package tw.nekomimi.nekogram.nekomod.download;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

import tw.nekomimi.nekogram.NekoModConfig;
import tw.nekomimi.nekogram.nekomod.download.BulkDownloadManager.Entry;

/** NekoMod: pantalla de descarga masiva de un chat o canal. */
public class BulkDownloadActivity extends BaseFragment implements BulkDownloadManager.Listener {

    private final long dialogId;
    private BulkDownloadManager manager;

    // datos
    private ArrayList<Entry> entries = new ArrayList<>();
    private boolean scanning;
    private boolean scanError;
    private int scanFound;
    private BulkDownloadManager.ScanTask scanTask;
    private int typeMask;
    private long fromMs;
    private long toMs;
    private long lastMsgId = -1;
    private int dbPending;
    private ArrayList<Entry> selected = new ArrayList<>();
    private ArrayList<Entry> toDownload = new ArrayList<>();
    private ArrayList<Entry> newEntries = new ArrayList<>();
    private int refreshToken;
    private String finishedText;
    private boolean wasRunning;
    private int lastTotal, lastDone, lastFailed;

    // vistas
    private final TextCheckCell[] typeCells = new TextCheckCell[BulkDownloadManager.TYPE_LIST.length];
    private TextInfoPrivacyCell statusCell;
    private TextSettingsCell fromCell;
    private TextSettingsCell toCell;
    private TextCheckCell wifiCell;
    private TextCheckCell syncCell;
    private TextView startButton;
    private TextView updateButton;
    private TextView pendingText;
    private LinearLayout progressBox;
    private TextView progressText;
    private ProgressBar progressBar;
    private TextView pauseButton;
    private TextView cancelButton;
    private LinearLayout optionsBox;

    public BulkDownloadActivity(long dialogId) {
        super();
        this.dialogId = dialogId;
    }

    private static int[] typeLabels() {
        return new int[]{R.string.NekoModDlVideos, R.string.NekoModDlPhotos, R.string.NekoModDlGifs,
                R.string.NekoModDlAudio, R.string.NekoModDlDocs, R.string.NekoModDlMusic};
    }

    private int accent() {
        return Theme.getColor(Theme.key_featuredStickers_addButton);
    }

    private TextView makeButton(Context context, boolean primary) {
        TextView b = new TextView(context);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(AndroidUtilities.bold());
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setSingleLine(false);
        b.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        int color = accent();
        if (primary) {
            b.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), color, ColorUtils.setAlphaComponent(color, 190)));
        } else {
            b.setTextColor(color);
            b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), ColorUtils.setAlphaComponent(color, 36), ColorUtils.setAlphaComponent(color, 80)));
        }
        return b;
    }

    private void addHeader(Context context, LinearLayout root, String text) {
        HeaderCell h = new HeaderCell(context);
        h.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        h.setText(text);
        root.addView(h, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    @Override
    public View createView(Context context) {
        manager = BulkDownloadManager.get(currentAccount);
        typeMask = BulkDownloadManager.getTypes(dialogId);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NekoModDlTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = scroll;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // ---- progreso / estado de la cola (arriba)
        progressBox = new LinearLayout(context);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        progressBox.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        progressText = new TextView(context);
        progressText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        progressText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        progressBox.addView(progressText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setProgressTintList(ColorStateList.valueOf(accent()));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent(), 50)));
        progressBox.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8, 0, 8, 0, 8));
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        pauseButton = makeButton(context, true);
        pauseButton.setOnClickListener(v -> onPauseResume());
        row.addView(pauseButton, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 6, 0));
        cancelButton = makeButton(context, false);
        cancelButton.setText(LocaleController.getString(R.string.NekoModDlCancel));
        cancelButton.setOnClickListener(v -> onCancel());
        row.addView(cancelButton, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 6, 0, 0, 0));
        progressBox.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(progressBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        pendingText = new TextView(context);
        pendingText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        pendingText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        pendingText.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        root.addView(pendingText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ---- opciones (se ocultan mientras corre la descarga)
        optionsBox = new LinearLayout(context);
        optionsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(optionsBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addHeader(context, optionsBox, LocaleController.getString(R.string.NekoModDlWhat));
        int[] labels = typeLabels();
        for (int i = 0; i < typeCells.length; i++) {
            final int type = BulkDownloadManager.TYPE_LIST[i];
            TextCheckCell c = new TextCheckCell(context);
            c.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            c.setTextAndValueAndCheck(LocaleController.getString(labels[i]), "", (typeMask & type) != 0, false, i < typeCells.length - 1);
            c.setOnClickListener(v -> {
                typeMask ^= type;
                BulkDownloadManager.setTypes(dialogId, typeMask);
                recompute();
            });
            typeCells[i] = c;
            optionsBox.addView(c, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        statusCell = new TextInfoPrivacyCell(context);
        statusCell.setOnClickListener(v -> {
            if (scanError && !scanning) startScan();
        });
        optionsBox.addView(statusCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addHeader(context, optionsBox, LocaleController.getString(R.string.NekoModDlRange));
        fromCell = new TextSettingsCell(context);
        fromCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fromCell.setOnClickListener(v -> pickDate(true));
        optionsBox.addView(fromCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        toCell = new TextSettingsCell(context);
        toCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        toCell.setOnClickListener(v -> pickDate(false));
        optionsBox.addView(toCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextInfoPrivacyCell gap = new TextInfoPrivacyCell(context);
        gap.setText("");
        optionsBox.addView(gap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addHeader(context, optionsBox, LocaleController.getString(R.string.NekoModDlOptions));
        TextCheckCell skipCell = new TextCheckCell(context);
        skipCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        skipCell.setTextAndValueAndCheck(LocaleController.getString(R.string.NekoModDlSkipDownloaded), LocaleController.getString(R.string.NekoModDlSkipAbout), true, false, true);
        skipCell.setEnabled(false);
        optionsBox.addView(skipCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        wifiCell = new TextCheckCell(context);
        wifiCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        wifiCell.setTextAndCheck(LocaleController.getString(R.string.NekoModDlWifiOnly), BulkDownloadManager.getWifiOnly(dialogId), true);
        wifiCell.setOnClickListener(v -> {
            wifiCell.setChecked(!wifiCell.isChecked());
            BulkDownloadManager.setWifiOnly(dialogId, wifiCell.isChecked());
        });
        optionsBox.addView(wifiCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        syncCell = new TextCheckCell(context);
        syncCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        syncCell.setTextAndCheck(LocaleController.getString(R.string.NekoModDlSynced), NekoModConfig.parseIds(NekoModConfig.syncedDialogs).contains(dialogId), false);
        syncCell.setOnClickListener(v -> {
            syncCell.setChecked(!syncCell.isChecked());
            applySynced(syncCell.isChecked());
        });
        optionsBox.addView(syncCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextInfoPrivacyCell folderInfo = new TextInfoPrivacyCell(context);
        folderInfo.setText(LocaleController.formatString(R.string.NekoModDlFolderNote, manager.chatFolderName(dialogId)));
        optionsBox.addView(folderInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ---- botones grandes
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(24));
        startButton = makeButton(context, true);
        startButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        startButton.setOnClickListener(v -> onStart());
        buttons.addView(startButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));
        updateButton = makeButton(context, false);
        updateButton.setOnClickListener(v -> onUpdate());
        buttons.addView(updateButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 10, 0, 0));
        optionsBox.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        manager.addListener(this);
        loadDbState();
        startScan();
        updateViews();
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (manager != null) manager.removeListener(this);
        if (scanTask != null) scanTask.cancel();
        refreshToken++;
    }

    @Override
    public void onJobChanged(long id) {
        if (id != dialogId) return;
        BulkDownloadManager.Job j = manager.getJob(dialogId);
        if (j != null) {
            wasRunning = true;
            lastTotal = j.total;
            lastDone = j.done;
            lastFailed = j.failed;
        } else if (wasRunning) {
            wasRunning = false;
            finishedText = LocaleController.formatString(R.string.NekoModDlFinished, Math.max(lastDone - lastFailed, 0), lastTotal, lastFailed);
            loadDbState();
        }
        updateViews();
    }

    // ------------------------------------------------------------------ datos

    private void loadDbState() {
        manager.getChatInfo(dialogId, info -> {
            lastMsgId = info[0];
            recompute();
        });
        manager.getPendingCount(dialogId, n -> {
            dbPending = n;
            updateViews();
        });
    }

    private void startScan() {
        if (scanTask != null) scanTask.cancel();
        scanning = true;
        scanError = false;
        scanFound = 0;
        entries = new ArrayList<>();
        updateViews();
        scanTask = manager.scan(dialogId, BulkDownloadManager.ALL_TYPES, 0, new BulkDownloadManager.ScanCallback() {
            @Override
            public void onProgress(int found) {
                scanFound = found;
                updateStatus();
            }

            @Override
            public void onDone(ArrayList<Entry> result, boolean error) {
                scanning = false;
                scanError = error;
                entries = result;
                recompute();
            }
        });
    }

    private boolean inDateRange(Entry e) {
        if (fromMs > 0 && e.date < fromMs / 1000L) return false;
        if (toMs > 0 && e.date > toMs / 1000L + 86399L) return false;
        return true;
    }

    /** Recalcula selección, nuevos y (en la base) cuáles faltan por bajar. */
    private void recompute() {
        selected = new ArrayList<>();
        newEntries = new ArrayList<>();
        for (Entry e : entries) {
            if ((typeMask & e.type) == 0) continue;
            if (inDateRange(e)) selected.add(e);
            if (lastMsgId >= 0 && e.msgId > lastMsgId) newEntries.add(e);
        }
        final int token = ++refreshToken;
        manager.filterPending(dialogId, selected, list -> {
            if (token != refreshToken) return;
            toDownload = list;
            updateViews();
        });
        updateViews();
    }

    private static long sumSize(ArrayList<Entry> list) {
        long s = 0;
        for (Entry e : list) s += e.size;
        return s;
    }

    // ------------------------------------------------------------------ UI

    private void updateStatus() {
        if (statusCell == null) return;
        if (scanning) {
            statusCell.setText(LocaleController.formatString(R.string.NekoModDlScanning, scanFound));
        } else if (scanError) {
            statusCell.setText(LocaleController.getString(R.string.NekoModDlScanError));
        } else {
            statusCell.setText("");
        }
    }

    private void updateViews() {
        if (fragmentView == null) return;
        BulkDownloadManager.Job job = manager.getJob(dialogId);
        boolean running = job != null && job.running;

        // conteos por tipo
        int[] labels = typeLabels();
        int[] counts = new int[typeCells.length];
        long[] sizes = new long[typeCells.length];
        for (Entry e : entries) {
            for (int i = 0; i < typeCells.length; i++) {
                if (BulkDownloadManager.TYPE_LIST[i] == e.type) {
                    counts[i]++;
                    sizes[i] += e.size;
                }
            }
        }
        for (int i = 0; i < typeCells.length; i++) {
            String value = scanning && entries.isEmpty() ? "…" : LocaleController.formatString(R.string.NekoModDlCountFormat, counts[i], AndroidUtilities.formatFileSize(sizes[i]));
            typeCells[i].setTextAndValueAndCheck(LocaleController.getString(labels[i]), value, (typeMask & BulkDownloadManager.TYPE_LIST[i]) != 0, false, i < typeCells.length - 1);
        }
        updateStatus();

        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        String any = LocaleController.getString(R.string.NekoModDlAnyDate);
        fromCell.setTextAndValue(LocaleController.getString(R.string.NekoModDlFrom), fromMs > 0 ? df.format(new java.util.Date(fromMs)) : any, true);
        toCell.setTextAndValue(LocaleController.getString(R.string.NekoModDlTo), toMs > 0 ? df.format(new java.util.Date(toMs)) : any, false);

        // botón principal
        if (scanning) {
            startButton.setText(LocaleController.getString(R.string.NekoModDlStart).replace("%1$d", "…").replace("%2$s", "…"));
            startButton.setEnabled(false);
            startButton.setAlpha(0.5f);
        } else if (toDownload.isEmpty()) {
            startButton.setText(LocaleController.getString(R.string.NekoModDlNothing));
            startButton.setEnabled(false);
            startButton.setAlpha(0.5f);
        } else {
            startButton.setText(LocaleController.formatString(R.string.NekoModDlStart, toDownload.size(), AndroidUtilities.formatFileSize(sumSize(toDownload))));
            startButton.setEnabled(true);
            startButton.setAlpha(1f);
        }
        // botón actualizar (solo si el chat ya tuvo descargas)
        if (lastMsgId >= 0 && !scanning && !scanError) {
            updateButton.setVisibility(View.VISIBLE);
            if (newEntries.isEmpty()) {
                updateButton.setText(LocaleController.getString(R.string.NekoModDlUpdateNone));
                updateButton.setEnabled(false);
                updateButton.setAlpha(0.5f);
            } else {
                updateButton.setText(LocaleController.formatString(R.string.NekoModDlUpdateNew, newEntries.size(), AndroidUtilities.formatFileSize(sumSize(newEntries))));
                updateButton.setEnabled(true);
                updateButton.setAlpha(1f);
            }
        } else {
            updateButton.setVisibility(View.GONE);
        }

        // progreso / pendientes de una sesión anterior
        if (running) {
            progressBox.setVisibility(View.VISIBLE);
            optionsBox.setVisibility(View.GONE);
            pendingText.setVisibility(View.GONE);
            int total = Math.max(job.total, 1);
            progressBar.setMax(total);
            progressBar.setProgress(Math.min(job.done, total));
            if (job.paused) {
                int res = job.waitingWifi ? R.string.NekoModDlWaitWifi : (job.fetchError ? R.string.NekoModDlFetchError : R.string.NekoModDlPaused);
                progressText.setText(LocaleController.formatString(res, job.done, job.total));
                pauseButton.setText(LocaleController.getString(R.string.NekoModDlResume));
            } else {
                progressText.setText(LocaleController.formatString(R.string.NekoModDlProgress, job.done, job.total));
                pauseButton.setText(LocaleController.getString(R.string.NekoModDlPause));
            }
        } else {
            optionsBox.setVisibility(View.VISIBLE);
            if (dbPending > 0) {
                // cola guardada de antes: solo se reanuda si el usuario toca Reanudar
                progressBox.setVisibility(View.VISIBLE);
                pendingText.setVisibility(View.GONE);
                progressBar.setMax(Math.max(dbPending, 1));
                progressBar.setProgress(0);
                progressText.setText(LocaleController.formatString(R.string.NekoModDlPendingFound, dbPending));
                pauseButton.setText(LocaleController.getString(R.string.NekoModDlResume));
            } else {
                progressBox.setVisibility(View.GONE);
                pendingText.setVisibility(finishedText != null ? View.VISIBLE : View.GONE);
                pendingText.setText(finishedText != null ? finishedText : "");
            }
        }
    }

    // ------------------------------------------------------------------ acciones

    private void applySynced(boolean add) {
        Set<Long> ids = NekoModConfig.parseIds(NekoModConfig.syncedDialogs);
        if (add) ids.add(dialogId); else ids.remove(dialogId);
        NekoModConfig.setString("syncedDialogs", NekoModConfig.joinIds(ids));
    }

    private void toast(String s) {
        Context c = getParentActivity();
        if (c != null) Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    private void launch(ArrayList<Entry> list) {
        BulkDownloadManager.setTypes(dialogId, typeMask);
        final boolean wifiOnly = wifiCell.isChecked();
        BulkDownloadManager.setWifiOnly(dialogId, wifiOnly);
        finishedText = null;
        manager.enqueue(dialogId, list, pending -> {
            if (pending <= 0) {
                toast(LocaleController.getString(R.string.NekoModDlNothing));
                loadDbState();
                return;
            }
            manager.start(dialogId, wifiOnly, null);
            loadDbState();
        });
    }

    private void onStart() {
        if (toDownload.isEmpty()) return;
        launch(selected);
    }

    private void onUpdate() {
        if (newEntries.isEmpty() || getParentActivity() == null) return;
        final ArrayList<Entry> list = new ArrayList<>(newEntries);
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(LocaleController.getString(R.string.NekoModDlUpdate));
        b.setMessage(LocaleController.formatString(R.string.NekoModDlUpdateConfirm, list.size(), AndroidUtilities.formatFileSize(sumSize(list))));
        b.setPositiveButton(LocaleController.getString(R.string.NekoModDlOk), (d, w) -> launch(list));
        b.setNegativeButton(LocaleController.getString(R.string.NekoModDlCancel), null);
        showDialog(b.create());
    }

    private void onPauseResume() {
        BulkDownloadManager.Job job = manager.getJob(dialogId);
        if (job != null && job.running) {
            if (job.paused) manager.resume(dialogId); else manager.pause(dialogId);
        } else if (dbPending > 0) {
            manager.start(dialogId, wifiCell.isChecked(), null);
        }
        updateViews();
    }

    private void onCancel() {
        manager.cancel(dialogId);
        dbPending = 0;
        finishedText = null;
        wasRunning = false;
        loadDbState();
        updateViews();
    }

    private void pickDate(final boolean from) {
        if (getParentActivity() == null) return;
        Calendar cal = Calendar.getInstance();
        long cur = from ? fromMs : toMs;
        if (cur > 0) cal.setTimeInMillis(cur);
        DatePickerDialog dlg = new DatePickerDialog(getParentActivity(), (view, year, month, day) -> {
            Calendar c = Calendar.getInstance();
            c.set(year, month, day, 0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (from) fromMs = c.getTimeInMillis(); else toMs = c.getTimeInMillis();
            recompute();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        dlg.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, LocaleController.getString(R.string.NekoModDlClear), (d, w) -> {
            if (from) fromMs = 0; else toMs = 0;
            recompute();
        });
        dlg.show();
    }
}
