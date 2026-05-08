package com.heikinashi.monitoring.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Snapshot of all three Heikin Ashi pattern configurations (CLAUDE.md §5 / §8). */
public record PatternsConfig(ColorChange colorChange, StrongCandle strongCandle, Doji doji) {

    public PatternsConfig {
        Objects.requireNonNull(colorChange, "colorChange");
        Objects.requireNonNull(strongCandle, "strongCandle");
        Objects.requireNonNull(doji, "doji");
    }

    public record ColorChange(boolean enabled, int minStreakLength) {}

    public record StrongCandle(boolean enabled, BigDecimal wickTolerance, BigDecimal minBodyRatio) {
        public StrongCandle {
            Objects.requireNonNull(wickTolerance, "wickTolerance");
            Objects.requireNonNull(minBodyRatio, "minBodyRatio");
        }
    }

    public record Doji(boolean enabled, BigDecimal maxBodyRatio) {
        public Doji {
            Objects.requireNonNull(maxBodyRatio, "maxBodyRatio");
        }
    }

    public static PatternsConfig defaults() {
        return new PatternsConfig(
                new ColorChange(false, 3),
                new StrongCandle(false, new BigDecimal("0.001"), new BigDecimal("0.5")),
                new Doji(false, new BigDecimal("0.1")));
    }

    public PatternsConfig withColorChange(ColorChange v) {
        return new PatternsConfig(v, strongCandle, doji);
    }

    public PatternsConfig withStrongCandle(StrongCandle v) {
        return new PatternsConfig(colorChange, v, doji);
    }

    public PatternsConfig withDoji(Doji v) {
        return new PatternsConfig(colorChange, strongCandle, v);
    }
}
