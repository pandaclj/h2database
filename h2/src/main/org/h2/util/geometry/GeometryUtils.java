/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.util.geometry;

import org.h2.util.Bits;

/**
 * Utilities for GEOMETRY data type.
 */
public final class GeometryUtils {

    /**
     * Converter output target.
     */
    public static abstract class Target {

        public Target() {
        }

        /**
         * Invoked before writing a POINT.
         *
         * @param srid
         *            SRID
         */
        protected void startPoint(int srid) {
        }

        /**
         * Invoked before writing a LINESTRING.
         *
         * @param srid
         *            SRID
         * @param numPoints
         *            number of points in line string
         */
        protected void startLineString(int srid, int numPoints) {
        }

        /**
         * Invoked before writing a POLYGON.
         *
         * @param srid
         *            SRID
         * @param numInner
         *            number of inner polygons
         * @param numPoints
         *            number of points in outer polygon
         */
        protected void startPolygon(int srid, int numInner, int numPoints) {
        }

        /**
         * Invoked before writing an inner polygon in POLYGON.
         *
         * @param numInner
         *            number of points in inner polygon
         */
        protected void startPolygonInner(int numInner) {
        }

        /**
         * Invoked after writing of non-empty POLYGON.
         */
        protected void endNonEmptyPolygon() {
        }

        /**
         * Invoked before writing of a collection.
         *
         * @param type
         *            type of collection, one of
         *            {@link GeometryUtils#MULTI_POINT},
         *            {@link GeometryUtils#MULTI_LINE_STRING},
         *            {@link GeometryUtils#MULTI_POLYGON},
         *            {@link GeometryUtils#GEOMETRY_COLLECTION}
         * @param srid
         *            SRID
         * @param numItems
         *            number of items in this collection
         */
        protected void startCollection(int type, int srid, int numItems) {
        }

        /**
         * Invoked before writing of a collection item.
         *
         * @param index
         *            0-based index of this item in the collection
         * @param total
         *            total number of items in the collection
         * @return output target that should be used for processing of this
         *         collection item. May return this target or an custom
         *         sub-target.
         */
        protected Target startCollectionItem(int index, int total) {
            return this;
        }

        /**
         * Invoked after writing of a collection item. This method is invoked on
         * the same target that was used for
         * {@link #startCollectionItem(int, int)}.
         *
         * @param target
         *            the result of {@link #startCollectionItem(int, int)}
         * @param index
         *            0-based index of this item in the collection
         * @param total
         *            total number of items in the collection
         */
        protected void endCollectionItem(Target target, int index, int total) {
        }

        /**
         * Invoked after writing of a collection.
         *
         * @param type
         *            type of collection, see
         *            {@link #startCollection(int, int, int)}
         */
        protected void endCollection(int type) {
        }

        /**
         * Invoked to add a coordinate to a geometry.
         *
         * @param x
         *            X coordinate
         * @param y
         *            Y coordinate
         * @param z
         *            Z coordinate (NaN if not used)
         * @param m
         *            M coordinate (NaN if not used)
         * @param index
         *            0-based index of coordinate in the current sequence
         * @param total
         *            total number of coordinates in the current sequence
         */
        protected abstract void addCoordinate(double x, double y, double z, double m, int index, int total);

    }

    /**
     * Converter output target that calculates an envelope.
     */
    public static final class EnvelopeTarget extends Target {

        /**
         * Enables or disables the envelope calculation. Inner rings of polygons
         * are not counted.
         */
        private boolean enabled;

        /**
         * Whether envelope was set.
         */
        private boolean set;

        double minX, maxX, minY, maxY;

        /**
         * Creates a new envelope calculation target.
         */
        public EnvelopeTarget() {
        }

        @Override
        protected void startPoint(int srid) {
            enabled = true;
        }

        @Override
        protected void startLineString(int srid, int numPoints) {
            enabled = true;
        }

        @Override
        protected void startPolygon(int srid, int numInner, int numPoints) {
            enabled = true;
        }

        @Override
        protected void startPolygonInner(int numInner) {
            enabled = false;
        }

        @Override
        protected void addCoordinate(double x, double y, double z, double m, int index, int total) {
            if (enabled) {
                if (!set) {
                    minX = maxX = x;
                    minY = maxY = y;
                    set = true;
                } else {
                    if (minX > x) {
                        minX = x;
                    }
                    if (maxX < x) {
                        maxX = x;
                    }
                    if (minY > y) {
                        minY = y;
                    }
                    if (maxY < y) {
                        maxY = y;
                    }
                }
            }
        }

        /**
         * Returns the envelope.
         *
         * @return the envelope, or null
         */
        public double[] getEnvelope() {
            return set ? new double[] { minX, maxX, minY, maxY } : null;
        }

    }

    /**
     * Converter output target that determines minimal dimension system for a
     * geometry.
     */
    public static final class DimensionSystemTarget extends Target {

        private boolean hasZ;

        private boolean hasM;

        /**
         * Creates a new dimension system determination target.
         */
        public DimensionSystemTarget() {
        }

        @Override
        protected void addCoordinate(double x, double y, double z, double m, int index, int total) {
            if (!hasZ && !Double.isNaN(z)) {
                hasZ = true;
            }
            if (!hasM && !Double.isNaN(m)) {
                hasM = true;
            }
        }

        /**
         * Returns the minimal dimension system.
         *
         * @return the minimal dimension system
         */
        public int getDimensionSystem() {
            return (hasZ ? DIMENSION_SYSTEM_XYZ : 0) | (hasM ? DIMENSION_SYSTEM_XYM : 0);
        }

    }

    /**
     * Converter output target that calculates an envelope and determines the
     * minimal dimension system.
     */
    public static final class EnvelopeAndDimensionSystemTarget extends Target {

        /**
         * Enables or disables the envelope calculation. Inner rings of polygons
         * are not counted.
         */
        private boolean enabled;

        /**
         * Whether envelope was set.
         */
        private boolean set;

        double minX, maxX, minY, maxY;

        private boolean hasZ;

        private boolean hasM;

        /**
         * Creates a new envelope and dimension system calculation target.
         */
        public EnvelopeAndDimensionSystemTarget() {
        }

        @Override
        protected void startPoint(int srid) {
            enabled = true;
        }

        @Override
        protected void startLineString(int srid, int numPoints) {
            enabled = true;
        }

        @Override
        protected void startPolygon(int srid, int numInner, int numPoints) {
            enabled = true;
        }

        @Override
        protected void startPolygonInner(int numInner) {
            enabled = false;
        }

        @Override
        protected void addCoordinate(double x, double y, double z, double m, int index, int total) {
            if (!hasZ && !Double.isNaN(z)) {
                hasZ = true;
            }
            if (!hasM && !Double.isNaN(m)) {
                hasM = true;
            }
            if (enabled) {
                if (!set) {
                    minX = maxX = x;
                    minY = maxY = y;
                    set = true;
                } else {
                    if (minX > x) {
                        minX = x;
                    }
                    if (maxX < x) {
                        maxX = x;
                    }
                    if (minY > y) {
                        minY = y;
                    }
                    if (maxY < y) {
                        maxY = y;
                    }
                }
            }
        }

        /**
         * Returns the envelope.
         *
         * @return the envelope, or null
         */
        public double[] getEnvelope() {
            return set ? new double[] { minX, maxX, minY, maxY } : null;
        }

        /**
         * Returns the minimal dimension system.
         *
         * @return the minimal dimension system
         */
        public int getDimensionSystem() {
            return (hasZ ? DIMENSION_SYSTEM_XYZ : 0) | (hasM ? DIMENSION_SYSTEM_XYM : 0);
        }

    }

    /**
     * POINT geometry type.
     */
    static final int POINT = 1;

    /**
     * LINESTRING geometry type.
     */
    static final int LINE_STRING = 2;

    /**
     * POLYGON geometry type.
     */
    static final int POLYGON = 3;

    /**
     * MULTIPOINT geometry type.
     */
    static final int MULTI_POINT = 4;

    /**
     * MULTILINESTRING geometry type.
     */
    static final int MULTI_LINE_STRING = 5;

    /**
     * MULTIPOLYGON geometry type.
     */
    static final int MULTI_POLYGON = 6;

    /**
     * GEOMETRYCOLLECTION geometry type.
     */
    static final int GEOMETRY_COLLECTION = 7;

    /**
     * Number of X coordinate.
     */
    public static final int X = 0;

    /**
     * Number of Y coordinate.
     */
    public static final int Y = 1;

    /**
     * Number of Z coordinate.
     */
    public static final int Z = 2;

    /**
     * Number of M coordinate.
     */
    public static final int M = 3;

    /**
     * Code of 2D (XY) dimension system.
     */
    public static final int DIMENSION_SYSTEM_XY = 0;

    /**
     * Code of Z (XYZ) dimension system. Can also be used in bit masks to
     * determine presence of dimension Z.
     */
    public static final int DIMENSION_SYSTEM_XYZ = 1;

    /**
     * Code of M (XYM) dimension system. Can also be used in bit masks to
     * determine presence of dimension M.
     */
    public static final int DIMENSION_SYSTEM_XYM = 2;

    /**
     * Code of ZM (XYZM) dimension system. Can be also combined from
     * {@link #DIMENSION_SYSTEM_XYZ} and {@link #DIMENSION_SYSTEM_XYM} using
     * bitwise OR.
     */
    public static final int DIMENSION_SYSTEM_XYZM = 3;

    /**
     * Minimum X coordinate index.
     */
    public static final int MIN_X = 0;

    /**
     * Maximum X coordinate index.
     */
    public static final int MAX_X = 1;

    /**
     * Minimum Y coordinate index.
     */
    public static final int MIN_Y = 2;

    /**
     * Maximum Y coordinate index.
     */
    public static final int MAX_Y = 3;

    /**
     * Calculates an envelope of a specified geometry.
     *
     * @param ewkb
     *            EWKB of a geometry
     * @return envelope, or null
     */
    public static double[] getEnvelope(byte[] ewkb) {
        EnvelopeAndDimensionSystemTarget target = new EnvelopeAndDimensionSystemTarget();
        EWKBUtils.parseEKWB(ewkb, target);
        return target.getEnvelope();
    }

    /**
     * Converts an envelope to a WKB.
     *
     * @param envelope
     *            envelope, or null
     * @return WKB, or null
     */
    public static byte[] envelope2wkb(double[] envelope) {
        if (envelope == null) {
            return null;
        }
        byte[] result;
        double minX = envelope[MIN_X], maxX = envelope[MAX_X], minY = envelope[MIN_Y], maxY = envelope[MAX_Y];
        if (minX == maxX && minY == maxY) {
            result = new byte[21];
            result[4] = POINT;
            Bits.writeLong(result, 5, Double.doubleToRawLongBits(minX));
            Bits.writeLong(result, 13, Double.doubleToRawLongBits(minY));
        } else if (minX == maxX || minY == maxY) {
            result = new byte[41];
            result[4] = LINE_STRING;
            result[8] = 2;
            Bits.writeLong(result, 9, Double.doubleToRawLongBits(minX));
            Bits.writeLong(result, 17, Double.doubleToRawLongBits(minY));
            Bits.writeLong(result, 25, Double.doubleToRawLongBits(maxX));
            Bits.writeLong(result, 33, Double.doubleToRawLongBits(maxY));
        } else {
            result = new byte[93];
            result[4] = POLYGON;
            result[8] = 1;
            result[12] = 5;
            Bits.writeLong(result, 13, Double.doubleToRawLongBits(minX));
            Bits.writeLong(result, 21, Double.doubleToRawLongBits(minY));
            Bits.writeLong(result, 29, Double.doubleToRawLongBits(minX));
            Bits.writeLong(result, 37, Double.doubleToRawLongBits(maxY));
            Bits.writeLong(result, 45, Double.doubleToRawLongBits(maxX));
            Bits.writeLong(result, 53, Double.doubleToRawLongBits(maxY));
            Bits.writeLong(result, 61, Double.doubleToRawLongBits(maxX));
            Bits.writeLong(result, 69, Double.doubleToRawLongBits(minY));
            Bits.writeLong(result, 77, Double.doubleToRawLongBits(minX));
            Bits.writeLong(result, 85, Double.doubleToRawLongBits(minY));
        }
        return result;
    }

    /**
     * Checks whether two envelopes intersect with each other.
     *
     * @param envelope1
     *            first envelope, or null
     * @param envelope2
     *            second envelope, or null
     * @return whether the specified envelopes intersects
     */
    public static boolean intersects(double[] envelope1, double[] envelope2) {
        return envelope1 != null && envelope2 != null //
                && envelope1[MAX_X] >= envelope2[MIN_X] //
                && envelope1[MIN_X] <= envelope2[MAX_X] //
                && envelope1[MAX_Y] >= envelope2[MIN_Y] //
                && envelope1[MIN_Y] <= envelope2[MAX_Y];
    }

    /**
     * Returns union of two envelopes. This method does not modify the specified
     * envelopes, but may return one of them as a result.
     *
     * @param envelope1
     *            first envelope, or null
     * @param envelope2
     *            second envelope, or null
     * @return union of two envelopes
     */
    public static double[] union(double[] envelope1, double[] envelope2) {
        if (envelope1 == null) {
            return envelope2;
        } else if (envelope2 == null) {
            return envelope1;
        }

        double minX1 = envelope1[MIN_X], maxX1 = envelope1[MAX_X], minY1 = envelope1[MIN_Y], maxY1 = envelope1[MAX_Y];
        double minX2 = envelope2[MIN_X], maxX2 = envelope2[MAX_X], minY2 = envelope2[MIN_Y], maxY2 = envelope2[MAX_Y];
        if (minX1 > minX2) {
            minX1 = minX2;
        }
        if (maxX1 < maxX2) {
            maxX1 = maxX2;
        }
        if (minY1 > minY2) {
            minY1 = minY2;
        }
        if (maxY1 < maxY2) {
            maxY1 = maxY2;
        }
        return new double[] { minX1, maxX1, minY1, maxY1 };
    }

    /**
     * Normalizes all NaNs into single type on NaN and negative zero to positive
     * zero.
     *
     * @param d
     *            double value
     * @return normalized value
     */
    static double toCanonicalDouble(double d) {
        return Double.isNaN(d) ? Double.NaN : d == 0d ? 0d : d;
    }

    private GeometryUtils() {
    }

}
