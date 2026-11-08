package com.pithaya.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final int S_LOADING = 0, S_CAMERA = 1, S_REVIEW = 2, S_FULFIL = 3, S_TIMER = 4, S_PERM = 5;

    private static final String DEATH_TEXT =
            "Grant death to those who have overstayed their fate. And the wish you desire shall finally be yours.";

    private static final int BLOOD = 0xFF8A0303;
    private static final int BRIGHT = 0xFFC41212;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private SharedPreferences prefs;
    private Typeface font;
    private File videoFile;
    private int state = -1;

    // camera
    private SurfaceView surfaceView;
    private SurfaceHolder holder;
    private Camera camera;
    private Camera.CameraInfo camInfo;
    private CamcorderProfile profile;
    private int prevW = 1280, prevH = 720;
    private MediaRecorder recorder;
    private boolean recording = false;
    private long recStart;
    private PixelEyeView recEye;
    private TextView recText;

    // death overlay
    private FrameLayout deathOverlay;
    private TextView deathText;
    private Runnable typeRunnable;

    // timer
    private TextView timerView;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("pithaya", MODE_PRIVATE);
        font = loadFont();
        videoFile = new File(getFilesDir(), "wish.mp4");
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        showLoading();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRecordingQuietly();
        releaseCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (state == S_PERM && hasPerms()) {
            showCamera();
        } else if (state == S_CAMERA && camera == null && holder != null && holder.getSurface().isValid()) {
            openCamera();
        }
    }

    @Override
    public void onBackPressed() {
        if (deathOverlay != null) { hideDeath(); return; }
        if (state == S_REVIEW) { showCamera(); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Put a font file named font.ttf (or font.otf) in the repo and it will be used everywhere.
     * Otherwise a bold serif is used.
     */
    private Typeface loadFont() {
        String[] names = {"font.ttf", "font.otf"};
        for (String n : names) {
            try {
                return Typeface.createFromAsset(getAssets(), n);
            } catch (Exception ignored) {
            }
        }
        return Typeface.create("serif", Typeface.BOLD);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.06f);
        return t;
    }

    private TextView pill(String s, boolean filled) {
        TextView t = text(s, 15, filled ? Color.WHITE : 0xFFE0A0A0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(30));
        g.setColor(filled ? BLOOD : 0xCC000000);
        g.setStroke(dp(2), BLOOD);
        t.setBackground(g);
        t.setPadding(dp(10), dp(16), dp(10), dp(16));
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void switchTo(int s) {
        handler.removeCallbacksAndMessages(null);
        stopRecordingQuietly();
        releaseCamera();
        root.removeAllViews();
        surfaceView = null;
        holder = null;
        deathOverlay = null;
        deathText = null;
        timerView = null;
        recEye = null;
        recText = null;
        state = s;
    }

    // ------------------------------------------------------------------ loading

    private void showLoading() {
        switchTo(S_LOADING);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        TextView title = text("Pithaya", 46, BRIGHT);
        title.setShadowLayer(dp(8), 0, 0, 0xFFFF0000);
        col.addView(title, new LinearLayout.LayoutParams(WRAP, WRAP));

        PixelEyeView eye = new PixelEyeView(this);
        eye.setBlinking(true);
        col.addView(eye, new LinearLayout.LayoutParams(dp(290), dp(180)));

        TextView tag = text("Bawat hangad, may kapalit", 16, 0xFFA33A3A);
        col.addView(tag, new LinearLayout.LayoutParams(WRAP, WRAP));

        root.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        col.setAlpha(0f);
        col.animate().alpha(1f).setDuration(900).start();

        handler.postDelayed(this::afterLoading, 3800);
    }

    private void afterLoading() {
        if (prefs.getLong("end", 0) > 0) showTimer();
        else goCamera();
    }

    // ------------------------------------------------------------------ permissions

    private boolean hasPerms() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void goCamera() {
        if (hasPerms()) {
            showCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (hasPerms()) showCamera();
        else showPerm();
    }

    private void showPerm() {
        switchTo(S_PERM);
        TextView t = text("Kailangan ang camera at mikropono para matupad ang iyong hiling.\n\nI-tap dito para magpatuloy.", 18, BRIGHT);
        t.setPadding(dp(32), 0, dp(32), 0);
        t.setOnClickListener(v -> onPermTap());
        root.addView(t, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
    }

    private void onPermTap() {
        boolean blocked = false;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) blocked = true;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) blocked = true;
        if (blocked) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    // ------------------------------------------------------------------ camera screen

    private void showCamera() {
        switchTo(S_CAMERA);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        holder = surfaceView.getHolder();
        holder.addCallback(this);

        // small eye, top right
        PixelEyeView small = new PixelEyeView(this);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(dp(64), dp(40), Gravity.TOP | Gravity.END);
        sl.topMargin = dp(34);
        sl.rightMargin = dp(16);
        small.setOnClickListener(v -> showDeath());
        root.addView(small, sl);

        // recording time
        recText = text("", 16, 0xFFFF4040);
        recText.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        rl.topMargin = dp(44);
        root.addView(recText, rl);

        // record button (eye, never blinks)
        recEye = new PixelEyeView(this);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(120), dp(80), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bl.bottomMargin = dp(40);
        recEye.setOnClickListener(v -> toggleRecording());
        root.addView(recEye, bl);
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        stopRecordingQuietly();
        releaseCamera();
    }

    private CamcorderProfile pickProfile(int id) {
        int[] qs = {CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_480P,
                CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_LOW};
        for (int q : qs) {
            try {
                if (CamcorderProfile.hasProfile(id, q)) return CamcorderProfile.get(id, q);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Camera.Size bestPreview(List<Camera.Size> sizes, int w, int h) {
        for (Camera.Size s : sizes) if (s.width == w && s.height == h) return s;
        double target = (double) w / h;
        Camera.Size best = sizes.get(0);
        double bestScore = Double.MAX_VALUE;
        for (Camera.Size s : sizes) {
            double score = Math.abs((double) s.width / s.height - target) * 100
                    + Math.abs(s.width - w) / 1000.0;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    private void openCamera() {
        if (camera != null || holder == null) return;
        try {
            int n = Camera.getNumberOfCameras();
            int camId = -1;
            Camera.CameraInfo ci = new Camera.CameraInfo();
            for (int i = 0; i < n; i++) {
                Camera.getCameraInfo(i, ci);
                if (ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) { camId = i; break; }
            }
            if (camId < 0) camId = 0;
            camInfo = new Camera.CameraInfo();
            Camera.getCameraInfo(camId, camInfo);

            camera = Camera.open(camId);
            profile = pickProfile(camId);

            Camera.Parameters p = camera.getParameters();
            int wantW = profile != null ? profile.videoFrameWidth : 1280;
            int wantH = profile != null ? profile.videoFrameHeight : 720;
            Camera.Size best = bestPreview(p.getSupportedPreviewSizes(), wantW, wantH);
            p.setPreviewSize(best.width, best.height);
            prevW = best.width;
            prevH = best.height;
            List<String> fm = p.getSupportedFocusModes();
            if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            p.setRecordingHint(true);
            camera.setParameters(p);

            int result;
            if (camInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (360 - (camInfo.orientation % 360)) % 360;
            } else {
                result = (camInfo.orientation + 360) % 360;
            }
            camera.setDisplayOrientation(result);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            fitSurface(best.width, best.height);
        } catch (Exception e) {
            toast("Hindi mabuksan ang camera");
            releaseCamera();
        }
    }

    /** Fill the screen with the preview (center crop). Sensor is landscape, screen is portrait. */
    private void fitSurface(int pw, int ph) {
        if (surfaceView == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (root.getWidth() > 0 && root.getHeight() > 0) {
            sw = root.getWidth();
            sh = root.getHeight();
        }
        float scale = Math.max(sw / (float) ph, sh / (float) pw);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.round(ph * scale), Math.round(pw * scale), Gravity.CENTER);
        surfaceView.setLayoutParams(lp);
    }

    private void releaseCamera() {
        if (recorder != null) cleanupRecorder();
        if (camera != null) {
            try { camera.stopPreview(); } catch (Exception ignored) { }
            try { camera.release(); } catch (Exception ignored) { }
            camera = null;
        }
    }

    // ------------------------------------------------------------------ recording

    private void toggleRecording() {
        if (camera == null) return;
        if (!recording) startRecording();
        else stopRecording();
    }

    private void startRecording() {
        try {
            if (videoFile.exists()) videoFile.delete();
            camera.unlock();
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setCamera(camera);
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            if (profile != null) {
                recorder.setProfile(profile);
            } else {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setVideoSize(prevW, prevH);
                recorder.setVideoFrameRate(30);
                recorder.setVideoEncodingBitRate(3000000);
            }
            recorder.setOutputFile(videoFile.getAbsolutePath());
            recorder.setOrientationHint(camInfo != null ? camInfo.orientation : 90);
            recorder.setPreviewDisplay(holder.getSurface());
            recorder.prepare();
            recorder.start();
            recording = true;
            recStart = SystemClock.elapsedRealtime();
            if (recEye != null) recEye.setGlow(true);
            if (recText != null) recText.setVisibility(View.VISIBLE);
            handler.post(recTick);
        } catch (Exception e) {
            cleanupRecorder();
            toast("Hindi masimulan ang recording");
        }
    }

    private final Runnable recTick = new Runnable() {
        @Override
        public void run() {
            if (!recording || recText == null) return;
            long s = (SystemClock.elapsedRealtime() - recStart) / 1000;
            recText.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
            handler.postDelayed(this, 500);
        }
    };

    private void stopRecording() {
        if (!recording) return;
        boolean ok = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            ok = false;
            videoFile.delete();
        }
        cleanupRecorder();
        if (recEye != null) recEye.setGlow(false);
        if (recText != null) recText.setVisibility(View.INVISIBLE);
        if (ok && videoFile.exists() && videoFile.length() > 0) {
            showReview();
        } else {
            toast("Masyadong maikli. Subukan muli.");
        }
    }

    private void stopRecordingQuietly() {
        if (recording && recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) { }
            cleanupRecorder();
            videoFile.delete();
        } else if (recorder != null) {
            cleanupRecorder();
        }
    }

    private void cleanupRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) { }
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
        if (camera != null) {
            try { camera.lock(); } catch (Exception ignored) { }
        }
        recording = false;
    }

    // ------------------------------------------------------------------ death overlay

    private void showDeath() {
        if (deathOverlay != null) return;
        deathOverlay = new FrameLayout(this);
        deathOverlay.setBackgroundColor(Color.BLACK);
        deathOverlay.setClickable(true);
        deathOverlay.setOnClickListener(v -> hideDeath());

        deathText = text(DEATH_TEXT, 24, BLOOD);
        deathText.setPadding(dp(32), 0, dp(32), 0);
        deathText.setLineSpacing(0, 1.25f);
        deathOverlay.addView(deathText, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));

        deathOverlay.setAlpha(0f);
        root.addView(deathOverlay, new FrameLayout.LayoutParams(MATCH, MATCH));
        deathOverlay.animate().alpha(1f).setDuration(700).start();

        setRevealed(0);
        typeRunnable = new Runnable() {
            int i = 0;

            @Override
            public void run() {
                if (deathText == null) return;
                i++;
                setRevealed(i);
                if (i < DEATH_TEXT.length()) {
                    char c = DEATH_TEXT.charAt(i - 1);
                    handler.postDelayed(this, (c == '.' || c == ',') ? 650 : 95);
                }
            }
        };
        handler.postDelayed(typeRunnable, 1400);
    }

    private void setRevealed(int n) {
        if (deathText == null) return;
        SpannableString ss = new SpannableString(DEATH_TEXT);
        if (n < DEATH_TEXT.length()) {
            ss.setSpan(new ForegroundColorSpan(0x00000000), n, DEATH_TEXT.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        deathText.setText(ss);
    }

    private void hideDeath() {
        if (typeRunnable != null) handler.removeCallbacks(typeRunnable);
        if (deathOverlay != null) {
            root.removeView(deathOverlay);
            deathOverlay = null;
            deathText = null;
        }
    }

    // ------------------------------------------------------------------ review

    private void showReview() {
        switchTo(S_REVIEW);

        VideoView vv = new VideoView(this);
        root.addView(vv, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        vv.setVideoPath(videoFile.getAbsolutePath());
        vv.setOnPreparedListener(mp -> mp.setLooping(true));
        vv.start();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);

        TextView retry = pill("muling subukan", false);
        TextView send = pill("lalay ang hiling", true);
        retry.setOnClickListener(v -> showCamera());
        send.setOnClickListener(v -> startFulfil());

        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, WRAP, 1f);
        a.setMargins(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(0, WRAP, 1f);
        c.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(retry, a);
        bar.addView(send, c);

        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        bl.bottomMargin = dp(36);
        bl.leftMargin = dp(14);
        bl.rightMargin = dp(14);
        root.addView(bar, bl);
    }

    // ------------------------------------------------------------------ fulfilling the wish

    private void startFulfil() {
        switchTo(S_FULFIL);
        prefs.edit().putLong("end", System.currentTimeMillis() + 24L * 60 * 60 * 1000).apply();

        PixelEyeView eye = new PixelEyeView(this);
        eye.setOrbiting(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(330), dp(240), Gravity.CENTER);
        lp.bottomMargin = dp(40);
        root.addView(eye, lp);
        eye.setAlpha(0f);
        eye.animate().alpha(1f).setStartDelay(500).setDuration(900).start();

        TextView t = text("tinutupad ang iyong hiling", 18, BRIGHT);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        tl.bottomMargin = dp(80);
        root.addView(t, tl);
        AlphaAnimation a = new AlphaAnimation(0.3f, 1f);
        a.setDuration(1100);
        a.setRepeatMode(Animation.REVERSE);
        a.setRepeatCount(Animation.INFINITE);
        t.startAnimation(a);

        handler.postDelayed(this::showTimer, 7000);
    }

    // ------------------------------------------------------------------ timer

    private String fmt(long s) {
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (timerView == null) return;
            long end = prefs.getLong("end", 0);
            long rem = Math.max(0, (end - System.currentTimeMillis() + 999) / 1000);
            timerView.setText(fmt(rem));
            handler.postDelayed(this, 250);
        }
    };

    private void showTimer() {
        switchTo(S_TIMER);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        PixelEyeView eye = new PixelEyeView(this);
        col.addView(eye, new LinearLayout.LayoutParams(dp(130), dp(80)));

        timerView = text("24:00:00", 56, BRIGHT);
        timerView.setShadowLayer(dp(8), 0, 0, 0xAAFF0000);
        timerView.setFontFeatureSettings("tnum");
        timerView.setPadding(0, dp(28), 0, 0);
        // Hidden on purpose: tapping the timer lets you change it, with no visual hint.
        timerView.setOnClickListener(v -> showEditDialog());
        col.addView(timerView, new LinearLayout.LayoutParams(MATCH, WRAP));

        root.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        handler.post(timerTick);
    }

    private EditText field(long value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        e.setGravity(Gravity.CENTER);
        e.setTextSize(24);
        e.setText(String.format(Locale.US, "%02d", value));
        e.setSelectAllOnFocus(true);
        return e;
    }

    private void showEditDialog() {
        long end = prefs.getLong("end", 0);
        long rem = Math.max(0, (end - System.currentTimeMillis()) / 1000);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(20), dp(20), dp(20), dp(8));

        final EditText h = field(Math.min(99, rem / 3600));
        final EditText m = field((rem % 3600) / 60);
        final EditText s = field(rem % 60);
        row.addView(h, new LinearLayout.LayoutParams(dp(70), WRAP));
        TextView c1 = text(":", 24, Color.WHITE);
        row.addView(c1, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.addView(m, new LinearLayout.LayoutParams(dp(70), WRAP));
        TextView c2 = text(":", 24, Color.WHITE);
        row.addView(c2, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.addView(s, new LinearLayout.LayoutParams(dp(70), WRAP));

        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setView(row)
                .setPositiveButton("OK", (d, w) -> {
                    long total = parse(h) * 3600 + parse(m) * 60 + parse(s);
                    prefs.edit().putLong("end", System.currentTimeMillis() + total * 1000).apply();
                    if (timerView != null) timerView.setText(fmt(total));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private long parse(EditText e) {
        try {
            return Long.parseLong(e.getText().toString().trim());
        } catch (Exception ex) {
            return 0;
        }
    }
}
