package com.conveyal.gtfs.error;

/** Indicates that a number is out of the acceptable range. */
public class RangeError extends GTFSError {

    double min, max, actual;

    public RangeError(String file, long line, String field, double min, double max, double actual) {
        super(file, line, field);
        this.min = min;
        this.max = max;
        this.actual = actual;
    }

    @Override public String getMessage() {
        return String.format("Number %s outside of acceptable range [%s,%s].", actual, min, max);
    }

}
