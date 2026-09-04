package com.jartrans.ui;

import javafx.geometry.Orientation;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.skin.ScrollBarSkin;
import javafx.scene.layout.Region;

/**
 * 滚动条皮肤：强制滑块（thumb）保底最小长度且不越出轨道。
 *
 * <p>JavaFX 默认 ScrollBarSkin 的滑块最小长度由内部常量硬编码（约 1.5 倍滚动条
 * 粗细，12px 宽时仅 18px 上下），CSS 无属性可调；当条目非常多时滑块按比例会缩成
 * 几乎看不见的细线。</p>
 *
 * <p>实现要点：默认皮肤对滑块尺寸与位置是分开管理的——布局时按内部小长度
 * resize 滑块，滚动/拖动时用 {@code setTranslate} 移动滑块（内部缓存的小长度做
 * 滑程，位置与滑块实际尺寸脱节）。本皮肤在每次布局以及 value/max 变化
 * （拖动、滚轮、赋值）后：①把滑块 resize 到 ≥ {@link #MIN_THUMB_LENGTH}；
 * ②用 {@code setTranslate} 按「实际滑块尺寸 + 当前 value」重新定位到轨道范围内，
 * 覆盖默认内部位置（监听注册在父构造之后，晚于默认监听，最终生效）。</p>
 *
 * <p>挂载方式（注意不能代码里 setSkin——UA 样式表的 -fx-skin 会在每次 CSS
 * pass 把它还原成默认皮肤）：由 resources 的 scrollbar-skin.css 通过
 * {@code .scroll-bar { -fx-skin: "com.jartrans.ui.MinThumbScrollBarSkin"; } }
 * 以 author 样式覆盖（author &gt; UA），随 Theme.attach 全场景生效。</p>
 */
public class MinThumbScrollBarSkin extends ScrollBarSkin {

    /** 滑块保底长度（px）。 */
    public static final double MIN_THUMB_LENGTH = 32;

    public MinThumbScrollBarSkin(ScrollBar scrollBar) {
        super(scrollBar);
        // 默认皮肤只在布局时算滑块尺寸、滚动时只 setTranslate 位置；
        // 这些监听注册在父构造之后 → 每次变化我们都最后校正，保证不越界
        scrollBar.valueProperty().addListener((o, ov, nv) -> fixThumb());
        scrollBar.maxProperty().addListener((o, ov, nv) -> fixThumb());
    }

    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        super.layoutChildren(x, y, w, h);
        fixThumb();
    }

    /** 放大滑块到保底长度，并按 value 重定位到轨道内。 */
    private void fixThumb() {
        ScrollBar bar = (ScrollBar) getSkinnable();
        Region track = (Region) bar.lookup(".track");
        Region thumb = (Region) bar.lookup(".thumb");
        if (track == null || thumb == null) {
            return;
        }
        if (bar.getOrientation() == Orientation.VERTICAL) {
            double trackLen = track.getHeight();
            if (trackLen <= 0) {
                return;
            }
            double len = thumb.getHeight();
            double newLen = Math.min(Math.max(len, MIN_THUMB_LENGTH), trackLen);
            if (newLen > len) {
                thumb.resize(thumb.getWidth(), newLen);
            }
            double y = position(track.getLayoutY(), trackLen, thumb.getHeight(), bar);
            thumb.setTranslateY(y - thumb.getLayoutY());
        } else {
            double trackLen = track.getWidth();
            if (trackLen <= 0) {
                return;
            }
            double len = thumb.getWidth();
            double newLen = Math.min(Math.max(len, MIN_THUMB_LENGTH), trackLen);
            if (newLen > len) {
                thumb.resize(newLen, thumb.getHeight());
            }
            double x = position(track.getLayoutX(), trackLen, thumb.getWidth(), bar);
            thumb.setTranslateX(x - thumb.getLayoutX());
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
