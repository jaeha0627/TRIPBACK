package com.tripback.backend.trip.recommendation;

import com.tripback.backend.trip.provider.Wgs84Point;

/** Vincenty inverse on the WGS84 ellipsoid. Non-convergence is conservatively outside the radius. */
final class RegionDistance {
    static final double MAX_METRES = 20_000;
    static double metres(Wgs84Point a, Wgs84Point b) {
        if (a.equals(b)) return 0;
        double f = 1 / 298.257223563, major = 6_378_137, minor = (1 - f) * major;
        double u1 = Math.atan((1 - f) * Math.tan(Math.toRadians(a.latitude())));
        double u2 = Math.atan((1 - f) * Math.tan(Math.toRadians(b.latitude())));
        double sinU1 = Math.sin(u1), cosU1 = Math.cos(u1), sinU2 = Math.sin(u2), cosU2 = Math.cos(u2);
        double difference = Math.IEEEremainder(Math.toRadians(b.longitude() - a.longitude()), 2 * Math.PI);
        double lambda = difference, sigma = 0, sinSigma = 0, cosSigma = 0, cosSqAlpha = 0, cos2SigmaM = 0;
        boolean converged = false;
        for (int i = 0; i < 200; i++) {
            double sinLambda = Math.sin(lambda), cosLambda = Math.cos(lambda);
            double x = cosU2 * sinLambda, y = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda;
            sinSigma = Math.hypot(x, y);
            if (sinSigma == 0) return Math.cos(difference) > 0 ? 0 : Double.POSITIVE_INFINITY;
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            double sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
            cosSqAlpha = Math.max(0, 1 - sinAlpha * sinAlpha);
            cos2SigmaM = cosSqAlpha < 1e-15 ? 0 : cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha;
            double c = f / 16 * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha));
            double previous = lambda;
            lambda = difference + (1 - c) * f * sinAlpha * (sigma + c * sinSigma
                    * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
            if (Math.abs(lambda - previous) < 1e-12) { converged = true; break; }
        }
        if (!converged) return Double.POSITIVE_INFINITY;
        double squared = cosSqAlpha * (major * major - minor * minor) / (minor * minor);
        double aa = 1 + squared / 16384 * (4096 + squared * (-768 + squared * (320 - 175 * squared)));
        double bb = squared / 1024 * (256 + squared * (-128 + squared * (74 - 47 * squared)));
        double delta = bb * sinSigma * (cos2SigmaM + bb / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
                - bb / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
        return minor * aa * (sigma - delta);
    }
    static boolean contains(Wgs84Point center, Wgs84Point point) {
        double distance = metres(center, point); return Double.isFinite(distance) && distance <= MAX_METRES;
    }
}
