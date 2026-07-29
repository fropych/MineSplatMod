package io.github.yromko.minesplat.workflow;

final class JobFailedException extends RuntimeException {
    JobFailedException(String message) {
        super(message);
    }
}
