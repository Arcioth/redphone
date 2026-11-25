package io.github.arcioth.redphone.web;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The voice browser's view inside the deck's overlay container (one instance per attached host,
 * built with the host's context and dropped on detach so no Activity outlives its window).
 *
 * <ul>
 *   <li>ORB: a centred square panel, min(340dp, 90% of the host) like the desktop's 340x340 orb
 *       window, rounded with a thin crimson border; touches outside it reach the deck below.</li>
 *   <li>FULL: the panel fills the host (sign-in / VOICE TAB) with a small "◀ DECK" button.</li>
 *   <li>HIDDEN: GONE. The WebView is not in here then (VoiceWindow parks it detached).</li>
 * </ul>
 * It also reports its window's visibility, so VoiceWindow can take the WebView out of a stopped
 * window before Chromium marks the page hidden.
 */
final class VoiceFrame extends FrameLayout {

    interface Listener {
        /** Window visibility of this frame changed (also reported once when attached). */
        void onWindowVisibility(VoiceFrame frame, boolean visible);

        void onDetached(VoiceFrame frame);

        void onDeckPressed(VoiceFrame frame);
    }

    static final int ORB_DP = 340;
    static final float ORB_FRACTION = 0.9f;
    static final int ORB_PAD_DP = 5;

    private static final int ORB_RADIUS_DP = 18;
    private static final int ORB_ELEVATION_DP = 16;
    private static final int CRIMSON = 0xFFFF2A42;
    private static final int ORB_BORDER = 0x99FF2A42;
    private static final int BADGE_BG = 0xE012080A;
    private static final int BADGE_BORDER = 0xB3FF3E4D;
    private static final int BADGE_TEXT = 0xFFFFD7DA;

    private final float density;
    private final FrameLayout panel;
    private final TextView deck;
    private final GradientDrawable orbBg;
    private final ColorDrawable fullBg;
    private Listener listener;
    private int mode = -1;
    private int insetL;
    private int insetT;
    private int insetR;
    private int insetB;

    VoiceFrame(Context ctx, Listener l) {
        super(ctx);
        listener = l;
        density = ctx.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setMotionEventSplittingEnabled(false);

        orbBg = new GradientDrawable();
        orbBg.setColor(VoiceWindow.BG);
        orbBg.setCornerRadius(dp(ORB_RADIUS_DP));
        orbBg.setStroke(Math.max(1, Math.round(1.5f * density)), ORB_BORDER);
        fullBg = new ColorDrawable(VoiceWindow.BG);

        panel = new FrameLayout(ctx);
        panel.setBackground(orbBg);
        // Rounded clip (WebView honours outline clips from API 28; the padding keeps the corners
        // clean on 26/27 too) + a crimson glow where the platform can tint shadows.
        panel.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= 28) {
            panel.setOutlineAmbientShadowColor(CRIMSON);
            panel.setOutlineSpotShadowColor(CRIMSON);
        }
        addView(panel, new LayoutParams(dp(ORB_DP), dp(ORB_DP), Gravity.CENTER));

        deck = new TextView(ctx);
        deck.setText("◀ DECK");
        deck.setAllCaps(false);
        deck.setIncludeFontPadding(false);
        deck.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        deck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        deck.setLetterSpacing(0.08f);
        deck.setTextColor(BADGE_TEXT);
        deck.setGravity(Gravity.CENTER);
        deck.setMinHeight(dp(40));
        deck.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(BADGE_BG);
        pill.setCornerRadius(dp(20));
        pill.setStroke(Math.max(1, dp(1)), BADGE_BORDER);
        deck.setBackground(new RippleDrawable(ColorStateList.valueOf(0x66FF2A42), pill, null));
        deck.setElevation(dp(6));
        deck.setClickable(true);
        deck.setFocusable(true);
        deck.setContentDescription("Back to the Red Phone deck");
        deck.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Listener cb = listener;
                if (cb != null) cb.onDeckPressed(VoiceFrame.this);
            }
        });
        LayoutParams dl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        dl.setMargins(dp(10), dp(10), dp(10), dp(10));
        addView(deck, dl);

        setMode(VoiceWindow.HIDDEN);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Outer orb panel size for a host of w x h px (0 = unknown). */
    static int orbOuterPx(float density, int w, int h) {
        int s = Math.round(ORB_DP * density);
        if (w > 0) s = Math.min(s, Math.round(ORB_FRACTION * w));
        if (h > 0) s = Math.min(s, Math.round(ORB_FRACTION * h));
        return Math.max(s, 1);
    }

    /** The page viewport inside the orb panel (outer size minus the bezel). */
    static int orbInnerPx(float density, int w, int h) {
        return Math.max(1, orbOuterPx(density, w, h) - 2 * Math.round(ORB_PAD_DP * density));
    }

    FrameLayout panel() {
        return panel;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setMode(int m) {
        if (m == mode) return;
        mode = m;
        boolean full = m == VoiceWindow.FULL;
        setVisibility(m == VoiceWindow.HIDDEN ? GONE : VISIBLE);
        panel.setBackground(full ? fullBg : orbBg);
        int pad = full ? 0 : dp(ORB_PAD_DP);
        panel.setPadding(pad, pad, pad, pad);
        panel.setElevation(full ? 0 : dp(ORB_ELEVATION_DP));
        deck.setVisibility(full ? VISIBLE : GONE);
        applyInsetPadding();
        requestLayout();
    }

    private void applyInsetPadding() {
        if (mode == VoiceWindow.FULL) {
            setPadding(insetL, insetT, insetR, insetB);
        } else {
            setPadding(0, 0, 0, 0);
        }
    }

    // Only matters when the deck draws edge-to-edge: keep the full page and the DECK button out
    // from under the system bars / IME. Insets are recorded, never consumed.
    @Override
    @SuppressWarnings("deprecation")
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        insetL = insets.getSystemWindowInsetLeft();
        insetT = insets.getSystemWindowInsetTop();
        insetR = insets.getSystemWindowInsetRight();
        insetB = insets.getSystemWindowInsetBottom();
        applyInsetPadding();
        return insets;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        if (mode == VoiceWindow.FULL) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.NO_GRAVITY;
        } else {
            int w = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? 0 : MeasureSpec.getSize(widthSpec);
            int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? 0 : MeasureSpec.getSize(heightSpec);
            int s = orbOuterPx(density, w, h);
            lp.width = s;
            lp.height = s;
            lp.gravity = Gravity.CENTER;
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // View only calls onWindowVisibilityChanged on attach when the window is not GONE.
        Listener cb = listener;
        if (cb != null) cb.onWindowVisibility(this, getWindowVisibility() == VISIBLE);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // Runs before the children are told (ViewGroup dispatches to itself first), so a WebView
        // removed here never sees its window go invisible.
        Listener cb = listener;
        if (cb != null) cb.onWindowVisibility(this, visibility == VISIBLE);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Listener cb = listener;
        if (cb != null) cb.onDetached(this);
    }
}
