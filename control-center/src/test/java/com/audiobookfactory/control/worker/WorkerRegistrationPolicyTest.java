package com.audiobookfactory.control.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerRegistrationPolicyTest {

    @Test
    void expiredRegistrationCanBeReplacedByTheSingleUserColabWorker() {
        assertThat(WorkerService.canReplaceExistingRegistration(
                "server-playwright-worker", "EXPIRED", "colab-worker"))
                .isTrue();
    }

    @Test
    void activeRegistrationCannotBeReplacedByAnotherWorkerName() {
        assertThat(WorkerService.canReplaceExistingRegistration(
                "server-playwright-worker", "ACTIVE", "colab-worker"))
                .isFalse();
    }
}
