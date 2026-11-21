package org.primftpd.remotecontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.primftpd.R;
import org.primftpd.prefs.LoadPrefsUtil;

import org.primftpd.util.ServicesStartStopUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PftpdPowerTogglesPlugin extends PowerTogglesPlugin {

    protected Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    protected void changeState(Context context, boolean newState) {
        logger.trace("changeState()");
        // The permission protecting this receiver is defined by the PowerToggles app. If that app is not
        // installed any app can define the permission itself and start or stop the server,
        // so remote control must have been enabled by the user like for the tasker plugin.
        SharedPreferences prefs = LoadPrefsUtil.getPrefs(context);
        if (!Boolean.TRUE.equals(LoadPrefsUtil.taskerEnabled(prefs))) {
            logger.info("ignoring request of power toggles plugin, remote control is disabled in preferences");
            Toast.makeText(context, R.string.taskerDisabledInPreferences, Toast.LENGTH_LONG).show();
            return;
        }
        if (newState) {
            ServicesStartStopUtil.startServers(context);
        } else {
            ServicesStartStopUtil.stopServers(context);
        }
        sendStateUpdate(context, newState);
    }
}
