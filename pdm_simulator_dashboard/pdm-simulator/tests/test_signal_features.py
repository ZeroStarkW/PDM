import unittest

import numpy as np

from signal_features import extract_band_energy_rms, extract_envelope_features


class SignalFeaturesTest(unittest.TestCase):
    def test_band_energy_is_stable_across_small_frequency_drift(self):
        sample_rate = 4096
        sample_count = 4096
        time = np.arange(sample_count) / sample_rate
        frequencies = np.fft.rfftfreq(sample_count, 1.0 / sample_rate)
        window = np.hanning(sample_count)

        amplitudes = []
        for frequency in (1480 / 60, 1500 / 60, 1520 / 60):
            signal = 0.45 * np.sin(2 * np.pi * frequency * time)
            spectrum = np.abs(np.fft.rfft(signal * window)) / (sample_count / 2) / np.mean(window)
            amplitudes.append(extract_band_energy_rms(frequencies, spectrum, frequency, tolerance=0.03))

        self.assertLess((max(amplitudes) - min(amplitudes)) / np.mean(amplitudes), 0.02)
        self.assertLess(abs(np.mean(amplitudes) - 0.45) / 0.45, 0.01)

    def test_envelope_detects_bpfo_modulation(self):
        sample_rate = 4096
        sample_count = 4096
        time = np.arange(sample_count) / sample_rate
        carrier = np.sin(2 * np.pi * 1400 * time)
        clean = carrier
        damaged = (1 + 0.25 * np.sin(2 * np.pi * 143 * time)) * carrier

        _, clean_bpfo, _ = extract_envelope_features(clean, sample_rate, 143)
        _, damaged_bpfo, _ = extract_envelope_features(damaged, sample_rate, 143)

        self.assertGreater(damaged_bpfo, clean_bpfo * 3)


if __name__ == "__main__":
    unittest.main()
