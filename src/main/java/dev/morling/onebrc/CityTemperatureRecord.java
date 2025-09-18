package dev.morling.onebrc;

public final class CityTemperatureRecord {
    public String name;
    public double min;
    public double max;
    public double sum;
    public long count;

    public CityTemperatureRecord() {
        this.name = null;
        this.min = Double.POSITIVE_INFINITY;
        this.max = Double.NEGATIVE_INFINITY;
        this.sum = 0.0;
        this.count = 0;
    }

    public CityTemperatureRecord(String k) {
        this.name = k;
        this.min = Double.POSITIVE_INFINITY;
        this.max = Double.NEGATIVE_INFINITY;
        this.sum = 0.0;
        this.count = 0;
    }

    public void accept(double v) {
        if (count == 0) {
            min = v;
            max = v;
            sum = v;
            count = 1;
        } else {
            if (v < min)
                min = v;
            if (v > max)
                max = v;
            sum += v;
            count++;
        }
    }
}
