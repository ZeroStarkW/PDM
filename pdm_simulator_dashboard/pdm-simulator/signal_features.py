"""Edge-side vibration feature extraction helpers (PHM 协议 2.0.0)."""

import numpy as np
from scipy.signal import butter, filtfilt, hilbert

# 与平台轴承字典库 SKF 6208 一致的特征频率系数（×转频）
BPFO_FACTOR = 3.5395
BPFI_FACTOR = 5.4605


def extract_band_energy_rms(freqs: np.ndarray, amplitudes: np.ndarray, target_hz: float,
                            tolerance: float = 0.03) -> float:
    if not np.isfinite(target_hz) or target_hz <= 0 or not 0 <= tolerance < 1:
        return 0.0
    bin_width = float(np.median(np.diff(freqs))) if freqs.size > 1 else 0.0
    # A Hann-windowed tone spreads across adjacent FFT bins; include its main lobe
    # when the requested percentage band is narrower than the available resolution.
    half_width = max(target_hz * tolerance, 1.5 * bin_width)
    mask = (freqs >= target_hz - half_width) & (freqs <= target_hz + half_width)
    # FFT amplitudes are coherent-gain corrected. This compensates for Hann-window power spread.
    return float(np.sqrt(np.sum(np.square(amplitudes[mask])) * (2.0 / 3.0)))


def extract_envelope_features(signal: np.ndarray, sample_rate: float, bpfo_hz: float,
                              tolerance: float = 0.04,
                              bpfi_hz: float | None = None) -> tuple[float, float, float]:
    """解调 1.0–1.8 kHz 共振带，返回 (包络 RMS, BPFO 频带能量, BPFI 频带能量)。"""
    if signal.size < 64 or sample_rate <= 3600 or not 0 < bpfo_hz < sample_rate / 2:
        return 0.0, 0.0, 0.0
    b, a = butter(4, [1000.0 / (sample_rate / 2.0), 1800.0 / (sample_rate / 2.0)], btype="bandpass")
    carrier = filtfilt(b, a, signal)
    envelope = np.abs(hilbert(carrier))
    envelope_rms = float(np.sqrt(np.mean(np.square(envelope))))
    envelope_ac = envelope - np.mean(envelope)
    window = np.hanning(signal.size)
    window_correction = float(np.mean(window))
    spectrum = np.abs(np.fft.rfft(envelope_ac * window)) / (signal.size / 2) / window_correction
    frequencies = np.fft.rfftfreq(signal.size, d=1.0 / sample_rate)
    bpfo_amp = extract_band_energy_rms(frequencies, spectrum, bpfo_hz, tolerance)
    bpfi_amp = 0.0
    if bpfi_hz is not None and 0 < bpfi_hz < sample_rate / 2:
        bpfi_amp = extract_band_energy_rms(frequencies, spectrum, bpfi_hz, tolerance)
    return envelope_rms, bpfo_amp, bpfi_amp


def extract_envelope_spectrum(signal: np.ndarray, sample_rate: float) -> tuple[np.ndarray, np.ndarray]:
    """完整希尔伯特包络谱（1.0–1.8 kHz 带通解调），供看板与测试验证特征谱线。"""
    if signal.size < 64 or sample_rate <= 3600:
        return np.array([]), np.array([])
    b, a = butter(4, [1000.0 / (sample_rate / 2.0), 1800.0 / (sample_rate / 2.0)], btype="bandpass")
    carrier = filtfilt(b, a, signal)
    envelope = np.abs(hilbert(carrier))
    envelope_ac = envelope - np.mean(envelope)
    window = np.hanning(signal.size)
    window_correction = float(np.mean(window))
    spectrum = np.abs(np.fft.rfft(envelope_ac * window)) / (signal.size / 2) / window_correction
    frequencies = np.fft.rfftfreq(signal.size, d=1.0 / sample_rate)
    return frequencies, spectrum
