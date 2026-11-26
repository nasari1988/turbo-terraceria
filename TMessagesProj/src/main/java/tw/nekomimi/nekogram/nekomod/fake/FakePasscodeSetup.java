package tw.nekomimi.nekogram.nekomod.fake;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import tw.nekomimi.nekogram.NekoModConfig;

// NekoMod: configuración del código falso
public class FakePasscodeSetup {

    public static void show(BaseFragment fragment) {
        if (fragment == null) return;
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        if (SharedConfig.passcodeHash == null || SharedConfig.passcodeHash.length() == 0) {
            toast(activity, activity.getString(R.string.NekoModFakeNeedPasscode));
            return;
        }
        showDialog(activity);
    }

    public static void clear() {
        NekoModConfig.setString("fakePasscodeHash", "");
        NekoModConfig.fakePasscodeHash = "";
        NekoModConfig.fakeModeActive = false;
    }

    private static EditText newField(Context ctx, String hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
        return e;
    }

    private static void showDialog(final Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        final EditText first = newField(activity, activity.getString(R.string.NekoModFakeEnter));
        final EditText second = newField(activity, activity.getString(R.string.NekoModFakeConfirm));
        layout.addView(first, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(second, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(activity.getString(R.string.NekoModFakeTitle));
        builder.setView(layout);
        builder.setNegativeButton(activity.getString(R.string.NekoModFakeCancel), null);
        builder.setPositiveButton(activity.getString(R.string.NekoModFakeSave), (dialog, which) -> {
            String a = first.getText().toString();
            String b = second.getText().toString();
            String error = null;
            if (!a.matches("[0-9]{4,8}")) {
                error = activity.getString(R.string.NekoModFakeInvalidLength);
            } else if (SharedConfig.passcodeType == SharedConfig.PASSCODE_TYPE_PIN && a.length() != 4) {
                error = activity.getString(R.string.NekoModFakePinLength);
            } else if (!a.equals(b)) {
                error = activity.getString(R.string.NekoModFakeMismatch);
            } else if (SharedConfig.checkPasscode(a)) {
                error = activity.getString(R.string.NekoModFakeSameAsReal);
            }
            if (error != null) {
                toast(activity, error);
                showDialog(activity);
                return;
            }
            String hash = NekoModConfig.sha256(a);
            NekoModConfig.setString("fakePasscodeHash", hash);
            NekoModConfig.fakePasscodeHash = hash;
            toast(activity, activity.getString(R.string.NekoModFakeSaved));
        });
        builder.show();
    }

    private static void toast(Context ctx, String text) {
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
    }
}
