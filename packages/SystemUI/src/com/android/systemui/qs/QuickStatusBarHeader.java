/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.net.Uri;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.graphics.ColorUtils;
import com.android.internal.policy.SystemBarUtils;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.battery.BatteryMeterView;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.VariableDateView;
import com.android.systemui.util.LargeScreenUtils;
import com.android.systemui.tuner.TunerService;

import lineageos.providers.LineageSettings;
import android.provider.Settings;

import java.util.List;

/**
 * View that contains the top-most bits of the QS panel (primarily the status bar with date, time,
 * battery, carrier info and privacy icons) and also contains the {@link QuickQSPanel}.
 */
public class QuickStatusBarHeader extends FrameLayout implements TunerService.Tunable,
        View.OnClickListener, View.OnLongClickListener {    

    private static final String QS_WEATHER_POSITION =
            "system:" + Settings.System.QS_WEATHER_POSITION;

    private static final float LARGE_CLOCK_SCALE_X = 2.3f;
    private static final float LARGE_CLOCK_SCALE_Y = 2.4f;

    private static final String QS_HEADER_IMAGE =
            "system:" + Settings.System.QS_HEADER_IMAGE;

    private boolean mExpanded;
    private boolean mQsDisabled;

    @Nullable
    private TouchAnimator mAlphaAnimator;
    @Nullable
    private TouchAnimator mTranslationAnimator;
    @Nullable
    private TouchAnimator mIconsAlphaAnimator;
    private TouchAnimator mIconsAlphaAnimatorFixed;

    protected QuickQSPanel mHeaderQsPanel;
    private View mDatePrivacyView;
    private View mDateView;
    // DateView next to clock. Visible on QQS
    private VariableDateView mClockDateView;
    private View mStatusIconsView;
    private View mContainer;

    private View mQsWeatherView;

    private View mQSCarriers;
    private ViewGroup mClockContainer;
    private Clock mClockView;
    private Space mDatePrivacySeparator;
    private View mClockIconsSeparator;
    private boolean mShowClockIconsSeparator;
    private View mRightLayout;
    private View mDateContainer;
    private View mPrivacyContainer;

    private BatteryMeterView mBatteryRemainingIcon;
    private StatusIconContainer mIconContainer;
    private View mPrivacyChip;
    
    private int mQQSWeather;

    // QS Header
    private ImageView mQsHeaderImageView;
    private View mQsHeaderLayout;
    private boolean mHeaderImageEnabled;
    private int mHeaderImageValue;

    @Nullable
    private TintedIconManager mTintedIconManager;
    @Nullable
    private QSExpansionPathInterpolator mQSExpansionPathInterpolator;
    private StatusBarContentInsetsProvider mInsetsProvider;

    private int mRoundedCornerPadding = 0;
    private int mStatusBarPaddingTop;
    private int mStatusBarPaddingStart;
    private int mStatusBarPaddingEnd;
    private int mHeaderPaddingLeft;
    private int mHeaderPaddingRight;
    private int mWaterfallTopInset;
    private int mCutOutPaddingLeft;
    private int mCutOutPaddingRight;
    private float mKeyguardExpansionFraction;
    private int mTextColorPrimary = Color.TRANSPARENT;
    private int mTopViewMeasureHeight;

    @NonNull
    private List<String> mRssiIgnoredSlots = List.of();
    private boolean mIsSingleCarrier;

    private boolean mHasLeftCutout;
    private boolean mHasRightCutout;

    private boolean mUseCombinedQSHeader;

    private int mStatusBarBatteryStyle, mQSBatteryStyle, mQSBatteryLocation;
    private final ActivityStarter mActivityStarter;
    private final Vibrator mVibrator;

    private boolean mQsExpanding;
    private int mClockHeight;

    private int mExpandedQsClockDateStart;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
        mActivityStarter = Dependency.get(ActivityStarter.class);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /**
     * How much the view containing the clock and QQS will translate down when QS is fully expanded.
     *
     * This matches the measured height of the view containing the date and privacy icons.
     */
    public int getOffsetTranslation() {
        return mTopViewMeasureHeight;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mDatePrivacyView = findViewById(R.id.quick_status_bar_date_privacy);
        mDatePrivacySeparator = findViewById(R.id.space);
        mStatusIconsView = findViewById(R.id.quick_qs_status_icons);
        mQSCarriers = findViewById(R.id.carrier_group);
        mContainer = findViewById(R.id.qs_container);
        mIconContainer = findViewById(R.id.statusIcons);
        mPrivacyChip = findViewById(R.id.privacy_chip);
        mDateView = findViewById(R.id.date);
        mDateView.setOnClickListener(this);
        mDateView.setOnLongClickListener(this);
        mClockDateView = findViewById(R.id.date_clock);
        mClockDateView.setOnClickListener(this);
        mClockDateView.setOnLongClickListener(this);
        mQsWeatherView = findViewById(R.id.qs_weather_view);
        mQsWeatherView.setOnLongClickListener(this);
        mClockIconsSeparator = findViewById(R.id.separator);
        mRightLayout = findViewById(R.id.rightLayout);
        mDateContainer = findViewById(R.id.date_container);
        mPrivacyContainer = findViewById(R.id.privacy_container);

        mClockContainer = findViewById(R.id.clock_container);
        mClockView = findViewById(R.id.clock);
        mClockView.setOnClickListener(this);
        mClockView.setOnLongClickListener(this);
        mDatePrivacySeparator = findViewById(R.id.space);
        mClockView.setPivotX(0);
        mClockView.setPivotY(mClockView.getMeasuredHeight());

        // Tint for the battery icons are handled in setupHost()
        mBatteryRemainingIcon = findViewById(R.id.batteryRemainingIcon);
        mBatteryRemainingIcon.setOnClickListener(this);
        mBatteryRemainingIcon.setOnLongClickListener(this);

	mQsHeaderLayout = findViewById(R.id.layout_header);
        mQsHeaderImageView = findViewById(R.id.qs_header_image_view);
        mQsHeaderImageView.setClipToOutline(true);

        updateResources();

        Configuration config = mContext.getResources().getConfiguration();
        setDatePrivacyContainersWidth(config.orientation == Configuration.ORIENTATION_LANDSCAPE);

        mIconsAlphaAnimatorFixed = new TouchAnimator.Builder()
                .addFloat(mIconContainer, "alpha", 0, 1)
                .addFloat(mBatteryRemainingIcon, "alpha", 0, 1)
                .build();

        Dependency.get(TunerService.class).addTunable(this,
                StatusBarIconController.ICON_HIDE_LIST,
                QS_WEATHER_POSITION,
                QS_HEADER_IMAGE);
    }

    void onAttach(TintedIconManager iconManager,
            QSExpansionPathInterpolator qsExpansionPathInterpolator,
            List<String> rssiIgnoredSlots,
            StatusBarContentInsetsProvider insetsProvider,
            boolean useCombinedQSHeader) {
        mUseCombinedQSHeader = useCombinedQSHeader;
        mTintedIconManager = iconManager;
        mRssiIgnoredSlots = rssiIgnoredSlots;
        mInsetsProvider = insetsProvider;
        int fillColor = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.textColorPrimary);

        // Set the correct tint for the status icons so they contrast
        iconManager.setTint(fillColor);

        mQSExpansionPathInterpolator = qsExpansionPathInterpolator;
        updateAnimators();
    }

    void setIsSingleCarrier(boolean isSingleCarrier) {
        mIsSingleCarrier = isSingleCarrier;
        if (mIsSingleCarrier) {
            mIconContainer.removeIgnoredSlots(mRssiIgnoredSlots);
        }
        updateAlphaAnimator();
    }

    public QuickQSPanel getHeaderQsPanel() {
        return mHeaderQsPanel;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mDatePrivacyView.getMeasuredHeight() != mTopViewMeasureHeight) {
            mTopViewMeasureHeight = mDatePrivacyView.getMeasuredHeight();
            updateAnimators();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
        setDatePrivacyContainersWidth(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    @Override
    public void onClick(View v) {
        // Clock view is still there when the panel is not expanded
        // Making sure we get the date action when the user clicks on it
        // but actually is seeing the date
        if (v == mClockView) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS), 0);
        } else if (v == mDateView || v == mClockDateView) {
            Uri.Builder builder = CalendarContract.CONTENT_URI.buildUpon();
            builder.appendPath("time");
            builder.appendPath(Long.toString(System.currentTimeMillis()));
            Intent todayIntent = new Intent(Intent.ACTION_VIEW, builder.build());
            mActivityStarter.postStartActivityDismissingKeyguard(todayIntent, 0);
        } else if (v == mBatteryRemainingIcon) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    Intent.ACTION_POWER_USAGE_SUMMARY), 0);
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == mClockView || v == mDateView || v == mClockDateView) {
            Intent nIntent = new Intent(Intent.ACTION_MAIN);
            nIntent.setClassName("com.android.settings",
                    "com.android.settings.Settings$DateTimeSettingsActivity");
            mActivityStarter.startActivity(nIntent, true /* dismissShade */);
            mVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            return true;
        } else if (v == mBatteryRemainingIcon) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    Intent.ACTION_POWER_USAGE_SUMMARY), 0);
            return true;
        } else if (v == mQsWeatherView) {
            Intent wIntent = new Intent(Intent.ACTION_MAIN);
            wIntent.setClassName("org.omnirom.omnijaws",
                    "org.omnirom.omnijaws.SettingsActivity");
            mActivityStarter.startActivity(wIntent, true /* dismissShade */);
            mVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            return true;
        }
        return false;
    }

    private void setDatePrivacyContainersWidth(boolean landscape) {
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) mPrivacyContainer.getLayoutParams();
        lp.width = landscape ? WRAP_CONTENT : 0;
        lp.weight = landscape ? 0f : 1f;
        mPrivacyContainer.setLayoutParams(lp);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // If using combined headers, only react to touches inside QuickQSPanel
        if (!mUseCombinedQSHeader || event.getY() > mHeaderQsPanel.getTop()) {
            return super.onTouchEvent(event);
        } else {
            return false;
        }
    }

    void updateResources() {
        Resources resources = mContext.getResources();
        boolean largeScreenHeaderActive =
                LargeScreenUtils.shouldUseLargeScreenShadeHeader(resources);

        boolean gone = largeScreenHeaderActive || mUseCombinedQSHeader || mQsDisabled;
        mStatusIconsView.setVisibility(gone ? View.GONE : View.VISIBLE);
        mDatePrivacyView.setVisibility(gone ? View.GONE : View.VISIBLE);

        mRoundedCornerPadding = resources.getDimensionPixelSize(
                R.dimen.rounded_corner_content_padding);

        int statusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);

        mExpandedQsClockDateStart = resources.getDimensionPixelSize(
                R.dimen.qs_expanded_clock_date_padding_start);

        mStatusBarPaddingStart = resources.getDimensionPixelSize(
                R.dimen.status_bar_padding_start);
        mStatusBarPaddingEnd = resources.getDimensionPixelSize(
                R.dimen.status_bar_padding_end);

        int qsOffsetHeight = SystemBarUtils.getQuickQsOffsetHeight(mContext);

        mStatusBarPaddingTop = resources.getDimensionPixelSize(
                R.dimen.status_bar_padding_top_insets);

        mDatePrivacyView.getLayoutParams().height = statusBarHeight;
        mDatePrivacyView.setLayoutParams(mDatePrivacyView.getLayoutParams());

        mStatusIconsView.getLayoutParams().height = statusBarHeight;
        mStatusIconsView.setLayoutParams(mStatusIconsView.getLayoutParams());

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (mQsDisabled) {
            lp.height = mStatusIconsView.getLayoutParams().height - mWaterfallTopInset;
        } else {
            lp.height = WRAP_CONTENT;
        }
        setLayoutParams(lp);

        int textColor = Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorPrimary);
        if (textColor != mTextColorPrimary) {
            int isCircleBattery = mBatteryRemainingIcon.getBatteryStyle();
            int textColorSecondary = Utils.getColorAttrDefaultColor(mContext,
                    (isCircleBattery == 1 || isCircleBattery == 2 || isCircleBattery == 3)
                    ? android.R.attr.textColorHint : android.R.attr.textColorSecondary);
            mTextColorPrimary = textColor;
            mClockView.setTextColor(textColor);
            if (mTintedIconManager != null) {
                mTintedIconManager.setTint(textColor);
            }
            mBatteryRemainingIcon.updateColors(mTextColorPrimary, textColorSecondary,
                    mTextColorPrimary);
        }

        MarginLayoutParams qqsLP = (MarginLayoutParams) mHeaderQsPanel.getLayoutParams();
        qqsLP.topMargin = largeScreenHeaderActive || !mUseCombinedQSHeader
                ? mContext.getResources().getDimensionPixelSize(R.dimen.qqs_layout_margin_top)
                : SystemBarUtils.getQuickQsOffsetHeight(mContext);
        mHeaderQsPanel.setLayoutParams(qqsLP);

        updateQSHeaderImage();

        updateHeadersPadding();
        updateAnimators();

        updateClockDatePadding();
    }

    private void updateQSHeaderImage() {
        if (!mHeaderImageEnabled) {
            mQsHeaderLayout.setVisibility(View.GONE);
            return;
        }
        Configuration config = mContext.getResources().getConfiguration();
        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            boolean mIsNightMode = (mContext.getResources().getConfiguration().uiMode &
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int fadeFilter = ColorUtils.blendARGB(Color.TRANSPARENT, mIsNightMode ?
                Color.BLACK : Color.WHITE, 30 / 100f);
            int resId = getResources().getIdentifier("qs_header_image_" +
                String.valueOf(mHeaderImageValue), "drawable", "com.android.systemui");
            mQsHeaderImageView.setImageResource(resId);
            mQsHeaderImageView.setColorFilter(fadeFilter, PorterDuff.Mode.SRC_ATOP);
            mQsHeaderLayout.setVisibility(View.VISIBLE);
        } else {
            mQsHeaderLayout.setVisibility(View.GONE);
        }
    }

    private void updateClockDatePadding() {
        int startPadding = mContext.getResources()
                .getDimensionPixelSize(R.dimen.status_bar_left_clock_starting_padding);
        int endPadding = mContext.getResources()
                .getDimensionPixelSize(R.dimen.status_bar_left_clock_end_padding);
        mClockView.setPaddingRelative(
                startPadding,
                mClockView.getPaddingTop(),
                endPadding,
                mClockView.getPaddingBottom()
        );

        MarginLayoutParams lp = (MarginLayoutParams) mClockDateView.getLayoutParams();
        lp.setMarginStart(endPadding);
        mClockDateView.setLayoutParams(lp);
    }

    private void updateAnimators() {
        if (mUseCombinedQSHeader) {
            mTranslationAnimator = null;
            return;
        }
        updateAlphaAnimator();
        int offset = mTopViewMeasureHeight;

        mTranslationAnimator = new TouchAnimator.Builder()
                .addFloat(mContainer, "translationY", 0, offset)
                .setInterpolator(mQSExpansionPathInterpolator != null
                        ? mQSExpansionPathInterpolator.getYInterpolator()
                        : null)
                .build();
    }

    private void updateAlphaAnimator() {
        int endPadding = mContext.getResources()
                .getDimensionPixelSize(R.dimen.status_bar_left_clock_end_padding);
        if (mUseCombinedQSHeader) {
            mAlphaAnimator = null;
            return;
        }
        TouchAnimator.Builder builder = new TouchAnimator.Builder()
                // These views appear on expanding down
                .addFloat(mDateView, "alpha", 0, 0, 1)
                .addFloat(mQSCarriers, "alpha", 0, 0, 1)
                .addFloat(mQsWeatherView, "alpha", 0, 0, 1)
                // Date is shown next to clock only in QQS
                .addFloat(mClockDateView, "alpha", 1, 0, 0, 0, 0)
                // Move the clock container
                .addFloat(mClockContainer, "translationX",
                    mHeaderPaddingLeft + mStatusBarPaddingStart, mExpandedQsClockDateStart - endPadding)
                .addFloat(mDateView, "translationX",
                    mHeaderPaddingLeft + mStatusBarPaddingEnd, mExpandedQsClockDateStart)
                // Enlarge clock on expanding down
                .addFloat(mClockView, "scaleX", 1, LARGE_CLOCK_SCALE_X)
                .addFloat(mClockView, "scaleY", 1, LARGE_CLOCK_SCALE_Y)
                // Hide and show the right layout (status icons etc.)
                .addFloat(mRightLayout, "alpha", 1, 0, 1)
                .addFloat(mRightLayout, "translationX",
                    -(mHeaderPaddingRight + mStatusBarPaddingEnd), 0)
                .setListener(new TouchAnimator.ListenerAdapter() {
                    @Override
                    public void onAnimationAtEnd() {
                        super.onAnimationAtEnd();
                        mDateView.setVisibility(View.VISIBLE);
                        mClockDateView.setVisibility(View.GONE);
                        mQSCarriers.setVisibility(View.VISIBLE);
                        updateRightLayout(true);
                    }

                    @Override
                    public void onAnimationStarted() {
                        mClockDateView.setVisibility(View.VISIBLE);
                        mClockDateView.setFreezeSwitching(true);
                        mDateView.setVisibility(View.VISIBLE);
                        mQSCarriers.setVisibility(View.VISIBLE);
                        setChipVisibility(mPrivacyChip.getVisibility() == View.VISIBLE);
                        setSeparatorVisibility(false);
                        mQsWeatherView.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onAnimationAtStart() {
                        super.onAnimationAtStart();
                        mClockDateView.setFreezeSwitching(false);
                        mClockDateView.setVisibility(View.VISIBLE);
                        mClockView.setVisibility(View.VISIBLE);
                        mDateView.setVisibility(View.GONE);
                        mQSCarriers.setVisibility(View.GONE);
                        setSeparatorVisibility(mShowClockIconsSeparator);
                        updateRightLayout(false);
                        mClockHeight = mClockView.getMeasuredHeight();
                        if (mQsWeatherView != null) {
                            mQsWeatherView.setVisibility(View.GONE);
                        }
                    }
                });
        mAlphaAnimator = builder.build();
    }

    private void updateRightLayout(boolean expanding) {
        if (!mIsSingleCarrier) {
            if (expanding) {
                mIconContainer.addIgnoredSlots(mRssiIgnoredSlots);
            } else {
                mIconContainer.removeIgnoredSlots(mRssiIgnoredSlots);
            }
        }
        // Align to the date view when expanded, otherwise to the parent.
        int targetResId = expanding ? R.id.date : R.id.quick_qs_status_icons;
        ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams)
                mRightLayout.getLayoutParams();
        lp.topToTop = lp.bottomToBottom = targetResId;
        mRightLayout.setLayoutParams(lp);
    }

    void setChipVisibility(boolean visibility) {
        if (visibility) {
            // Animates the icons and battery indicator from alpha 0 to 1, when the chip is visible
            mIconsAlphaAnimator = mIconsAlphaAnimatorFixed;
            mIconsAlphaAnimator.setPosition(mKeyguardExpansionFraction);
            setBatteryRemainingOnClick(false);
        } else {
            mIconsAlphaAnimator = null;
            mIconContainer.setAlpha(1);
            mBatteryRemainingIcon.setAlpha(1);
            setBatteryRemainingOnClick(true);
        }

    }

    /** */
    public void setExpanded(boolean expanded, QuickQSPanelController quickQSPanelController) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        quickQSPanelController.setExpanded(expanded);
        updateEverything();
    }

    /**
     * Animates the inner contents based on the given expansion details.
     *
     * @param forceExpanded whether we should show the state expanded forcibly
     * @param expansionFraction how much the QS panel is expanded/pulled out (up to 1f)
     * @param panelTranslationY how much the panel has physically moved down vertically (required
     *                          for keyguard animations only)
     */
    public void setExpansion(boolean forceExpanded, float expansionFraction,
                             float panelTranslationY) {
        final float keyguardExpansionFraction = forceExpanded ? 1f : expansionFraction;

        if (mAlphaAnimator != null) {
            mAlphaAnimator.setPosition(keyguardExpansionFraction);
        }
        if (mTranslationAnimator != null) {
            mTranslationAnimator.setPosition(keyguardExpansionFraction);
        }
        if (mIconsAlphaAnimator != null) {
            mIconsAlphaAnimator.setPosition(keyguardExpansionFraction);
        }
        if (keyguardExpansionFraction == 1f && mBatteryRemainingIcon != null) {
            mBatteryRemainingIcon.setClickable(true);
        }

        // Adjust clock translation since we're scaling it, and other dependent
        // elements accordingly
        int yOffset = (int) (keyguardExpansionFraction * mClockHeight
                * (1 + LARGE_CLOCK_SCALE_Y - LARGE_CLOCK_SCALE_X));
        mClockView.setTranslationY(-yOffset);
        mDateView.setTranslationY(-yOffset / 2);
        mRightLayout.setTranslationY(-yOffset / 2);

        // HACK: For reasons unknown, QS carriers are top aligned when single sim and
        // bottom aligned when dual, rather than being centered vertically, so let's
        // translate it dynamically
        mQSCarriers.setTranslationY((mIsSingleCarrier ? 1 : -1) * (yOffset / 4));

        // Make changes in the right layout - hiding signal icons, changing constraints etc
        // while its hidden (alpha=1) at half animation progress
        boolean isQsExpanding = keyguardExpansionFraction > 0.5f;
        if (isQsExpanding != mQsExpanding) {
            mQsExpanding = isQsExpanding;
            updateRightLayout(isQsExpanding);
        }

        // If forceExpanded (we are opening QS from lockscreen), the animators have been set to
        // position = 1f.
        if (forceExpanded) {
            setAlpha(expansionFraction);
        } else {
            setAlpha(1);
        }

        mKeyguardExpansionFraction = keyguardExpansionFraction;
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        mStatusIconsView.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        updateResources();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        // Handle padding of the views
        DisplayCutout cutout = insets.getDisplayCutout();

        Pair<Integer, Integer> sbInsets = mInsetsProvider
                .getStatusBarContentInsetsForCurrentRotation();
        boolean hasCornerCutout = mInsetsProvider.currentRotationHasCornerCutout();

        LinearLayout.LayoutParams datePrivacySeparatorLayoutParams =
                (LinearLayout.LayoutParams) mDatePrivacySeparator.getLayoutParams();
        ConstraintLayout.LayoutParams mClockIconsSeparatorLayoutParams =
                (ConstraintLayout.LayoutParams) mClockIconsSeparator.getLayoutParams();
        if (cutout != null) {
            Rect topCutout = cutout.getBoundingRectTop();
            if (topCutout.isEmpty() || hasCornerCutout) {
                datePrivacySeparatorLayoutParams.width = 0;
                mDatePrivacySeparator.setVisibility(View.GONE);
                mClockIconsSeparatorLayoutParams.width = 0;
                setSeparatorVisibility(false);
                mShowClockIconsSeparator = false;
                if (sbInsets.first != 0) {
                    mHasLeftCutout = true;
                }
                if (sbInsets.second != 0) {
                    mHasRightCutout = true;
                }
            } else {
                datePrivacySeparatorLayoutParams.width = topCutout.width();
                mDatePrivacySeparator.setVisibility(View.VISIBLE);
                mClockIconsSeparatorLayoutParams.width = topCutout.width();
                mShowClockIconsSeparator = true;
                setSeparatorVisibility(mKeyguardExpansionFraction == 0f);
                mHasLeftCutout = false;
                mHasRightCutout = false;
            }
        }
        mDatePrivacySeparator.setLayoutParams(datePrivacySeparatorLayoutParams);
        mClockIconsSeparator.setLayoutParams(mClockIconsSeparatorLayoutParams);
        mCutOutPaddingLeft = sbInsets.first;
        mCutOutPaddingRight = sbInsets.second;
        mWaterfallTopInset = cutout == null ? 0 : cutout.getWaterfallInsets().top;

        updateHeadersPadding();
        return super.onApplyWindowInsets(insets);
    }

    /**
     * Sets the visibility of the separator between clock and icons.
     *
     * This separator is "visible" when there is a center cutout, to block that space. In that
     * case, the clock and the layout on the right (containing the icons and the battery meter) are
     * set to weight 1 to take the available space.
     * @param visible whether the separator between clock and icons should be visible.
     */
    private void setSeparatorVisibility(boolean visible) {
        int newVisibility = visible ? View.VISIBLE : View.GONE;
        if (mClockIconsSeparator.getVisibility() == newVisibility) return;

        mClockIconsSeparator.setVisibility(visible ? View.VISIBLE : View.GONE);
        mQSCarriers.setVisibility(visible ? View.GONE : View.VISIBLE);

        ConstraintLayout.LayoutParams lp =
                (ConstraintLayout.LayoutParams) mClockContainer.getLayoutParams();
        lp.width = visible ? 0 : WRAP_CONTENT;
        mClockContainer.setLayoutParams(lp);

        lp = (ConstraintLayout.LayoutParams) mRightLayout.getLayoutParams();
        lp.width = visible ? 0 : WRAP_CONTENT;
        mRightLayout.setLayoutParams(lp);
    }

    private void updateHeadersPadding() {
        setContentMargins(mDatePrivacyView, 0, 0);
        setContentMargins(mStatusIconsView, 0, 0);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        // Note: these are supposedly notification_side_paddings
        int leftMargin = lp.leftMargin;
        int rightMargin = lp.rightMargin;

        // The clock might collide with cutouts, let's shift it out of the way.
        // Margin will be the reference point of paddings/translations
        // and will have to be subtracted from cutout paddings
        boolean headerPaddingUpdated = false;
        int headerPaddingLeft = Math.max(mCutOutPaddingLeft, mRoundedCornerPadding) - leftMargin;
        if (headerPaddingLeft != mHeaderPaddingLeft) {
            mHeaderPaddingLeft = headerPaddingLeft;
            headerPaddingUpdated = true;
        }
        int headerPaddingRight = Math.max(mCutOutPaddingRight, mRoundedCornerPadding) - rightMargin;
        if (headerPaddingRight != mHeaderPaddingRight) {
            mHeaderPaddingRight = headerPaddingRight;
            headerPaddingUpdated = true;
        }

        // Update header animator with new paddings
        if (headerPaddingUpdated) {
            updateAnimators();
        }
        mDatePrivacyView.setPadding(mHeaderPaddingLeft + mStatusBarPaddingStart,
                mStatusBarPaddingTop,
                mHeaderPaddingRight + mStatusBarPaddingEnd,
                0);
        mStatusIconsView.setPadding(0,
                mStatusBarPaddingTop,
                0,
                0);
    }

    public void updateEverything() {
        post(() -> setClickable(!mExpanded));
        if (mExpanded) {
            setBatteryRemainingOnClick(true);
        }
    }

    private void setContentMargins(View view, int marginStart, int marginEnd) {
        MarginLayoutParams lp = (MarginLayoutParams) view.getLayoutParams();
        lp.setMarginStart(marginStart);
        lp.setMarginEnd(marginEnd);
        view.setLayoutParams(lp);
    }

    /**
     * Scroll the headers away.
     *
     * @param scrollY the scroll of the QSPanel container
     */
    public void setExpandedScrollAmount(int scrollY) {
        mStatusIconsView.setScrollY(scrollY);
        mDatePrivacyView.setScrollY(scrollY);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        mClockView.setClockVisibleByUser(!StatusBarIconController.getIconHideList(
                mContext, newValue).contains("clock"));
        switch (key) {
            case QS_HEADER_IMAGE:
                mHeaderImageValue =
                       TunerService.parseInteger(newValue, 0);
                mHeaderImageEnabled = mHeaderImageValue != 0;
                updateResources();
                break;
            default:
                break;
        }
    }

    private void setBatteryRemainingOnClick(boolean enable) {
        if (enable) {
            mBatteryRemainingIcon.setOnClickListener(
                    v -> mActivityStarter.postStartActivityDismissingKeyguard(
                            new Intent(Intent.ACTION_POWER_USAGE_SUMMARY), 0));
            mBatteryRemainingIcon.setClickable(true);
        } else {
            mBatteryRemainingIcon.setOnClickListener(null);
            mBatteryRemainingIcon.setClickable(false);
        }
    }
}
