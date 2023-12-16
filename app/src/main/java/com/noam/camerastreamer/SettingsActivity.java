package com.noam.camerastreamer;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

public class SettingsActivity extends AppCompatActivity {

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        getSupportFragmentManager().beginTransaction().replace(R.id.settings, new SettingsFragment(this)).commit();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private SettingsActivity activity;

        public SettingsFragment(SettingsActivity activity) {
            this.activity = activity;
        }

        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            EditTextPreference intEditText = findPreference(activity.getString(R.string.server_port_key));
            if (intEditText != null) {
                intEditText.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        if (!(newValue instanceof String)) {
                            return false;
                        }
                        int integerValue = Integer.parseInt((String) newValue);
                        return integerValue >= 0 && integerValue <= 65535;
                    }
                });
            }
            final ListPreference sizes = findPreference(activity.getString(R.string.size_key));
            if (sizes != null) {
                sizes.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        updateSizes(sizes);
                        return true;
                    }
                });
            }
        }

        private void updateSizes(ListPreference sizes) {
            String[] sizesList = MainActivity.possibleSizes;
            if (sizesList != null) {
                sizes.setEntries(sizesList);
                sizes.setEntryValues(sizesList);
                sizes.setDefaultValue(sizesList[0]);
            }
        }

        private void toast(Object... objects) {
            final StringBuilder res = new StringBuilder();
            for (int i = 0; i < objects.length; i++) {
                Object object = objects[i];
                if (object == null) {
                    res.append("null");
                } else {
                    res.append(object);
                }
                if (i < objects.length - 1) {
                    res.append(" ");
                }
            }

            activity.runOnUiThread(new Runnable() {
                public final void run() {
                    Toast.makeText(activity, res.toString(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
