package tw.nekomimi.nekogram.nekomod.download;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;

import tw.nekomimi.nekogram.NekoModConfig;

/** NekoMod: sincronización nocturna (red sin límite, cargando, cada 24 h) de los chats sincronizados. */
public class NightlySyncJob extends JobService {

    public static final int JOB_ID = 0x4E4D01;

    /** Programa o cancela el trabajo periódico según enable. */
    public static void schedule(Context ctx, boolean enable) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!enable) {
                js.cancel(JOB_ID);
                return;
            }
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, NightlySyncJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setRequiresCharging(true)
                    .setPeriodic(24L * 60 * 60 * 1000)
                    .setPersisted(true)
                    .build();
            js.schedule(info);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!NekoModConfig.nightlySync) {
            return false;
        }
        final ArrayList<Long> ids = new ArrayList<>(NekoModConfig.parseIds(NekoModConfig.syncedDialogs));
        if (ids.isEmpty()) {
            return false;
        }
        final int account = UserConfig.selectedAccount;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (!UserConfig.getInstance(account).isClientActivated()) {
                    jobFinished(params, false);
                    return;
                }
                BulkDownloadManager.get(account).updateDialogs(ids, true, () -> jobFinished(params, false));
            } catch (Throwable e) {
                FileLog.e(e);
                jobFinished(params, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // el sistema detuvo el trabajo: lo pendiente sigue en la cola persistente
        return true;
    }
}
