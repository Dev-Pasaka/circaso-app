#!/usr/bin/env python3
"""
Writes the payment chime: the sound Add Money plays when a top-up's USDC lands (Pasaka, 2026-11-23).

A clean two-note chime, a rising fifth (E6 then B6), each a soft bell: a sine with a quieter octave and twelfth that
fade faster than the note itself, a 4 ms attack so it never clicks, and an exponential tail. About 0.6 s, mono 16-bit
PCM at 32 kHz (iOS System Sound Services and Android's SoundPool both play it as it is). It peaks at -9 dBFS, so its
loudness (about -21 dBFS RMS) is close to the coins sound's (-23): a pure tone sounds louder than its peak suggests.

    python3 scripts/make_payment_chime.py

writes `shared/src/commonMain/composeResources/files/payment_chime.wav` (iOS reads it from the app's resources) and
`androidApp/src/main/res/raw/payment_chime.wav` (Android's SoundPool loads it by name), the same bytes in both.
Standard library only, and deterministic: the same file every run.
"""
import math
import os
import struct
import wave

RATE = 32_000
LENGTH = 0.62  # seconds
PEAK = 10 ** (-9 / 20)

# (start s, frequency Hz, decay time constant s, level)
NOTES = [
    (0.000, 1318.51, 0.16, 0.85),  # E6
    (0.115, 1975.53, 0.22, 1.00),  # B6, a fifth above
]
# Partials as (multiple, level, how much faster than the note they fade).
PARTIALS = [(1.0, 1.0, 1.0), (2.0, 0.22, 1.8), (3.0, 0.06, 2.6)]
ATTACK = 0.004


def sample(t: float) -> float:
    total = 0.0
    for start, freq, tau, level in NOTES:
        u = t - start
        if u < 0:
            continue
        attack = min(1.0, u / ATTACK)
        for multiple, part_level, faster in PARTIALS:
            f = freq * multiple
            if f >= RATE / 2:
                continue
            total += level * part_level * attack * math.exp(-u * faster / tau) * math.sin(2 * math.pi * f * u)
    return total


def main() -> None:
    n = int(RATE * LENGTH)
    raw = [sample(i / RATE) for i in range(n)]
    # The last 40 ms fade to silence so the file never ends on a step.
    fade = int(RATE * 0.04)
    for i in range(fade):
        raw[n - fade + i] *= 1 - (i + 1) / fade
    top = max(abs(x) for x in raw)
    frames = b"".join(struct.pack("<h", int(round(x / top * PEAK * 32767))) for x in raw)
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for path in ("shared/src/commonMain/composeResources/files/payment_chime.wav", "androidApp/src/main/res/raw/payment_chime.wav"):
        full = os.path.join(root, path)
        with wave.open(full, "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(RATE)
            out.writeframes(frames)
        print(f"{path}: {os.path.getsize(full)} bytes, {LENGTH:.2f} s")


if __name__ == "__main__":
    main()
