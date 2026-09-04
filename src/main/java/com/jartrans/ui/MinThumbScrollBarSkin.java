package com.jartrans.ui;

import javafx.geometry.Orientation;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.skin.ScrollBarSkin;
import javafx.scene.layout.Region;

/**
 * 滚动条皮肤：强制滑块（thumb）保底最小长度。
 *
 * <p>JavaFX 默认 ScrollBarSkin 的滑块最小长度由内部常量硬编码（约 1.5 倍滚动条
 * 粗细，12px 宽时仅 18px 上下），CSS 无属性可调；当条目非常多时滑块按比例会缩成
 * 几乎看不见的细线。本皮肤在每次布局后把滑块校正到 {@link #MIN_THUMB_LENGTH}
 * （内容足够多时）并按当前 value 重新定位，保证长列表滚动条始终可见、可点。</p>
 *
 * <p>挂载方式（注意不能代码里 setSkin——UA 样式表的 -fx-skin 会在每次 CSS
 * pass 把它还原成默认皮肤）：由 resources 的 scrollbar-skin.css 通过
 * {@code .scroll-bar { -fx-skin: "com.jartrans.ui.MinThumbScrollBarSkin"; } }
 * 以 author 样式覆盖（author &gt; UA），随 Theme.attach 全场景生效。</p>
 *
 * <p>说明：ScrollBarSkin 取自公开 API 包 javafx.scene.control.skin（JavaFX 17+
 * 已公开），无需 --add-exports。</p>
 */
public class MinThumbScrollBarSkin extends ScrollBarSkin {

    /** 滑块保底长度（px）。 */
    public static final double MIN_THUMB_LENGTH = 32;

    public MinThumbScrollBarSkin(ScrollBar scrollBar) {
        super(scrollBar);
    }

    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        super.layoutChildren(x, y, w, h);
        ScrollBar bar = (ScrollBar) getSkinnable();
        Region track = (Region) bar.lookup(".track");
        Region thumb = (Region) bar.lookup(".thumb");
        if (track == null || thumb == null) {
            return;
        }
        if (bar.getOrientation() == Orientation.VERTICAL) {
            double len = thumb.getHeight();
            double newLen = Math.min(Math.max(len, MIN_THUMB_LENGTH), track.getHeight());
            if (newLen > len) {
                thumb.resize(thumb.getWidth(), newLen);
                thumb.relocate(thumb.getLayoutX(), position(track.getLayoutY(),
                        track.getHeight(), newLen, bar));
            }
        } else {
            double len = thumb.getWidth();
            double newLen = Math.min(Math.max(len, MIN_THUMB_LENGTH), track.getWidth());
            if (newLen > len) {
                thumb.resize(newLen, thumb.getHeight());
                thumb.relocate(position(track.getLayoutX(), track.getWidth(), newLen, bar),
                        thumb.getLayoutY());
            }
        }
    }

    /** 滑块起点 = 轨道起点 + 滑行范围(轨道长-滑块长) × value/max 比例。 */
    private static double position(double trackStart, double trackLength,
                                   double thumbLength, ScrollBar bar) {
        double range = trackLength - thumbLength;
        if (range <= 0) {
            return trackStart;
        }
        double max = bar.getMax();
        if (max <= 0) {
            return trackStart;
        }
        double ratio = Math.max(0, Math.min(bar.getValue(), max)) / max;
        return trackStart + range * ratio;
    }
}
