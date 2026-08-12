package com.flamingo.tiktaktoe.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Throwaway. Exists only to make CI red on the ci-gate-proof branch, proving
 * that branch protection blocks the merge button. This branch is never merged
 * and is deleted once the evidence is captured.
 */
class CiGateProofTest {

    @Test
    void failsOnPurpose() {
        assertThat(1).isEqualTo(2);
    }
}
