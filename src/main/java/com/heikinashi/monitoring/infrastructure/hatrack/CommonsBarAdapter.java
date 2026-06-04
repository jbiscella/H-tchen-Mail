package com.heikinashi.monitoring.infrastructure.hatrack;

import com.heikinashi.monitoring.domain.HABar;
import com.heikinashi.monitoring.domain.OHLCBar;
import com.heikinashi.monitoring.domain.Timeframe;
import java.time.Instant;
import java.util.List;

/**
 * The single boundary between H-tchen's domain bar types
 * ({@code com.heikinashi.monitoring.domain.OHLCBar} / {@code HABar} / {@code Timeframe})
 * and ha-track's commons types ({@code org.hatrack.commons.*}).
 *
 * <p><b>Naming caution (homonyms).</b> ha-track and H-tchen share the type names
 * {@code OHLCBar}, {@code HABar} and {@code Timeframe}. To keep the boundary
 * unambiguous, this class imports only the <em>domain</em> types unqualified;
 * every commons type is written out as {@code org.hatrack.commons.X}. Per Block 11
 * this adapter is the ONLY production class allowed to import (or reference) both
 * families of bar types — every later block (12 HA, 13 ingest, 14 chart, 15
 * detection) routes its conversions through here.
 *
 * <p><b>Scale handling.</b> Both sides model prices as {@link java.math.BigDecimal}.
 * Conversions pass the very same {@code BigDecimal} instances through unchanged:
 * no {@code setScale}, no rounding, no {@code MathContext} is applied. Numeric
 * equality therefore holds by {@code compareTo} <em>and</em> by exact scale. Any
 * future need to re-scale must be made explicit here and asserted in a test —
 * never introduced silently.
 *
 * <p>The commons bar types carry only {@code time + prices}; H-tchen's carry
 * extra denormalized metadata (instrumentId, timeframe, source/ingestedAt,
 * computedAt). The {@code fromCommons} directions therefore require the caller to
 * supply that metadata, since it cannot be recovered from a commons bar alone.
 */
public final class CommonsBarAdapter {

    private CommonsBarAdapter() {}

    // ---- Timeframe -------------------------------------------------------

    /** Domain timeframe to commons timeframe via the shared wire vocabulary ("1d"/"1w"). */
    public static org.hatrack.commons.Timeframe toCommons(Timeframe tf) {
        return org.hatrack.commons.Timeframe.fromWire(tf.wire());
    }

    /** Commons timeframe back to the domain enum via the shared wire vocabulary. */
    public static Timeframe fromCommons(org.hatrack.commons.Timeframe tf) {
        return Timeframe.fromWire(tf.wire());
    }

    // ---- OHLC ------------------------------------------------------------

    /** Domain OHLC bar to a commons OHLC bar (prices passed through unchanged). */
    public static org.hatrack.commons.OHLCBar toCommons(OHLCBar bar) {
        return new org.hatrack.commons.OHLCBar(
                bar.barTime(), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
    }

    /** A chronological list of domain OHLC bars to a commons {@code OHLCSeries}. */
    public static org.hatrack.commons.OHLCSeries toCommonsOhlcSeries(List<OHLCBar> bars) {
        List<org.hatrack.commons.OHLCBar> converted =
                bars.stream().map(CommonsBarAdapter::toCommons).toList();
        return new org.hatrack.commons.OHLCSeries(converted);
    }

    /**
     * Commons OHLC bar back to a domain OHLC bar. The caller supplies the
     * denormalized metadata that commons does not model.
     */
    public static OHLCBar fromCommons(
            org.hatrack.commons.OHLCBar bar,
            String instrumentId,
            Timeframe timeframe,
            String source,
            Instant ingestedAt) {
        return new OHLCBar(
                instrumentId,
                timeframe,
                bar.time(),
                bar.open(),
                bar.high(),
                bar.low(),
                bar.close(),
                bar.volume(),
                source,
                ingestedAt);
    }

    // ---- Heikin Ashi -----------------------------------------------------

    /** Domain HA bar to a commons HA bar (values passed through unchanged). */
    public static org.hatrack.commons.HABar toCommons(HABar bar) {
        return new org.hatrack.commons.HABar(bar.barTime(), bar.haOpen(), bar.haHigh(), bar.haLow(), bar.haClose());
    }

    /** A chronological list of domain HA bars to a commons {@code HASeries}. */
    public static org.hatrack.commons.HASeries toCommonsHaSeries(List<HABar> bars) {
        List<org.hatrack.commons.HABar> converted =
                bars.stream().map(CommonsBarAdapter::toCommons).toList();
        return new org.hatrack.commons.HASeries(converted);
    }

    /**
     * Commons HA bar back to a domain HA bar. The caller supplies the
     * denormalized metadata that commons does not model.
     */
    public static HABar fromCommons(
            org.hatrack.commons.HABar bar, String instrumentId, Timeframe timeframe, Instant computedAt) {
        return new HABar(
                instrumentId,
                timeframe,
                bar.time(),
                bar.haOpen(),
                bar.haHigh(),
                bar.haLow(),
                bar.haClose(),
                computedAt);
    }
}
