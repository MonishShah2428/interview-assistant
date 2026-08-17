package com.interviewprep.track.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * No {@code sourceJd} — that's the level-inference JD path being built later. A 5,000-character
 * "goal" on this path is either a pasted JD on the wrong endpoint or an attack, hence the length
 * cap.
 */
record CreateTrackRequest(
    @NotBlank @Size(max = 500) String goal, @NotBlank @Size(max = 50) String level) {}
