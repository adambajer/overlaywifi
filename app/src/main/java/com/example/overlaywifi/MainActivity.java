package com.example.overlaywifi;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_OVERLAY             = 1;
    private static final int REQ_RUNTIME_PERMISSIONS = 2;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* 1) Draw-over-apps */
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                                startActivityForResult(i, REQ_OVERLAY);
                                    return;
                                    }
        /* 2) Dangerous run-time perms */
        if (requestMissingRuntimePerms()) return;

        /* 3) Vše ok → spustit službu, zavřít launcher */
        startOverlayAndFinish();
    }

    /* ---------- callbacks ---------- */

    @Override
    public void onActivityResult(int rq, int res, @Nullable Intent data) {
        super.onActivityResult(rq, res, data);
            if (rq == REQ_OVERLAY) {
                    if (Settings.canDrawOverlays(this)) {
                                if (!requestMissingRuntimePerms()) startOverlayAndFinish();
                                        } else {
                                                    Toast.makeText(this, "Bez povolení překrytí nelze pokračovat.",
                                                                        Toast.LENGTH_LONG).show();
                                                                                    finish();
                                                                                            }
                                                                                                }
                                                                                                }
    @Override public void onRequestPermissionsResult(int rq,
                                                     @NonNull String[] p,
                                                     @NonNull int[] r) {
        super.onRequestPermissionsResult(rq, p, r);
        if (rq != REQ_RUNTIME_PERMISSIONS) return;
        boolean all = true; for (int v : r) if (v != PackageManager.PERMISSION_GRANTED) {all=false;break;}
        if (all) startOverlayAndFinish();
        else { Toast.makeText(this,"Aplikace potřebuje všechna oprávnění.",
                Toast.LENGTH_LONG).show(); finish(); }
    }

    /* ---------- helpers ---------- */

    private boolean requestMissingRuntimePerms() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);

        if (Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                        != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.NEARBY_WIFI_DEVICES);

        if (need.isEmpty()) return false;
        requestPermissions(need.toArray(new String[0]), REQ_RUNTIME_PERMISSIONS);
        return true;
    }

    private void startOverlayAndFinish() {
        ContextCompat.startForegroundService(this,
                new Intent(this, OverlayService.class));
        finish();
    }
}
