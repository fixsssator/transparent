package com.example.transparenttelegram;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class AboutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setPadding(48, 96, 48, 48);
        tv.setTextSize(16);

        StringBuilder sb = new StringBuilder();
        sb.append("Transparent Telegram\n\n");
        sb.append("Этот APK — не полноценное приложение, а модуль для LSPosed.\n\n");
        sb.append("1. Установите LSPosed (Zygisk/Magisk).\n");
        sb.append("2. Установите этот APK.\n");
        sb.append("3. В LSPosed Manager -> Modules включите модуль и отметьте " +
                "нужные приложения в Scope.\n");
        sb.append("4. Перезапустите отмеченные приложения (force stop).\n\n");
        sb.append("Список приложений, для которых хук прописан в коде " +
                "(HookEntry.TARGET_PACKAGES) — их же имеет смысл отмечать в Scope:\n");
        try {
            for (String pkg : getResources().getStringArray(R.array.target_packages)) {
                sb.append("• ").append(pkg).append("\n");
            }
        } catch (Throwable ignored) {
        }
        sb.append("\nЕсли эффекта нет — смотрите логи через LSPosed Manager -> Logs, " +
                "строки начинаются с \"[TransparentTelegram]\".");

        tv.setText(sb.toString());
        setContentView(tv);
    }
}
