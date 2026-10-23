<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- QA variant only: fixture screen for the emulator gate. This file is merged
         into app-qa.apk; the public app-debug.apk never contains the activity. -->
    <application>
        <activity
            android:name=".QaFixtureActivity"
            android:exported="false" />
    </application>
</manifest>
