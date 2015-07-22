package eu.chainfire.opendelta;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;

public class Shortcut extends Activity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, new Intent(this, MainActivity.class));
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, this.getResources().getString(R.string.title));
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher_settings));
        setResult(RESULT_OK, intent);

        finish();
    }

}
