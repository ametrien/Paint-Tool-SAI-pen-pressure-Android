package com.winlator.saipen;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * A column that is never wider than 640 dp: on a wide screen (a tablet held
 * sideways) the cards keep a phone-like width instead of stretching edge to
 * edge. The cap is worked out on every measure from the width the parent
 * really gives, so a turn of the screen, split screen or a return from SAI's
 * landscape window can't leave the column wider than the screen.
 */
public class MaxWidthLinearLayout extends LinearLayout {
    static final int MAX_DP = 640;

    public MaxWidthLinearLayout(Context context) { super(context); }
    public MaxWidthLinearLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int max = Math.round(MAX_DP * getResources().getDisplayMetrics().density);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && width > max)
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.getMode(widthMeasureSpec));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
