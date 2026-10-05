package com.army.marathon.service;

import com.army.marathon.model.Race;
import org.springframework.stereotype.Component;

/**
 * The calculation mode the operator currently has selected on Live Tracking.
 *
 * <p>Race.calculationMode only changes when results are actually calculated, and the
 * Results tab builds its columns from it - so persisting every dropdown change there
 * would make already-calculated results render under the wrong mode. Other screens that
 * need to react to the <em>selected</em> mode (the Configuration page's Start/Finish
 * rules) read it from here instead.
 */
@Component
public class CalculationModeSelection {

    private volatile Race.CalculationMode selected;

    /** Null until Live Tracking has been opened; callers fall back to the race's stored mode. */
    public Race.CalculationMode get() {
        return selected;
    }

    public void set(Race.CalculationMode mode) {
        this.selected = mode;
    }
}
