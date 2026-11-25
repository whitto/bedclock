package au.clock.display;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

final class FlipClockView extends View {
    private static final float TILE_WIDTH = .76f;
    private static final float COLON_WIDTH = .23f;
    private static final float GAP = .055f;
    private static final float TILE_HEIGHT = 1.02f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect glyphBounds = new Rect();
    private final RectF tile = new RectF();
    private String reading = "";
    private String previous = "";
    private float size = 1;
    private float progress = 1;
    private float baseline;
    private int digitColor = 0xfff6f3ff;
    private boolean lightBackground;
    private ValueAnimator animation;

    FlipClockView(Context context) {
        super(context);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    static float widthAtUnitSize(String value) {
        float width = 0;
        for (int i = 0; i < value.length(); i++) {
            width += value.charAt(i) == ':' ? COLON_WIDTH : TILE_WIDTH;
        }
        return width + Math.max(0, value.length() - 1) * GAP;
    }

    static float heightAtUnitSize() {
        return TILE_HEIGHT;
    }

    void setFace(Typeface face, int color, boolean light) {
        if (paint.getTypeface() != face) {
            paint.setTypeface(face);
            updateBaseline();
        }
        if (digitColor != color || lightBackground != light) {
            digitColor = color;
            lightBackground = light;
            invalidate();
        }
    }

    void setDigitSize(float value) {
        if (size == value) return;
        size = value;
        paint.setTextSize(value);
        updateBaseline();
        requestLayout();
    }

    private void updateBaseline() {
        paint.getTextBounds("0123456789", 0, 10, glyphBounds);
        baseline = size * TILE_HEIGHT / 2f - (glyphBounds.top + glyphBounds.bottom) / 2f;
        invalidate();
    }

    void setReading(String value) {
        if (reading.equals(value)) return;
        boolean sameWidth = reading.length() == value.length();
        if (animation != null) animation.cancel();
        previous = reading;
        reading = value;
        setContentDescription(value);
        if (!sameWidth) requestLayout();
        if (!previous.isEmpty() && isShown()) {
            progress = 0;
            animation = ValueAnimator.ofFloat(0f, 1f);
            animation.setDuration(420);
            animation.setInterpolator(new LinearInterpolator());
            animation.addUpdateListener(frame -> {
                progress = (float) frame.getAnimatedValue();
                invalidate();
            });
            animation.start();
        } else {
            progress = 1;
            invalidate();
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = (int) Math.ceil(widthAtUnitSize(reading) * size);
        int height = (int) Math.ceil(TILE_HEIGHT * size);
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onDetachedFromWindow() {
        if (animation != null) animation.cancel();
        progress = 1;
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float x = 0;
        float height = TILE_HEIGHT * size;
        for (int i = 0; i < reading.length(); i++) {
            char digit = reading.charAt(i);
            float width = (digit == ':' ? COLON_WIDTH : TILE_WIDTH) * size;
            if (digit == ':') {
                paint.setColor(digitColor);
                float radius = size * .033f;
                canvas.drawCircle(x + width / 2, height * .34f, radius, paint);
                canvas.drawCircle(x + width / 2, height * .66f, radius, paint);
            } else {
                int previousIndex = i + previous.length() - reading.length();
                char oldDigit = progress == 1 ? digit : previousIndex < 0 ? ' ' : previous.charAt(previousIndex);
                drawTile(canvas, x, width, height, oldDigit, digit);
            }
            x += width + GAP * size;
        }
    }

    private void drawTile(Canvas canvas, float x, float width, float height, char oldDigit, char newDigit) {
        float mid = height / 2;
        tile.set(x, 0, x + width, height);
        if (oldDigit == newDigit) {
            drawHalf(canvas, x, width, height, newDigit, true);
            drawHalf(canvas, x, width, height, newDigit, false);
        } else {
            boolean upper = progress < .5f;
            float rotation = (float) Math.cos(Math.PI * progress);
            float foldedHeight = mid * Math.abs(rotation);
            canvas.save();
            // Clip away the stationary glyph where the translucent flap covers it.
            canvas.clipOutRect(x, upper ? mid - foldedHeight : mid,
                    x + width, upper ? mid : mid + foldedHeight);
            drawHalf(canvas, x, width, height, newDigit, true);
            drawHalf(canvas, x, width, height, oldDigit, false);
            canvas.restore();
            canvas.save();
            canvas.scale(1f, Math.abs(rotation), x + width / 2, mid);
            drawHalf(canvas, x, width, height, upper ? oldDigit : newDigit, upper);
            canvas.restore();
        }
        paint.setColor(lightBackground ? 0x70000000 : 0x90000000);
        float seam = Math.max(1f, size * .009f);
        canvas.drawRect(x, mid - seam / 2, x + width, mid + seam / 2, paint);
    }

    private void drawHalf(Canvas canvas, float x, float width, float height, char digit, boolean upper) {
        float mid = height / 2;
        canvas.save();
        canvas.clipRect(x, upper ? 0 : mid, x + width, upper ? mid : height);
        paint.setColor(lightBackground
                ? (upper ? 0x0d000000 : 0x18000000)
                : (upper ? 0x20ffffff : 0x12ffffff));
        canvas.drawRoundRect(tile, size * .045f, size * .045f, paint);
        paint.setColor(digitColor);
        canvas.drawText(String.valueOf(digit), x + width / 2, baseline, paint);
        canvas.restore();
    }
}
