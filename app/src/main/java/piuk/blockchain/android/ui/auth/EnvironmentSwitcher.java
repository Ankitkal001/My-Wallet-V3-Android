package piuk.blockchain.android.ui.auth;

import android.content.Context;
import android.support.v7.app.AlertDialog;
import android.widget.ArrayAdapter;

import java.util.Arrays;
import java.util.List;

import piuk.blockchain.android.R;
import piuk.blockchain.android.data.api.UrlSettings;
import piuk.blockchain.android.ui.customviews.ToastCustom;
import piuk.blockchain.android.util.AppRate;
import piuk.blockchain.android.util.PrefsUtil;

class EnvironmentSwitcher {

    private Context context;
    private PrefsUtil prefsUtil;

    EnvironmentSwitcher(Context context) {
        this.context = context;
        prefsUtil = new PrefsUtil(context);
    }

    void showEnvironmentSelectionDialog() {
        List<String> itemsList = Arrays.asList("Production", "Staging", "Dev");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context, R.layout.item_environment_list, itemsList);

        UrlSettings.Environment environment = UrlSettings.getInstance().getCurrentEnvironment();
        int selection;
        switch (environment) {
            case STAGING:
                selection = 1;
                break;
            case DEV:
                selection = 2;
                break;
            default:
                selection = 0;
                break;
        }

        final UrlSettings.Environment[] selectedEnvironment = new UrlSettings.Environment[1];

        new AlertDialog.Builder(context, R.style.AlertDialogStyle)
                .setTitle("Choose Environment")
                .setSingleChoiceItems(adapter, selection, (dialogInterface, i) -> {
                    switch (i) {
                        case 1:
                            selectedEnvironment[0] = UrlSettings.Environment.STAGING;
                            break;
                        case 2:
                            selectedEnvironment[0] = UrlSettings.Environment.DEV;
                            break;
                        default:
                            selectedEnvironment[0] = UrlSettings.Environment.PRODUCTION;
                            break;
                    }
                })
                .setPositiveButton("Select", (dialog, id) -> {
                    UrlSettings.getInstance().changeEnvironment(selectedEnvironment[0]);
                    ToastCustom.makeText(
                            context,
                            "Environment set to " + selectedEnvironment[0].getName(),
                            ToastCustom.LENGTH_SHORT,
                            ToastCustom.TYPE_OK);
                })
                .setNegativeButton("Reset Timers", (dialogInterface, i) -> resetAllTimers())
                .create()
                .show();
    }

    private void resetAllTimers() {
        prefsUtil.removeValue(PrefsUtil.KEY_PIN_FAILS);
        prefsUtil.removeValue(PrefsUtil.KEY_FIRST_RUN);
        prefsUtil.removeValue(PrefsUtil.KEY_SECURITY_TIME_ELAPSED);
        prefsUtil.removeValue(PrefsUtil.KEY_SECURITY_BACKUP_NEVER);
        prefsUtil.removeValue(PrefsUtil.KEY_SECURITY_TWO_FA_NEVER);
        AppRate.reset(context);

        ToastCustom.makeText(context, "Timers reset", ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_OK);
    }

}
