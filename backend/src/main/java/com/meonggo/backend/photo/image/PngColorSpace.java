package com.meonggo.backend.photo.image;

import java.awt.color.ColorSpace;

/** PNG gAMA/cHRM의 전달 함수·원색·백색점을 sRGB D65로 변환한다. */
final class PngColorSpace extends ColorSpace {
    private static final ColorSpace SRGB = ColorSpace.getInstance(CS_sRGB);
    private static final double[] STANDARD = {0.3127, 0.3290, 0.64, 0.33, 0.30, 0.60, 0.15, 0.06};
    private static final double[][] BRADFORD = {
        {0.8951, 0.2664, -0.1614}, {-0.7502, 1.7135, 0.0367}, {0.0389, -0.0685, 1.0296}
    };
    private final double gamma;
    private final boolean gray;
    private final double[][] toStandard;
    private final double[][] fromStandard;

    PngColorSpace(boolean gray, double gamma, double[] chromaticities) {
        super(gray ? TYPE_GRAY : TYPE_RGB, gray ? 1 : 3);
        this.gray = gray;
        this.gamma = gamma;
        double[] points = chromaticities == null ? STANDARD : chromaticities;
        double[][] adaptation = adaptation(white(points), white(STANDARD));
        toStandard =
                multiply(inverse(primaries(STANDARD)), multiply(adaptation, primaries(points)));
        fromStandard = inverse(toStandard);
    }

    @Override
    public float[] toRGB(float[] components) {
        double[] linear = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            double value = components[gray ? 0 : channel];
            linear[channel] = gamma == 0 ? linear(value) : Math.pow(value, 1 / gamma);
        }
        double[] standard = gray ? linear : multiply(toStandard, linear);
        return new float[] {encoded(standard[0]), encoded(standard[1]), encoded(standard[2])};
    }

    @Override
    public float[] fromRGB(float[] rgb) {
        double[] original =
                multiply(
                        fromStandard,
                        new double[] {linear(rgb[0]), linear(rgb[1]), linear(rgb[2])});
        float[] result = new float[getNumComponents()];
        for (int channel = 0; channel < result.length; channel++) {
            double value = Math.clamp(original[channel], 0, 1);
            result[channel] = gamma == 0 ? encoded(value) : (float) Math.pow(value, gamma);
        }
        return result;
    }

    @Override
    public float[] toCIEXYZ(float[] components) {
        return SRGB.toCIEXYZ(toRGB(components));
    }

    @Override
    public float[] fromCIEXYZ(float[] xyz) {
        return fromRGB(SRGB.fromCIEXYZ(xyz));
    }

    private static double linear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static float encoded(double value) {
        value = Math.clamp(value, 0, 1);
        return (float)
                (value <= 0.0031308 ? value * 12.92 : 1.055 * Math.pow(value, 1 / 2.4) - 0.055);
    }

    private static double[] white(double[] points) {
        return xyz(points[0], points[1]);
    }

    private static double[] xyz(double x, double y) {
        if (x < 0 || y <= 0 || x + y > 1.00001) {
            throw new IllegalArgumentException("Invalid chromaticity");
        }
        return new double[] {x / y, 1, (1 - x - y) / y};
    }

    private static double[][] primaries(double[] points) {
        double[][] matrix = new double[3][3];
        for (int column = 0; column < 3; column++) {
            double[] primary = xyz(points[2 + column * 2], points[3 + column * 2]);
            for (int row = 0; row < 3; row++) {
                matrix[row][column] = primary[row];
            }
        }
        double[] scale = multiply(inverse(matrix), white(points));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                matrix[row][column] *= scale[column];
            }
        }
        return matrix;
    }

    private static double[][] adaptation(double[] source, double[] target) {
        double[] sourceCone = multiply(BRADFORD, source);
        double[] targetCone = multiply(BRADFORD, target);
        double[][] scale = new double[3][3];
        for (int channel = 0; channel < 3; channel++) {
            scale[channel][channel] = targetCone[channel] / sourceCone[channel];
        }
        return multiply(inverse(BRADFORD), multiply(scale, BRADFORD));
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                result[row] += matrix[row][column] * vector[column];
            }
        }
        return result;
    }

    private static double[][] multiply(double[][] left, double[][] right) {
        double[][] result = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                for (int index = 0; index < 3; index++) {
                    result[row][column] += left[row][index] * right[index][column];
                }
            }
        }
        return result;
    }

    private static double[][] inverse(double[][] matrix) {
        double[][] augmented = new double[3][6];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(matrix[row], 0, augmented[row], 0, 3);
            augmented[row][row + 3] = 1;
        }
        for (int column = 0; column < 3; column++) {
            int pivot = column;
            for (int row = column + 1; row < 3; row++) {
                if (Math.abs(augmented[row][column]) > Math.abs(augmented[pivot][column])) {
                    pivot = row;
                }
            }
            double[] swap = augmented[column];
            augmented[column] = augmented[pivot];
            augmented[pivot] = swap;
            double divisor = augmented[column][column];
            if (!Double.isFinite(divisor) || Math.abs(divisor) < 1e-12) {
                throw new IllegalArgumentException("Invalid chromaticity matrix");
            }
            for (int index = 0; index < 6; index++) {
                augmented[column][index] /= divisor;
            }
            for (int row = 0; row < 3; row++) {
                if (row == column) {
                    continue;
                }
                double factor = augmented[row][column];
                for (int index = 0; index < 6; index++) {
                    augmented[row][index] -= factor * augmented[column][index];
                }
            }
        }
        double[][] result = new double[3][3];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(augmented[row], 3, result[row], 0, 3);
        }
        return result;
    }
}
