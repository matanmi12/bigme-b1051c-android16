package org.b1051c.sleep;

import android.app.Activity;
import android.os.Bundle;

/** No window (Theme.NoDisplay): started once by the ROM provisioning / adb so Android 15 drops the package's
 *  "stopped" state (a freshly installed app gets no broadcasts until something starts it) and the first frame is rendered. */
public class WakeActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Renderer.request(this, "wake-activity");
        finish();
    }
}
