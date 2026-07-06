package com.formacraft.common.alignment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BayRhythm(
        @JsonProperty("side_bays") List<BaySpec> sideBays,
        @JsonProperty("bay_count") Integer bayCount,
        @JsonProperty("bay_width") Integer bayWidth
) {
    public boolean hasContent() {
        return (sideBays != null && !sideBays.isEmpty()) || (bayCount != null && bayCount > 0);
    }

    public int totalSpan() {
        if (sideBays != null && !sideBays.isEmpty()) {
            int sum = 0;
            for (BaySpec bay : sideBays) {
                if (bay != null && bay.width() > 0) {
                    sum += bay.width();
                }
            }
            return sum;
        }
        if (bayCount != null && bayCount > 0 && bayWidth != null && bayWidth > 0) {
            return bayCount * bayWidth;
        }
        return 0;
    }
}
