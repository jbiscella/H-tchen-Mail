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

    @Min(0)
    private int smaPeriod = 10;

    @Min(0)
    private int emaPeriod = 20;

    private boolean showRsi = true;

    @Min(1)
    private int rsiPeriod = 14;

    public boolean isShowVolume() {
        return showVolume;
    }

    public void setShowVolume(boolean showVolume) {
        this.showVolume = showVolume;
    }

    public int getSmaPeriod() {
        return smaPeriod;
    }

    public void setSmaPeriod(int smaPeriod) {
        this.smaPeriod = smaPeriod;
    }

    public int getEmaPeriod() {
        return emaPeriod;
    }

    public void setEmaPeriod(int emaPeriod) {
        this.emaPeriod = emaPeriod;
    }

    public boolean isShowRsi() {
        return showRsi;
    }

    public void setShowRsi(boolean showRsi) {
        this.showRsi = showRsi;
    }

    public int getRsiPeriod() {
        return rsiPeriod;
    }

    public void setRsiPeriod(int rsiPeriod) {
        this.rsiPeriod = rsiPeriod;
    }
}
