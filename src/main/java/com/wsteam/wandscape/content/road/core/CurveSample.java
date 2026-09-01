package com.wsteam.wandscape.content.road.core;

/**
 * A sample point along a spline curve, containing its position and tangent direction.
 */
public record CurveSample(SplineVec3 position, SplineVec3 tangent, double u) {}
