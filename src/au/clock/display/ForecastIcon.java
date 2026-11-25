package au.clock.display;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

final class ForecastIcon extends Drawable {
    private final ForecastWeather forecast;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    ForecastIcon(ForecastWeather forecast, int color) {
        this.forecast = forecast;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.65f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        switch (forecast.condition) {
            case CLEAR:
                sky(canvas);
                break;
            case PARTLY_CLOUDY:
                canvas.save();
                canvas.translate(9, 0);
                canvas.scale(.58f, .58f);
                sky(canvas);
                canvas.restore();
                cloud(canvas, 3);
                break;
            case CLOUDY:
                cloud(canvas, 2);
                break;
            case FOG:
                cloud(canvas, 0);
                canvas.drawLine(4, 18, 20, 18, paint);
                canvas.drawLine(7, 21, 17, 21, paint);
                break;
            case RAIN:
                cloud(canvas, 0);
                for (int x = 7; x <= 17; x += 5) canvas.drawLine(x, 18, x - 1.5f, 21, paint);
                break;
            case SNOW:
                cloud(canvas, 0);
                for (int x = 7; x <= 17; x += 5) canvas.drawCircle(x, x == 12 ? 21 : 19, .7f, paint);
                break;
            case STORM:
                cloud(canvas, -1);
                path.reset();
                path.moveTo(13, 15);
                path.lineTo(10, 19);
                path.lineTo(14, 19);
                path.lineTo(11, 23);
                canvas.drawPath(path, paint);
                break;
        }
        canvas.restore();
    }

    private void sky(Canvas canvas) {
        if (!forecast.day) {
            path.reset();
            path.moveTo(12, 3);
            path.cubicTo(1, 4, 1, 19, 11, 21);
            path.cubicTo(16, 22, 20, 18, 21, 14);
            path.cubicTo(13, 17, 8, 10, 12, 3);
            canvas.drawPath(path, paint);
            return;
        }
        canvas.drawCircle(12, 12, 4.25f, paint);
        for (int ray = 0; ray < 8; ray++) {
            canvas.save();
            canvas.rotate(ray * 45, 12, 12);
            canvas.drawLine(12, 2, 12, 4.5f, paint);
            canvas.restore();
        }
    }

    private void cloud(Canvas canvas, float offset) {
        canvas.save();
        canvas.translate(0, offset);
        path.reset();
        path.moveTo(7, 15);
        path.cubicTo(1, 15, 1, 7, 7, 7);
        path.cubicTo(8, 2, 16, 2, 17, 8);
        path.cubicTo(23, 7, 24, 15, 18, 15);
        path.close();
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
