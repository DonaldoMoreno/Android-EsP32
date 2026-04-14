#pragma once
/**
 * @file PIDController.h
 * @brief Discrete PID controller with anti-windup and output clamping.
 *
 * Usage:
 *   PIDController pid(2.0f, 0.5f, 0.1f);
 *   pid.setOutputLimits(0.0f, 100.0f);
 *   float output = pid.compute(setpoint, measurement, dt_seconds);
 */

class PIDController {
public:
    /**
     * Construct a PID controller.
     *
     * @param kp Proportional gain.
     * @param ki Integral gain.
     * @param kd Derivative gain.
     */
    PIDController(float kp, float ki, float kd)
        : _kp(kp), _ki(ki), _kd(kd),
          _integralSum(0.0f),
          _outMin(0.0f), _outMax(100.0f),
          _initialized(false) {}

    /** Set output clamping limits (default 0–100). */
    void setOutputLimits(float minOutput, float maxOutput) {
        _outMin = minOutput;
        _outMax = maxOutput;
    }

    /** Set PID gains at runtime. */
    void setGains(float kp, float ki, float kd) {
        _kp = kp;
        _ki = ki;
        _kd = kd;
    }

    /** Reset controller state (integral accumulator and derivative memory). */
    void reset() {
        _integralSum = 0.0f;
        _prevError   = 0.0f;
        _initialized = false;
    }

    /**
     * Compute PID output.
     *
     * @param setpoint  Desired value.
     * @param measured  Current measured value.
     * @param dt        Time elapsed since last call (seconds).
     * @return Clamped output in [_outMin, _outMax].
     */
    float compute(float setpoint, float measured, float dt) {
        if (dt <= 0.0f) return _outMin;

        float error = setpoint - measured;

        // Integral term with anti-windup clamping
        _integralSum += error * dt;
        _integralSum = clamp(_integralSum * _ki, _outMin, _outMax) / _ki; // back-calculate

        // Derivative on measurement to avoid derivative kick on setpoint changes
        float derivative = 0.0f;
        if (_initialized) {
            derivative = -(measured - _prevMeasured) / dt;
        }
        _prevMeasured = measured;
        _initialized  = true;

        float output = (_kp * error) + (_ki * _integralSum) + (_kd * derivative);
        return clamp(output, _outMin, _outMax);
    }

private:
    float _kp, _ki, _kd;
    float _integralSum;
    float _prevMeasured = 0.0f;
    float _outMin, _outMax;
    bool  _initialized;

    static float clamp(float val, float lo, float hi) {
        if (val < lo) return lo;
        if (val > hi) return hi;
        return val;
    }
};
