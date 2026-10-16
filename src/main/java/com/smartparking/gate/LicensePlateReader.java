package com.smartparking.gate;

import com.smartparking.infrastructure.DomainException;
import java.util.Locale;

/** Pure input and clearance policy; it has no store or facade dependency. */
public final class LicensePlateReader {
    public String read(String rawPlate, int heightCm) {
        if (rawPlate == null) {
            throw new DomainException("License plate is required.");
        }
        String plate = rawPlate.trim().toUpperCase(Locale.ROOT);
        if (!plate.matches("[A-Z0-9][A-Z0-9-]{1,11}")) {
            throw new DomainException("License plate must have 2-12 letters, digits or hyphens.");
        }
        if (heightCm < 1 || heightCm > 220) {
            throw new DomainException("Vehicle height must be 1-220 cm; garage clearance is 220 cm.");
        }
        return plate;
    }
}
