package com.example.douyinautoswiper;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    /** 与系统设置中 ENABLED_ACCESSIBILITY_SERVICES 记录格式一致。 */
    private static final String SERVICE_NAME =
            "com.example.douyinautoswiper/com.example.douyinautoswiper.AutoSwipeService";

    private Switch swEnabled;
    private EditText etInterval;
    private EditText etJitter;
    private TextView tvStatus;
    private Button btnOpenSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swEnabled = findViewById(R.id.sw_enabled);
        etInterval = findViewById(R.id.et_interval);
        etJitter = findViewById(R.id.et_jitter);
        tvStatus = findViewById(R.id.tv_status);
        btnOpenSettings = findViewById(R.id.btn_open_settings);

        swEnabled.setChecked(AppPreferences.isAutoSwipeEnabled(this));
        etInterval.setText(String.valueOf(AppPreferences.getIntervalSec(this)));
        etJitter.setText(String.valueOf(AppPreferences.getJitterSec(this)));

        swEnabled.setOnCheckedChangeListener((buttonView, checked) -> {
            AppPreferences.setAutoSwipeEnabled(this, checked);
            updateStatus();
            if (checked && !isAccessibilityEnabled()) {
                Toast.makeText(this, "请先在系统设置中开启本应用的无障碍权限",
                        Toast.LENGTH_LONG).show();
                openAccessibilitySettings();
            }
        });

        btnOpenSettings.setOnClickListener(v -> openAccessibilitySettings());

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            try {
                int interval = Integer.parseInt(etInterval.getText().toString().trim());
                int jitter = Integer.parseInt(etJitter.getText().toString().trim());
                AppPreferences.setIntervalSec(this, interval);
                AppPreferences.setJitterSec(this, jitter);
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "请输入有效的数字（秒）", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean acc = isAccessibilityEnabled();
        boolean on = AppPreferences.isAutoSwipeEnabled(this);
        if (!acc) {
            tvStatus.setText("状态：无障碍权限未开启（点下方按钮开启）");
        } else if (!on) {
            tvStatus.setText("状态：已就绪，自动滑动关闭");
        } else {
            tvStatus.setText("状态：运行中（正在自动刷）");
        }
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(SERVICE_NAME);
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }
}
