/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher2;

import com.android.launcher.R;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.ContextMenu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.View.OnTouchListener;
import android.view.animation.Animation;
import android.view.animation.LayoutAnimationController;

import java.util.ArrayList;
import java.util.Arrays;

public class CellLayout extends ViewGroup {
    static final String TAG = "CellLayout";
    // we make the dimmed bitmap smaller than the screen itself for memory + perf reasons
    static final float DIMMED_BITMAP_SCALE = 0.25f;

    private boolean mPortrait;

    private int mCellWidth;
    private int mCellHeight;

    private int mLeftPadding;
    private int mRightPadding;
    private int mTopPadding;
    private int mBottomPadding;

    private int mCountX;
    private int mCountY;

    private int mWidthGap;
    private int mHeightGap;

    private final Rect mRect = new Rect();
    private final RectF mRectF = new RectF();
    private final CellInfo mCellInfo = new CellInfo();

    // This is a temporary variable to prevent having to allocate a new object just to
    // return an (x, y) value from helper functions. Do NOT use it to maintain other state.
    private final int[] mTmpCellXY = new int[2];

    boolean[][] mOccupied;

    private OnTouchListener mInterceptTouchListener;

    // this is what the home screen fades to when it shrinks
    //   (ie in all apps and in home screen customize mode)
    private Bitmap mDimmedBitmap;
    private Canvas mDimmedBitmapCanvas;
    private float mDimmedBitmapAlpha;
    private boolean mDimmedBitmapDirty;
    private final Paint mDimmedBitmapPaint = new Paint();
    private final Rect mLayoutRect = new Rect();
    private final Rect mDimmedBitmapRect = new Rect();

    private final RectF mDragRect = new RectF();

    // When dragging, used to indicate a vacant drop location
    private Drawable mVacantDrawable;

    // When dragging, used to indicate an occupied drop location
    private Drawable mOccupiedDrawable;

    // Updated to point to mVacantDrawable or mOccupiedDrawable, as appropriate
    private Drawable mDragRectDrawable;

    // When a drag operation is in progress, holds the nearest cell to the touch point
    private final int[] mDragCell = new int[2];

    private boolean mDirtyTag;
    private boolean mLastDownOnOccupiedCell = false;

    private final WallpaperManager mWallpaperManager;

    public CellLayout(Context context) {
        this(context, null);
    }

    public CellLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CellLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        // A ViewGroup usually does not draw, but CellLayout needs to draw a rectangle to show
        // the user where a dragged item will land when dropped.
        setWillNotDraw(false);
        mVacantDrawable = getResources().getDrawable(R.drawable.rounded_rect_green);
        mOccupiedDrawable = getResources().getDrawable(R.drawable.rounded_rect_red);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CellLayout, defStyle, 0);

        mCellWidth = a.getDimensionPixelSize(R.styleable.CellLayout_cellWidth, 10);
        mCellHeight = a.getDimensionPixelSize(R.styleable.CellLayout_cellHeight, 10);

        mLeftPadding =
            a.getDimensionPixelSize(R.styleable.CellLayout_xAxisStartPadding, 10);
        mRightPadding =
            a.getDimensionPixelSize(R.styleable.CellLayout_xAxisEndPadding, 10);
        mTopPadding =
            a.getDimensionPixelSize(R.styleable.CellLayout_yAxisStartPadding, 10);
        mBottomPadding =
            a.getDimensionPixelSize(R.styleable.CellLayout_yAxisEndPadding, 10);

        mCountX = LauncherModel.getCellCountX();
        mCountY = LauncherModel.getCellCountY();

        a.recycle();

        setAlwaysDrawnWithCacheEnabled(false);

        mWallpaperManager = WallpaperManager.getInstance(getContext());

        mDimmedBitmapPaint.setFilterBitmap(true);
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mDragRect.isEmpty()) {
            mDragRectDrawable.setBounds(
                    (int)mDragRect.left,
                    (int)mDragRect.top,
                    (int)mDragRect.right,
                    (int)mDragRect.bottom);
            mDragRectDrawable.draw(canvas);
        }
        if (mDimmedBitmap != null && mDimmedBitmapAlpha > 0.0f) {
            if (mDimmedBitmapDirty) {
                updateDimmedBitmap();
                mDimmedBitmapDirty = false;
            }
            mDimmedBitmapPaint.setAlpha((int) (mDimmedBitmapAlpha * 255));

            canvas.drawBitmap(mDimmedBitmap, mDimmedBitmapRect, mLayoutRect, mDimmedBitmapPaint);
        }
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();

        // Cancel long press for all children
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            child.cancelLongPress();
        }
    }

    public void setOnInterceptTouchListener(View.OnTouchListener listener) {
        mInterceptTouchListener = listener;
    }

    int getCountX() {
        return mCountX;
    }

    int getCountY() {
        return mCountY;
    }

    // Takes canonical layout parameters
    public boolean addViewToCellLayout(View child, int index, int childId, LayoutParams params) {
        final LayoutParams lp = params;

        // Generate an id for each view, this assumes we have at most 256x256 cells
        // per workspace screen
        if (lp.cellX >= 0 && lp.cellX <= mCountX - 1 && lp.cellY >= 0 && lp.cellY <= mCountY - 1) {
            // If the horizontal or vertical span is set to -1, it is taken to
            // mean that it spans the extent of the CellLayout
            if (lp.cellHSpan < 0) lp.cellHSpan = mCountX;
            if (lp.cellVSpan < 0) lp.cellVSpan = mCountY;

            child.setId(childId);

            // We might be in the middle or end of shrinking/fading to a dimmed view
            // Make sure this view's alpha is set the same as all the rest of the views
            child.setAlpha(1.0f - mDimmedBitmapAlpha);

            addView(child, index, lp);

            // next time we draw the dimmed bitmap we need to update it
            mDimmedBitmapDirty = true;
            return true;
        }
        return false;
    }

    @Override
    public void removeView(View view) {
        super.removeView(view);
        mDimmedBitmapDirty = true;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (child != null) {
            Rect r = new Rect();
            child.getDrawingRect(r);
            requestRectangleOnScreen(r);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mCellInfo.screen = ((ViewGroup) getParent()).indexOfChild(this);
    }

    public void setTagToCellInfoForPoint(int touchX, int touchY) {
        final CellInfo cellInfo = mCellInfo;
        final Rect frame = mRect;
        final int x = touchX + mScrollX;
        final int y = touchY + mScrollY;
        final int count = getChildCount();

        boolean found = false;
        for (int i = count - 1; i >= 0; i--) {
            final View child = getChildAt(i);

            if ((child.getVisibility()) == VISIBLE || child.getAnimation() != null) {
                child.getHitRect(frame);
                if (frame.contains(x, y)) {
                    final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                    cellInfo.cell = child;
                    cellInfo.cellX = lp.cellX;
                    cellInfo.cellY = lp.cellY;
                    cellInfo.spanX = lp.cellHSpan;
                    cellInfo.spanY = lp.cellVSpan;
                    cellInfo.valid = true;
                    found = true;
                    mDirtyTag = false;
                    break;
                }
            }
        }

        mLastDownOnOccupiedCell = found;

        if (!found) {
            final int cellXY[] = mTmpCellXY;
            pointToCellExact(x, y, cellXY);

            final boolean portrait = mPortrait;
            final int xCount = mCountX;
            final int yCount = mCountY;

            final boolean[][] occupied = mOccupied;
            findOccupiedCells(xCount, yCount, occupied, null, true);

            cellInfo.cell = null;
            cellInfo.cellX = cellXY[0];
            cellInfo.cellY = cellXY[1];
            cellInfo.spanX = 1;
            cellInfo.spanY = 1;
            cellInfo.valid = cellXY[0] >= 0 && cellXY[1] >= 0 && cellXY[0] < xCount &&
                    cellXY[1] < yCount && !occupied[cellXY[0]][cellXY[1]];

            // Instead of finding the interesting vacant cells here, wait until a
            // caller invokes getTag() to retrieve the result. Finding the vacant
            // cells is a bit expensive and can generate many new objects, it's
            // therefore better to defer it until we know we actually need it.

            mDirtyTag = true;
        }
        setTag(cellInfo);
    }


    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mInterceptTouchListener != null && mInterceptTouchListener.onTouch(this, ev)) {
            return true;
        }
        final int action = ev.getAction();
        final CellInfo cellInfo = mCellInfo;

        if (action == MotionEvent.ACTION_DOWN) {
            setTagToCellInfoForPoint((int) ev.getX(), (int) ev.getY());
        } else if (action == MotionEvent.ACTION_UP) {
            cellInfo.cell = null;
            cellInfo.cellX = -1;
            cellInfo.cellY = -1;
            cellInfo.spanX = 0;
            cellInfo.spanY = 0;
            cellInfo.valid = false;
            mDirtyTag = false;
            setTag(cellInfo);
        }

        return false;
    }

    @Override
    public CellInfo getTag() {
        final CellInfo info = (CellInfo) super.getTag();
        if (mDirtyTag && info.valid) {
            final int xCount = mCountX;
            final int yCount = mCountY;

            final boolean[][] occupied = mOccupied;
            findOccupiedCells(xCount, yCount, occupied, null, true);

            findIntersectingVacantCells(info, info.cellX, info.cellY, xCount, yCount, occupied);

            mDirtyTag = false;
        }
        return info;
    }

    private static void findIntersectingVacantCells(CellInfo cellInfo, int x,
            int y, int xCount, int yCount, boolean[][] occupied) {

        cellInfo.maxVacantSpanX = Integer.MIN_VALUE;
        cellInfo.maxVacantSpanXSpanY = Integer.MIN_VALUE;
        cellInfo.maxVacantSpanY = Integer.MIN_VALUE;
        cellInfo.maxVacantSpanYSpanX = Integer.MIN_VALUE;
        cellInfo.clearVacantCells();

        if (occupied[x][y]) {
            return;
        }

        cellInfo.current.set(x, y, x, y);

        findVacantCell(cellInfo.current, xCount, yCount, occupied, cellInfo);
    }

    private static void findVacantCell(Rect current, int xCount, int yCount, boolean[][] occupied,
            CellInfo cellInfo) {

        addVacantCell(current, cellInfo);

        if (current.left > 0) {
            if (isColumnEmpty(current.left - 1, current.top, current.bottom, occupied)) {
                current.left--;
                findVacantCell(current, xCount, yCount, occupied, cellInfo);
                current.left++;
            }
        }

        if (current.right < xCount - 1) {
            if (isColumnEmpty(current.right + 1, current.top, current.bottom, occupied)) {
                current.right++;
                findVacantCell(current, xCount, yCount, occupied, cellInfo);
                current.right--;
            }
        }

        if (current.top > 0) {
            if (isRowEmpty(current.top - 1, current.left, current.right, occupied)) {
                current.top--;
                findVacantCell(current, xCount, yCount, occupied, cellInfo);
                current.top++;
            }
        }

        if (current.bottom < yCount - 1) {
            if (isRowEmpty(current.bottom + 1, current.left, current.right, occupied)) {
                current.bottom++;
                findVacantCell(current, xCount, yCount, occupied, cellInfo);
                current.bottom--;
            }
        }
    }

    private static void addVacantCell(Rect current, CellInfo cellInfo) {
        CellInfo.VacantCell cell = CellInfo.VacantCell.acquire();
        cell.cellX = current.left;
        cell.cellY = current.top;
        cell.spanX = current.right - current.left + 1;
        cell.spanY = current.bottom - current.top + 1;
        if (cell.spanX > cellInfo.maxVacantSpanX) {
            cellInfo.maxVacantSpanX = cell.spanX;
            cellInfo.maxVacantSpanXSpanY = cell.spanY;
        }
        if (cell.spanY > cellInfo.maxVacantSpanY) {
            cellInfo.maxVacantSpanY = cell.spanY;
            cellInfo.maxVacantSpanYSpanX = cell.spanX;
        }
        cellInfo.vacantCells.add(cell);
    }

    /**
     * Check if the column 'x' is empty from rows 'top' to 'bottom', inclusive.
     */
    private static boolean isColumnEmpty(int x, int top, int bottom, boolean[][] occupied) {
        for (int y = top; y <= bottom; y++) {
            if (occupied[x][y]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if the row 'y' is empty from columns 'left' to 'right', inclusive.
     */
    private static boolean isRowEmpty(int y, int left, int right, boolean[][] occupied) {
        for (int x = left; x <= right; x++) {
            if (occupied[x][y]) {
                return false;
            }
        }
        return true;
    }

    CellInfo findAllVacantCells(boolean[] occupiedCells, View ignoreView) {
        final int xCount = mCountX;
        final int yCount = mCountY;

        boolean[][] occupied = mOccupied;

        if (occupiedCells != null) {
            for (int y = 0; y < yCount; y++) {
                for (int x = 0; x < xCount; x++) {
                    occupied[x][y] = occupiedCells[y * xCount + x];
                }
            }
        } else {
            findOccupiedCells(xCount, yCount, occupied, ignoreView, true);
        }

        CellInfo cellInfo = new CellInfo();

        cellInfo.cellX = -1;
        cellInfo.cellY = -1;
        cellInfo.spanY = 0;
        cellInfo.spanX = 0;
        cellInfo.maxVacantSpanX = Integer.MIN_VALUE;
        cellInfo.maxVacantSpanXSpanY = Integer.MIN_VALUE;
        cellInfo.maxVacantSpanY = Integer.MIN_VALUE;
        cellInfo.maxVacantSpanYSpanX = Integer.MIN_VALUE;
        cellInfo.screen = mCellInfo.screen;

        Rect current = cellInfo.current;

        for (int x = 0; x < xCount; x++) {
            for (int y = 0; y < yCount; y++) {
                if (!occupied[x][y]) {
                    current.set(x, y, x, y);
                    findVacantCell(current, xCount, yCount, occupied, cellInfo);
                    occupied[x][y] = true;
                }
            }
        }

        cellInfo.valid = cellInfo.vacantCells.size() > 0;

        // Assume the caller will perform their own cell searching, otherwise we
        // risk causing an unnecessary rebuild after findCellForSpan()

        return cellInfo;
    }

    /**
     * Given a point, return the cell that strictly encloses that point
     * @param x X coordinate of the point
     * @param y Y coordinate of the point
     * @param result Array of 2 ints to hold the x and y coordinate of the cell
     */
    void pointToCellExact(int x, int y, int[] result) {
        final int hStartPadding = getLeftPadding();
        final int vStartPadding = getTopPadding();

        result[0] = (x - hStartPadding) / (mCellWidth + mWidthGap);
        result[1] = (y - vStartPadding) / (mCellHeight + mHeightGap);

        final int xAxis = mCountX;
        final int yAxis = mCountY;

        if (result[0] < 0) result[0] = 0;
        if (result[0] >= xAxis) result[0] = xAxis - 1;
        if (result[1] < 0) result[1] = 0;
        if (result[1] >= yAxis) result[1] = yAxis - 1;
    }

    /**
     * Given a point, return the cell that most closely encloses that point
     * @param x X coordinate of the point
     * @param y Y coordinate of the point
     * @param result Array of 2 ints to hold the x and y coordinate of the cell
     */
    void pointToCellRounded(int x, int y, int[] result) {
        pointToCellExact(x + (mCellWidth / 2), y + (mCellHeight / 2), result);
    }

    /**
     * Given a cell coordinate, return the point that represents the upper left corner of that cell
     *
     * @param cellX X coordinate of the cell
     * @param cellY Y coordinate of the cell
     *
     * @param result Array of 2 ints to hold the x and y coordinate of the point
     */
    void cellToPoint(int cellX, int cellY, int[] result) {
        final int hStartPadding = getLeftPadding();
        final int vStartPadding = getTopPadding();

        result[0] = hStartPadding + cellX * (mCellWidth + mWidthGap);
        result[1] = vStartPadding + cellY * (mCellHeight + mHeightGap);
    }

    int getCellWidth() {
        return mCellWidth;
    }

    int getCellHeight() {
        return mCellHeight;
    }

    int getLeftPadding() {
        return mLeftPadding;
    }

    int getTopPadding() {
        return mTopPadding;
    }

    int getRightPadding() {
        return mRightPadding;
    }

    int getBottomPadding() {
        return mBottomPadding;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // TODO: currently ignoring padding

        int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);

        int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSpecSize =  MeasureSpec.getSize(heightMeasureSpec);

        if (widthSpecMode == MeasureSpec.UNSPECIFIED || heightSpecMode == MeasureSpec.UNSPECIFIED) {
            throw new RuntimeException("CellLayout cannot have UNSPECIFIED dimensions");
        }

        final int cellWidth = mCellWidth;
        final int cellHeight = mCellHeight;

        if (mOccupied == null) {
            mOccupied = new boolean[mCountX][mCountY];
        }

        int numWidthGaps = mCountX - 1;
        int numHeightGaps = mCountY - 1;

        int vSpaceLeft = heightSpecSize - mTopPadding
                - mBottomPadding - (cellHeight * mCountY);
        mHeightGap = vSpaceLeft / numHeightGaps;

        int hSpaceLeft = widthSpecSize - mLeftPadding
                - mRightPadding - (cellWidth * mCountX);
        mWidthGap = hSpaceLeft / numWidthGaps;

        int count = getChildCount();

        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.setup(cellWidth, cellHeight, mWidthGap, mHeightGap,
                    mLeftPadding, mTopPadding);

            int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(lp.width,
                    MeasureSpec.EXACTLY);
            int childheightMeasureSpec = MeasureSpec.makeMeasureSpec(lp.height,
                    MeasureSpec.EXACTLY);

            child.measure(childWidthMeasureSpec, childheightMeasureSpec);
        }

        setMeasuredDimension(widthSpecSize, heightSpecSize);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int count = getChildCount();

        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {

                CellLayout.LayoutParams lp = (CellLayout.LayoutParams) child.getLayoutParams();

                int childLeft = lp.x;
                int childTop = lp.y;
                child.layout(childLeft, childTop, childLeft + lp.width, childTop + lp.height);

                if (lp.dropped) {
                    lp.dropped = false;

                    final int[] cellXY = mTmpCellXY;
                    getLocationOnScreen(cellXY);
                    mWallpaperManager.sendWallpaperCommand(getWindowToken(), "android.home.drop",
                            cellXY[0] + childLeft + lp.width / 2,
                            cellXY[1] + childTop + lp.height / 2, 0, null);
                }
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mLayoutRect.set(0, 0, w, h);
        mDimmedBitmapRect.set(0, 0, (int) (DIMMED_BITMAP_SCALE * w), (int) (DIMMED_BITMAP_SCALE * h));
    }

    @Override
    protected void setChildrenDrawingCacheEnabled(boolean enabled) {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View view = getChildAt(i);
            view.setDrawingCacheEnabled(enabled);
            // Update the drawing caches
            view.buildDrawingCache(true);
        }
    }

    @Override
    protected void setChildrenDrawnWithCacheEnabled(boolean enabled) {
        super.setChildrenDrawnWithCacheEnabled(enabled);
    }

    public float getDimmedBitmapAlpha() {
        return mDimmedBitmapAlpha;
    }

    public void setDimmedBitmapAlpha(float alpha) {
        // If we're dimming the screen after it was not dimmed, refresh
        // to allow for updated widgets. We don't continually refresh it
        // after this point, however, as an optimization
        if (mDimmedBitmapAlpha == 0.0f && alpha > 0.0f) {
            updateDimmedBitmap();
        }
        mDimmedBitmapAlpha = alpha;
        setChildrenAlpha(1.0f - mDimmedBitmapAlpha);
    }

    private void setChildrenAlpha(float alpha) {
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setAlpha(alpha);
        }
    }

    public void updateDimmedBitmap() {
        if (mDimmedBitmap == null) {
            mDimmedBitmap = Bitmap.createBitmap((int) (getWidth() * DIMMED_BITMAP_SCALE),
                    (int) (getHeight() * DIMMED_BITMAP_SCALE), Bitmap.Config.ARGB_8888);
            mDimmedBitmapCanvas = new Canvas(mDimmedBitmap);
            mDimmedBitmapCanvas.scale(DIMMED_BITMAP_SCALE, DIMMED_BITMAP_SCALE);
        }
        // clear the canvas
        mDimmedBitmapCanvas.drawColor(0x00000000, PorterDuff.Mode.CLEAR);

        // draw the screen into the bitmap
        // just for drawing to the bitmap, make all the items on the screen opaque
        setChildrenAlpha(1.0f);
        dispatchDraw(mDimmedBitmapCanvas);
        setChildrenAlpha(1.0f - mDimmedBitmapAlpha);

        // make the bitmap 'dimmed' ie colored regions are dark grey,
        // the rest is light grey
        // We draw grey to the whole bitmap, but filter where we draw based on
        // what regions are transparent or not (SRC_OUT), causing the intended effect

        // First, draw light grey everywhere in the background (currently transparent) regions
        // This will leave the regions with the widgets as mostly transparent
        mDimmedBitmapCanvas.drawColor(0xCCCCCCCC, PorterDuff.Mode.SRC_OUT);
        // Now, fill the the remaining transparent regions with dark grey
        mDimmedBitmapCanvas.drawColor(0xCC333333, PorterDuff.Mode.SRC_OUT);
    }

    private boolean isVacant(int originX, int originY, int spanX, int spanY) {
        for (int i = 0; i < spanY; i++) {
            if (!isRowEmpty(originY + i, originX, originX + spanX - 1, mOccupied)) {
                return false;
            }
        }
        return true;
    }

    public View getChildAt(int x, int y) {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if ((lp.cellX <= x) && (x < lp.cellX + lp.cellHSpan) &&
                    (lp.cellY <= y) && (y < lp.cellY + lp.cellHSpan)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Estimate the size that a child with the given dimensions will take in the layout.
     */
    void estimateChildSize(int minWidth, int minHeight, int[] result) {
        // Assuming it's placed at 0, 0, find where the bottom right cell will land
        rectToCell(minWidth, minHeight, result);

        // Then figure out the rect it will occupy
        cellToRect(0, 0, result[0], result[1], mRectF);
        result[0] = (int)mRectF.width();
        result[1] = (int)mRectF.height();
    }

    /**
     * Estimate where the top left cell of the dragged item will land if it is dropped.
     *
     * @param originX The X value of the top left corner of the item
     * @param originY The Y value of the top left corner of the item
     * @param spanX The number of horizontal cells that the item spans
     * @param spanY The number of vertical cells that the item spans
     * @param result The estimated drop cell X and Y.
     */
    void estimateDropCell(int originX, int originY, int spanX, int spanY, int[] result) {
        final int countX = mCountX;
        final int countY = mCountY;

        pointToCellRounded(originX, originY, result);

        // If the item isn't fully on this screen, snap to the edges
        int rightOverhang = result[0] + spanX - countX;
        if (rightOverhang > 0) {
            result[0] -= rightOverhang; // Snap to right
        }
        result[0] = Math.max(0, result[0]); // Snap to left
        int bottomOverhang = result[1] + spanY - countY;
        if (bottomOverhang > 0) {
            result[1] -= bottomOverhang; // Snap to bottom
        }
        result[1] = Math.max(0, result[1]); // Snap to top
    }

    void visualizeDropLocation(View view, int originX, int originY, int spanX, int spanY) {
        final int[] originCell = mDragCell;
        final int[] cellXY = mTmpCellXY;
        estimateDropCell(originX, originY, spanX, spanY, cellXY);

        // Only recalculate the bounding rect when necessary
        if (!Arrays.equals(cellXY, originCell)) {
            originCell[0] = cellXY[0];
            originCell[1] = cellXY[1];

            // Find the top left corner of the rect the object will occupy
            final int[] topLeft = mTmpCellXY;
            cellToPoint(originCell[0], originCell[1], topLeft);
            final int left = topLeft[0];
            final int top = topLeft[1];

            // Now find the bottom right
            final int[] bottomRight = mTmpCellXY;
            cellToPoint(originCell[0] + spanX - 1, originCell[1] + spanY - 1, bottomRight);
            bottomRight[0] += mCellWidth;
            bottomRight[1] += mCellHeight;

            final int countX = mCountX;
            final int countY = mCountY;
            // TODO: It's not necessary to do this every time, but it's not especially expensive
            findOccupiedCells(countX, countY, mOccupied, view, false);

            boolean vacant = isVacant(originCell[0], originCell[1], spanX, spanY);
            mDragRectDrawable = vacant ? mVacantDrawable : mOccupiedDrawable;

            // mDragRect will be rendered in onDraw()
            mDragRect.set(left, top, bottomRight[0], bottomRight[1]);
            invalidate();
        }
    }

    /**
     * Find a vacant area that will fit the given bounds nearest the requested
     * cell location. Uses Euclidean distance to score multiple vacant areas.
     *
     * @param pixelX The X location at which you want to search for a vacant area.
     * @param pixelY The Y location at which you want to search for a vacant area.
     * @param spanX Horizontal span of the object.
     * @param spanY Vertical span of the object.
     * @param vacantCells Pre-computed set of vacant cells to search.
     * @param recycle Previously returned value to possibly recycle.
     * @return The X, Y cell of a vacant area that can contain this object,
     *         nearest the requested location.
     */
    int[] findNearestVacantArea(int pixelX, int pixelY, int spanX, int spanY,
            CellInfo vacantCells, int[] recycle) {

        // Keep track of best-scoring drop area
        final int[] bestXY = recycle != null ? recycle : new int[2];
        double bestDistance = Double.MAX_VALUE;

        // Bail early if vacant cells aren't valid
        if (!vacantCells.valid) {
            return null;
        }

        // Look across all vacant cells for best fit
        final int size = vacantCells.vacantCells.size();
        for (int i = 0; i < size; i++) {
            final CellInfo.VacantCell cell = vacantCells.vacantCells.get(i);

            // Reject if vacant cell isn't our exact size
            if (cell.spanX != spanX || cell.spanY != spanY) {
                continue;
            }

            // Score is distance from requested pixel to the top left of each cell
            final int[] cellXY = mTmpCellXY;
            cellToPoint(cell.cellX, cell.cellY, cellXY);

            double distance = Math.sqrt(Math.pow(cellXY[0] - pixelX, 2)
                    + Math.pow(cellXY[1] - pixelY, 2));
            if (distance <= bestDistance) {
                bestDistance = distance;
                bestXY[0] = cell.cellX;
                bestXY[1] = cell.cellY;
            }
        }

        // Return null if no suitable location found
        if (bestDistance < Double.MAX_VALUE) {
            return bestXY;
        } else {
            return null;
        }
    }

    /**
     * Called when a drag and drop operation has finished (successfully or not).
     */
    void onDragComplete() {
        // Invalidate the drag data
        mDragCell[0] = -1;
        mDragCell[1] = -1;

        mDragRect.setEmpty();
        invalidate();
    }

    /**
     * Mark a child as having been dropped.
     *
     * @param child The child that is being dropped
     */
    void onDropChild(View child) {
        if (child != null) {
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.isDragging = false;
            lp.dropped = true;
            mDragRect.setEmpty();
            child.requestLayout();
        }
        onDragComplete();
    }

    void onDropAborted(View child) {
        if (child != null) {
            ((LayoutParams) child.getLayoutParams()).isDragging = false;
        }
        onDragComplete();
    }

    /**
     * Start dragging the specified child
     *
     * @param child The child that is being dragged
     */
    void onDragChild(View child) {
        LayoutParams lp = (LayoutParams) child.getLayoutParams();
        lp.isDragging = true;
        mDragRect.setEmpty();
    }

    /**
     * Computes a bounding rectangle for a range of cells
     *
     * @param cellX X coordinate of upper left corner expressed as a cell position
     * @param cellY Y coordinate of upper left corner expressed as a cell position
     * @param cellHSpan Width in cells
     * @param cellVSpan Height in cells
     * @param resultRect Rect into which to put the results
     */
    public void cellToRect(int cellX, int cellY, int cellHSpan, int cellVSpan, RectF resultRect) {
        final int cellWidth = mCellWidth;
        final int cellHeight = mCellHeight;
        final int widthGap = mWidthGap;
        final int heightGap = mHeightGap;

        final int hStartPadding = getLeftPadding();
        final int vStartPadding = getTopPadding();

        int width = cellHSpan * cellWidth + ((cellHSpan - 1) * widthGap);
        int height = cellVSpan * cellHeight + ((cellVSpan - 1) * heightGap);

        int x = hStartPadding + cellX * (cellWidth + widthGap);
        int y = vStartPadding + cellY * (cellHeight + heightGap);

        resultRect.set(x, y, x + width, y + height);
    }

    /**
     * Computes the required horizontal and vertical cell spans to always
     * fit the given rectangle.
     *
     * @param width Width in pixels
     * @param height Height in pixels
     * @param result An array of length 2 in which to store the result (may be null).
     */
    public int[] rectToCell(int width, int height, int[] result) {
        // Always assume we're working with the smallest span to make sure we
        // reserve enough space in both orientations.
        final Resources resources = getResources();
        int actualWidth = resources.getDimensionPixelSize(R.dimen.workspace_cell_width);
        int actualHeight = resources.getDimensionPixelSize(R.dimen.workspace_cell_height);
        int smallerSize = Math.min(actualWidth, actualHeight);

        // Always round up to next largest cell
        int spanX = (width + smallerSize) / smallerSize;
        int spanY = (height + smallerSize) / smallerSize;

        if (result == null) {
            return new int[] { spanX, spanY };
        }
        result[0] = spanX;
        result[1] = spanY;
        return result;
    }

    /**
     * Find the first vacant cell, if there is one.
     *
     * @param vacant Holds the x and y coordinate of the vacant cell
     * @param spanX Horizontal cell span.
     * @param spanY Vertical cell span.
     *
     * @return True if a vacant cell was found
     */
    public boolean getVacantCell(int[] vacant, int spanX, int spanY) {
        final int xCount = mCountX;
        final int yCount = mCountY;
        final boolean[][] occupied = mOccupied;

        findOccupiedCells(xCount, yCount, occupied, null, true);

        return findVacantCell(vacant, spanX, spanY, xCount, yCount, occupied);
    }

    static boolean findVacantCell(int[] vacant, int spanX, int spanY,
            int xCount, int yCount, boolean[][] occupied) {

        for (int x = 0; x < xCount; x++) {
            for (int y = 0; y < yCount; y++) {
                boolean available = !occupied[x][y];
out:            for (int i = x; i < x + spanX - 1 && x < xCount; i++) {
                    for (int j = y; j < y + spanY - 1 && y < yCount; j++) {
                        available = available && !occupied[i][j];
                        if (!available) break out;
                    }
                }

                if (available) {
                    vacant[0] = x;
                    vacant[1] = y;
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Update the array of occupied cells (mOccupied), and return a flattened copy of the array.
     */
    boolean[] getOccupiedCellsFlattened() {
        final int xCount = mCountX;
        final int yCount = mCountY;
        final boolean[][] occupied = mOccupied;

        findOccupiedCells(xCount, yCount, occupied, null, true);

        final boolean[] flat = new boolean[xCount * yCount];
        for (int y = 0; y < yCount; y++) {
            for (int x = 0; x < xCount; x++) {
                flat[y * xCount + x] = occupied[x][y];
            }
        }

        return flat;
    }

    /**
     * Update the array of occupied cells.
     * @param ignoreView If non-null, the space occupied by this View is treated as vacant
     * @param ignoreFolders If true, a cell occupied by a Folder is treated as vacant
     */
    private void findOccupiedCells(
            int xCount, int yCount, boolean[][] occupied, View ignoreView, boolean ignoreFolders) {

        for (int x = 0; x < xCount; x++) {
            for (int y = 0; y < yCount; y++) {
                occupied[x][y] = false;
            }
        }

        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if ((ignoreFolders && child instanceof Folder) || child.equals(ignoreView)) {
                continue;
            }
            LayoutParams lp = (LayoutParams) child.getLayoutParams();

            for (int x = lp.cellX; x < lp.cellX + lp.cellHSpan && x < xCount; x++) {
                for (int y = lp.cellY; y < lp.cellY + lp.cellVSpan && y < yCount; y++) {
                    occupied[x][y] = true;
                }
            }
        }
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new CellLayout.LayoutParams(getContext(), attrs);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof CellLayout.LayoutParams;
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new CellLayout.LayoutParams(p);
    }

    public static class CellLayoutAnimationController extends LayoutAnimationController {
        public CellLayoutAnimationController(Animation animation, float delay) {
            super(animation, delay);
        }

        @Override
        protected long getDelayForView(View view) {
            return (int) (Math.random() * 150);
        }
    }

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        /**
         * Horizontal location of the item in the grid.
         */
        @ViewDebug.ExportedProperty
        public int cellX;

        /**
         * Vertical location of the item in the grid.
         */
        @ViewDebug.ExportedProperty
        public int cellY;

        /**
         * Number of cells spanned horizontally by the item.
         */
        @ViewDebug.ExportedProperty
        public int cellHSpan;

        /**
         * Number of cells spanned vertically by the item.
         */
        @ViewDebug.ExportedProperty
        public int cellVSpan;

        /**
         * Is this item currently being dragged
         */
        public boolean isDragging;

        // X coordinate of the view in the layout.
        @ViewDebug.ExportedProperty
        int x;
        // Y coordinate of the view in the layout.
        @ViewDebug.ExportedProperty
        int y;

        boolean dropped;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
            cellHSpan = 1;
            cellVSpan = 1;
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
            cellHSpan = 1;
            cellVSpan = 1;
        }

        public LayoutParams(LayoutParams source) {
            super(source);
            this.cellX = source.cellX;
            this.cellY = source.cellY;
            this.cellHSpan = source.cellHSpan;
            this.cellVSpan = source.cellVSpan;
        }

        public LayoutParams(int cellX, int cellY, int cellHSpan, int cellVSpan) {
            super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            this.cellX = cellX;
            this.cellY = cellY;
            this.cellHSpan = cellHSpan;
            this.cellVSpan = cellVSpan;
        }

        public void setup(int cellWidth, int cellHeight, int widthGap, int heightGap,
                int hStartPadding, int vStartPadding) {

            final int myCellHSpan = cellHSpan;
            final int myCellVSpan = cellVSpan;
            final int myCellX = cellX;
            final int myCellY = cellY;

            width = myCellHSpan * cellWidth + ((myCellHSpan - 1) * widthGap) -
                    leftMargin - rightMargin;
            height = myCellVSpan * cellHeight + ((myCellVSpan - 1) * heightGap) -
                    topMargin - bottomMargin;

            x = hStartPadding + myCellX * (cellWidth + widthGap) + leftMargin;
            y = vStartPadding + myCellY * (cellHeight + heightGap) + topMargin;
        }

        public String toString() {
            return "(" + this.cellX + ", " + this.cellY + ")";
        }
    }

    static final class CellInfo implements ContextMenu.ContextMenuInfo {
        /**
         * See View.AttachInfo.InvalidateInfo for futher explanations about the
         * recycling mechanism. In this case, we recycle the vacant cells
         * instances because up to several hundreds can be instanciated when the
         * user long presses an empty cell.
         */
        static final class VacantCell {
            int cellX;
            int cellY;
            int spanX;
            int spanY;

            // We can create up to 523 vacant cells on a 4x4 grid, 100 seems
            // like a reasonable compromise given the size of a VacantCell and
            // the fact that the user is not likely to touch an empty 4x4 grid
            // very often
            private static final int POOL_LIMIT = 100;
            private static final Object sLock = new Object();

            private static int sAcquiredCount = 0;
            private static VacantCell sRoot;

            private VacantCell next;

            static VacantCell acquire() {
                synchronized (sLock) {
                    if (sRoot == null) {
                        return new VacantCell();
                    }

                    VacantCell info = sRoot;
                    sRoot = info.next;
                    sAcquiredCount--;

                    return info;
                }
            }

            void release() {
                synchronized (sLock) {
                    if (sAcquiredCount < POOL_LIMIT) {
                        sAcquiredCount++;
                        next = sRoot;
                        sRoot = this;
                    }
                }
            }

            @Override
            public String toString() {
                return "VacantCell[x=" + cellX + ", y=" + cellY + ", spanX="
                        + spanX + ", spanY=" + spanY + "]";
            }
        }

        View cell;
        int cellX;
        int cellY;
        int spanX;
        int spanY;
        int screen;
        boolean valid;

        final ArrayList<VacantCell> vacantCells = new ArrayList<VacantCell>(VacantCell.POOL_LIMIT);
        int maxVacantSpanX;
        int maxVacantSpanXSpanY;
        int maxVacantSpanY;
        int maxVacantSpanYSpanX;
        final Rect current = new Rect();

        void clearVacantCells() {
            final ArrayList<VacantCell> list = vacantCells;
            final int count = list.size();

            for (int i = 0; i < count; i++) {
                list.get(i).release();
            }

            list.clear();
        }

        void findVacantCellsFromOccupied(boolean[] occupied, int xCount, int yCount) {
            if (cellX < 0 || cellY < 0) {
                maxVacantSpanX = maxVacantSpanXSpanY = Integer.MIN_VALUE;
                maxVacantSpanY = maxVacantSpanYSpanX = Integer.MIN_VALUE;
                clearVacantCells();
                return;
            }

            final boolean[][] unflattened = new boolean[xCount][yCount];
            for (int y = 0; y < yCount; y++) {
                for (int x = 0; x < xCount; x++) {
                    unflattened[x][y] = occupied[y * xCount + x];
                }
            }
            CellLayout.findIntersectingVacantCells(this, cellX, cellY, xCount, yCount, unflattened);
        }

        /**
         * This method can be called only once! Calling #findVacantCellsFromOccupied will
         * restore the ability to call this method.
         *
         * Finds the upper-left coordinate of the first rectangle in the grid that can
         * hold a cell of the specified dimensions.
         *
         * @param cellXY The array that will contain the position of a vacant cell if such a cell
         *               can be found.
         * @param spanX The horizontal span of the cell we want to find.
         * @param spanY The vertical span of the cell we want to find.
         *
         * @return True if a vacant cell of the specified dimension was found, false otherwise.
         */
        boolean findCellForSpan(int[] cellXY, int spanX, int spanY) {
            return findCellForSpan(cellXY, spanX, spanY, true);
        }

        boolean findCellForSpan(int[] cellXY, int spanX, int spanY, boolean clear) {
            final ArrayList<VacantCell> list = vacantCells;
            final int count = list.size();

            boolean found = false;

            // return the span represented by the CellInfo only there is no view there
            //   (this.cell == null) and there is enough space
            if (this.cell == null && this.spanX >= spanX && this.spanY >= spanY) {
                cellXY[0] = cellX;
                cellXY[1] = cellY;
                found = true;
            }

            // Look for an exact match first
            for (int i = 0; i < count; i++) {
                VacantCell cell = list.get(i);
                if (cell.spanX == spanX && cell.spanY == spanY) {
                    cellXY[0] = cell.cellX;
                    cellXY[1] = cell.cellY;
                    found = true;
                    break;
                }
            }

            // Look for the first cell large enough
            for (int i = 0; i < count; i++) {
                VacantCell cell = list.get(i);
                if (cell.spanX >= spanX && cell.spanY >= spanY) {
                    cellXY[0] = cell.cellX;
                    cellXY[1] = cell.cellY;
                    found = true;
                    break;
                }
            }

            if (clear) {
                clearVacantCells();
            }

            return found;
        }

        @Override
        public String toString() {
            return "Cell[view=" + (cell == null ? "null" : cell.getClass())
                    + ", x=" + cellX + ", y=" + cellY + "]";
        }
    }

    public boolean lastDownOnOccupiedCell() {
        return mLastDownOnOccupiedCell;
    }
}
