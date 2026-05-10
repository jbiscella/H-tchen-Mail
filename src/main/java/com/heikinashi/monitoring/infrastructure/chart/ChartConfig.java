package com.heikinashi.monitoring.infrastructure.chart;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@ConfigurationProperties("monitoring.chart")
public class ChartConfig {

    @Min(1)
    @NotNull
    private int lookbackBars = 30;

    @Min(100)
    @NotNull
    private int widthPx = 900;

    @Min(100)
    @NotNull
    private int heightPx = 500;

    private boolean showVolume = false;

    public int getLookbackBars() {
        return lookbackBars;
    }

    public void setLookbackBars(int lookbackBars) {
        this.lookbackBars = lookbackBars;
    }

    public int getWidthPx() {
        return widthPx;
    }

    public void setWidthPx(int widthPx) {
        this.widthPx = widthPx;
    }

    public int getHeightPx() {
        return heightPx;
    }

    public void setHeightPx(int heightPx) {
        this.heightPx = heightPx;
    }

    public boolean isShowVolume() {
        return showVolume;
    }

    public void setShowVolume(boolean showVolume) {
        this.showVolume = showVolume;
    }
}
